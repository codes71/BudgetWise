#include "raj_engine.h"

#include <chrono>
#include <cstdio>
#include <mutex>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "raj_text.h"

namespace {

constexpr int kSampleRate = 16000;

using Clock = std::chrono::steady_clock;

int64_t ms_since(Clock::time_point t0) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count();
}

bool readable(const std::string & path) {
    std::FILE * f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) {
        return false;
    }
    std::fclose(f);
    return true;
}

// Same rendering as llama-server: no lstrip, control tokens dropped.
std::string token_piece(const llama_vocab * vocab, llama_token tok) {
    char buf[128];
    const int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (n >= 0) {
        return std::string(buf, n);
    }
    std::string s(-n, '\0');
    const int m = llama_token_to_piece(vocab, tok, s.data(), (int32_t) s.size(), 0, false);
    s.resize(m > 0 ? m : 0);
    return s;
}

struct OneTokenBatch {
    llama_batch b = llama_batch_init(1, 0, 1);
    ~OneTokenBatch() { llama_batch_free(b); }
};

}  // namespace

std::unique_ptr<RajEngine> RajEngine::load(const std::string & model, const std::string & mmproj,
                                           const RajParams & p, std::string * err) {
    auto fail = [err](const std::string & msg) {
        if (err != nullptr) {
            *err = msg;
        }
        return std::unique_ptr<RajEngine>();
    };
    if (p.n_threads < 1 || p.n_ctx < 1) {
        return fail("n_threads and n_ctx must be positive");
    }
    if (!readable(model)) {
        return fail("cannot read model file: " + model);
    }
    if (!readable(mmproj)) {
        return fail("cannot read mmproj file: " + mmproj);
    }

    static std::once_flag backend_once;
    std::call_once(backend_once, llama_backend_init);

    std::unique_ptr<RajEngine> e(new RajEngine());
    e->params_ = p;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    e->model_ = llama_model_load_from_file(model.c_str(), mp);
    if (e->model_ == nullptr) {
        return fail("failed to load model: " + model);
    }
    e->vocab_ = llama_model_get_vocab(e->model_);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) p.n_ctx;
    cp.n_threads = p.n_threads;
    cp.n_threads_batch = p.n_threads;
    cp.no_perf = true;
    e->lctx_ = llama_init_from_model(e->model_, cp);
    if (e->lctx_ == nullptr) {
        return fail("failed to create llama context");
    }
    llama_set_abort_callback(e->lctx_, abort_cb, e.get());

    mtmd_context_params mtp = mtmd_context_params_default();
    mtp.use_gpu = false;
    mtp.warmup = false;
    mtp.print_timings = false;
    mtp.n_threads = p.n_threads;
    e->mctx_ = mtmd_init_from_file(mmproj.c_str(), e->model_, mtp);
    if (e->mctx_ == nullptr) {
        return fail("failed to load mmproj: " + mmproj);
    }
    if (!mtmd_support_audio(e->mctx_)) {
        return fail("mmproj has no audio encoder: " + mmproj);
    }
    if (mtmd_get_audio_sample_rate(e->mctx_) != kSampleRate) {
        return fail("mmproj expects " + std::to_string(mtmd_get_audio_sample_rate(e->mctx_)) + " Hz audio");
    }

    e->smpl_ = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(e->smpl_, llama_sampler_init_greedy());
    return e;
}

RajResult RajEngine::transcribe(const float * pcm16k, size_t n_samples, int max_new_tokens) {
    abort_.store(false);
    RajResult r;
    auto fail = [&r](const std::string & msg) {
        r.status = RAJ_STATUS_ERROR;
        r.text.clear();
        r.raw = msg;
        return r;
    };
    auto aborted = [&r](std::string partial) {
        r.status = RAJ_STATUS_ABORTED;
        r.text.clear();
        r.raw = std::move(partial);
        return r;
    };
    if (pcm16k == nullptr || n_samples == 0) {
        return fail("empty audio");
    }
    if (max_new_tokens < 1) {
        return fail("max_new_tokens must be positive");
    }

    llama_memory_clear(llama_get_memory(lctx_), true);
    llama_sampler_reset(smpl_);

    const auto t_encode = Clock::now();
    mtmd::bitmap audio(mtmd_bitmap_init_from_audio(n_samples, pcm16k));
    const mtmd_bitmap * bitmaps[] = {audio.ptr.get()};
    const std::string prompt = raj_build_prompt("", params_.forced_language);
    const mtmd_input_text text = {prompt.c_str(), prompt.size(), /* add_special */ true, /* parse_special */ true};
    mtmd::input_chunks chunks(mtmd_input_chunks_init());
    const int32_t tok_rc = mtmd_tokenize(mctx_, chunks.ptr.get(), &text, bitmaps, 1);
    if (tok_rc != 0) {
        return fail("mtmd_tokenize failed: " + std::to_string(tok_rc));
    }
    for (size_t i = 0; i < chunks.size(); i++) {
        const mtmd_input_chunk * chunk = chunks[i];
        if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_AUDIO) {
            r.n_audio_tokens += (int) mtmd_input_chunk_get_n_tokens(chunk);
        }
    }

    // KV cells in use; generation stops when the context is full.
    const size_t n_ctx = llama_n_ctx(lctx_);
    size_t n_kv = mtmd_helper_get_n_tokens(chunks.ptr.get());
    if (n_kv >= n_ctx) {
        return fail("audio too long: " + std::to_string(n_kv) + " prompt tokens, n_ctx " + std::to_string(n_ctx));
    }
    if (abort_.load()) {
        return aborted("");
    }
    llama_pos n_past = 0;
    const int32_t eval_rc = mtmd_helper_eval_chunks(mctx_, lctx_, chunks.ptr.get(), 0, 0,
                                                    (int32_t) llama_n_batch(lctx_), true, &n_past);
    r.encode_ms = ms_since(t_encode);
    if (abort_.load()) {
        return aborted("");
    }
    if (eval_rc != 0) {
        return fail("mtmd_helper_eval_chunks failed: " + std::to_string(eval_rc));
    }

    const auto t_decode = Clock::now();
    OneTokenBatch one;
    llama_batch & batch = one.b;
    std::string raw;
    while (r.n_gen_tokens < max_new_tokens) {
        if (abort_.load()) {
            r.decode_ms = ms_since(t_decode);
            return aborted(raw);
        }
        const llama_token tok = llama_sampler_sample(smpl_, lctx_, -1);
        if (llama_vocab_is_eog(vocab_, tok)) {
            break;
        }
        r.n_gen_tokens++;
        raw += token_piece(vocab_, tok);
        if (r.n_gen_tokens >= max_new_tokens || n_kv >= n_ctx) {
            break;
        }
        batch.n_tokens = 1;
        batch.token[0] = tok;
        batch.pos[0] = n_past++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;
        const int32_t rc = llama_decode(lctx_, batch);
        n_kv++;
        if (rc != 0) {
            r.decode_ms = ms_since(t_decode);
            if (abort_.load()) {
                return aborted(raw);
            }
            return fail("llama_decode failed: " + std::to_string(rc));
        }
    }
    r.decode_ms = ms_since(t_decode);
    r.status = RAJ_STATUS_OK;
    r.text = raj_postprocess(raw);
    r.raw = std::move(raw);
    return r;
}

void RajEngine::request_abort() {
    abort_.store(true);
}

bool RajEngine::abort_cb(void * data) {
    return static_cast<RajEngine *>(data)->abort_.load();
}

RajEngine::~RajEngine() {
    if (smpl_ != nullptr) {
        llama_sampler_free(smpl_);
    }
    if (mctx_ != nullptr) {
        mtmd_free(mctx_);
    }
    if (lctx_ != nullptr) {
        llama_free(lctx_);
    }
    if (model_ != nullptr) {
        llama_model_free(model_);
    }
}
