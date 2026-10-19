#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

struct llama_model;
struct llama_context;
struct llama_sampler;
struct llama_vocab;
struct mtmd_context;

// RajResult.status values.
constexpr int RAJ_STATUS_OK = 0;
constexpr int RAJ_STATUS_ABORTED = 1;  // text empty, raw holds the output generated so far
constexpr int RAJ_STATUS_ERROR = 2;    // text empty, raw holds the error message

struct RajParams {
    int n_threads = 4;
    int n_ctx = 1024;
    std::string forced_language;  // empty = auto mode (production)
};

struct RajResult {
    int status = RAJ_STATUS_ERROR;
    std::string text, raw;  // text = raj_postprocess(raw)
    int n_audio_tokens = 0;  // tokens in the audio chunk(s)
    int n_gen_tokens = 0;    // generated tokens, end-of-generation token excluded
    int64_t encode_ms = 0;   // mtmd tokenize + eval of all prompt chunks
    int64_t decode_ms = 0;   // generation loop
};

// Raj (Qwen3-ASR) through llama.cpp + libmtmd on the CPU. Backends must already be
// registered (built in, or ggml_backend_load_all_from_path on Android).
// transcribe is not reentrant; request_abort may be called from any thread.
class RajEngine {
 public:
    // nullptr on failure, with *err (may be null) set to the reason.
    static std::unique_ptr<RajEngine> load(const std::string & model, const std::string & mmproj,
                                           const RajParams & p, std::string * err);

    // pcm16k: mono float samples at 16 kHz. Clears the KV cache, then decodes greedily until
    // end of generation, max_new_tokens or a full context.
    RajResult transcribe(const float * pcm16k, size_t n_samples, int max_new_tokens);

    // Stops the transcribe in flight: checked per generated token and inside llama_decode.
    // The mtmd audio encoder does not check it, so an abort during encoding lands when the
    // encoded chunk is decoded.
    void request_abort();

    // For host tools that decode audio files with the libmtmd helpers.
    const mtmd_context * mtmd() const { return mctx_; }

    ~RajEngine();

 private:
    RajEngine() = default;
    RajEngine(const RajEngine &) = delete;
    RajEngine & operator=(const RajEngine &) = delete;

    static bool abort_cb(void * data);

    RajParams params_;
    llama_model * model_ = nullptr;
    llama_context * lctx_ = nullptr;
    mtmd_context * mctx_ = nullptr;
    llama_sampler * smpl_ = nullptr;
    const llama_vocab * vocab_ = nullptr;
    std::atomic<bool> abort_{false};
};
