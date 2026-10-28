// raj-host: run RajEngine over a clips.jsonl and write Task 1 style rows (spike/run_spike.py)
// plus model_id, llama_cpp_sha and n_audio_tokens. Rows only, no summary line.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "raj_engine.h"
#include "raj_text.h"

using json = nlohmann::ordered_json;

namespace {

constexpr int kSampleRate = 16000;

struct Args {
    std::string model, mmproj, clips, out, forced, model_id;
    int threads = 4;
    int max_new_tokens = 512;
    bool verbose = false;
};

void usage() {
    std::fprintf(stderr,
                 "usage: raj-host --model M --mmproj P --clips clips.jsonl --out out.jsonl\n"
                 "                [--forced Burmese] [--threads 4] [--max-new-tokens 512]\n"
                 "                [--model-id ID] [--verbose]\n");
}

bool parse_args(int argc, char ** argv, Args & a) {
    for (int i = 1; i < argc; i++) {
        const std::string k = argv[i];
        if (k == "--verbose") {
            a.verbose = true;
            continue;
        }
        if (i + 1 >= argc) {
            return false;
        }
        const std::string v = argv[++i];
        if (k == "--model") a.model = v;
        else if (k == "--mmproj") a.mmproj = v;
        else if (k == "--clips") a.clips = v;
        else if (k == "--out") a.out = v;
        else if (k == "--forced") a.forced = v;
        else if (k == "--model-id") a.model_id = v;
        else if (k == "--threads") a.threads = std::atoi(v.c_str());
        else if (k == "--max-new-tokens") a.max_new_tokens = std::atoi(v.c_str());
        else return false;
    }
    if (a.model_id.empty()) {
        const size_t slash = a.model.find_last_of('/');
        a.model_id = slash == std::string::npos ? a.model : a.model.substr(slash + 1);
    }
    return !a.model.empty() && !a.mmproj.empty() && !a.clips.empty() && !a.out.empty();
}

// Warnings and errors only, unless --verbose.
bool g_verbose = false;
ggml_log_level g_last_level = GGML_LOG_LEVEL_NONE;

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level != GGML_LOG_LEVEL_CONT) {
        g_last_level = level;
    }
    if (g_verbose || g_last_level >= GGML_LOG_LEVEL_WARN) {
        std::fputs(text, stderr);
    }
}

// Decodes (and resamples) the file with libmtmd, the way llama-server reads multimodal_data.
bool load_pcm(const mtmd_context * mctx, const std::string & path, std::vector<float> & pcm) {
    mtmd_helper_bitmap_wrapper w = mtmd_helper_bitmap_init_from_file(mctx, path.c_str(), false,
                                                                     mtmd_helper_init_opt_default());
    mtmd::bitmap bmp(w.bitmap);
    if (bmp.ptr == nullptr || !mtmd_bitmap_is_audio(bmp.ptr.get()) || bmp.n_bytes() % sizeof(float) != 0) {
        return false;
    }
    pcm.resize(bmp.n_bytes() / sizeof(float));
    std::memcpy(pcm.data(), bmp.data(), bmp.n_bytes());
    return true;
}

}  // namespace

int main(int argc, char ** argv) {
    Args a;
    if (!parse_args(argc, argv, a) || a.threads < 1 || a.max_new_tokens < 1) {
        usage();
        return 2;
    }
    g_verbose = a.verbose;
    llama_log_set(log_cb, nullptr);
    mtmd_helper_log_set(log_cb, nullptr);

    std::ifstream clips_in(a.clips);
    if (!clips_in) {
        std::fprintf(stderr, "cannot read %s\n", a.clips.c_str());
        return 1;
    }
    std::vector<json> clips;
    for (std::string line; std::getline(clips_in, line);) {
        if (line.find_first_not_of(" \t\r") != std::string::npos) {
            json c = json::parse(line, nullptr, false);
            if (c.is_discarded() || !c.contains("id") || !c.contains("wav")) {
                std::fprintf(stderr, "bad clip row: %s\n", line.c_str());
                return 1;
            }
            clips.push_back(std::move(c));
        }
    }

    RajParams p;
    p.n_threads = a.threads;
    p.forced_language = a.forced;
    std::string err;
    std::unique_ptr<RajEngine> engine = RajEngine::load(a.model, a.mmproj, p, &err);
    if (!engine) {
        std::fprintf(stderr, "load failed: %s\n", err.c_str());
        return 1;
    }

    std::ofstream out(a.out, std::ios::binary);
    if (!out) {
        std::fprintf(stderr, "cannot write %s\n", a.out.c_str());
        return 1;
    }
    for (const json & c : clips) {
        const std::string id = c.at("id").get<std::string>();
        const std::string wav = c.at("wav").get<std::string>();
        std::vector<float> pcm;
        if (!load_pcm(engine->mtmd(), wav, pcm)) {
            std::fprintf(stderr, "%s: cannot decode audio %s\n", id.c_str(), wav.c_str());
            return 1;
        }
        const RajResult r = engine->transcribe(pcm.data(), pcm.size(), a.max_new_tokens);
        if (r.status != RAJ_STATUS_OK) {
            std::fprintf(stderr, "%s: transcribe failed (status %d): %s\n", id.c_str(), r.status, r.raw.c_str());
            return 1;
        }
        std::string language = a.forced;
        if (a.forced.empty()) {
            raj_strip_asr_prefix(r.raw, &language);
        }
        json row;
        row["id"] = id;
        row["kind"] = c.value("kind", "");
        row["raw"] = r.raw;
        row["text"] = r.text;
        row["language"] = language;
        row["audio_ms"] = (int64_t) (pcm.size() * 1000 / kSampleRate);
        row["prompt_ms"] = (double) r.encode_ms;
        row["predicted_ms"] = (double) r.decode_ms;
        row["predicted_n"] = r.n_gen_tokens;
        row["n_audio_tokens"] = r.n_audio_tokens;
        row["model_id"] = a.model_id;
        row["llama_cpp_sha"] = RAJ_LLAMA_CPP_SHA;
        out << row.dump(-1, ' ', false, json::error_handler_t::replace) << '\n';
        out.flush();
        std::fprintf(stderr, "%s: encode %lld ms, decode %lld ms, audio %d tok, gen %d tok: %s\n", id.c_str(),
                     (long long) r.encode_ms, (long long) r.decode_ms, r.n_audio_tokens, r.n_gen_tokens,
                     r.text.c_str());
    }
    return 0;
}
