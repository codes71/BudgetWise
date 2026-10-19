// RajEngine checks that need the real Raj files: clean load failures, repeat calls, abort.
// Paths come from RAJ_MODEL, RAJ_MMPROJ and RAJ_WAV (defaults under ~/.cache/budgetwise-eval).
// Exits 77 (ctest skip) when a file is missing.

#include "raj_engine.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            std::exit(1);                                                        \
        }                                                                        \
    } while (0)

namespace fs = std::filesystem;
using Clock = std::chrono::steady_clock;

static std::string g_model, g_mmproj, g_wav;
static fs::path g_tmp;

static std::string env_or(const char * name, const std::string & fallback) {
    const char * v = std::getenv(name);
    return v != nullptr && *v != '\0' ? v : fallback;
}

static int64_t ms_between(Clock::time_point a, Clock::time_point b) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count();
}

// Copy of src with only the first n bytes (n < 0: all but the last -n bytes).
static std::string truncated_copy(const std::string & src, long long n, const char * name) {
    const long long size = (long long) fs::file_size(src);
    const long long keep = n < 0 ? size + n : n;
    const fs::path dst = g_tmp / name;
    std::ifstream in(src, std::ios::binary);
    std::ofstream out(dst, std::ios::binary | std::ios::trunc);
    std::vector<char> buf(1 << 20);
    for (long long left = keep; left > 0;) {
        const long long chunk = left < (long long) buf.size() ? left : (long long) buf.size();
        in.read(buf.data(), chunk);
        out.write(buf.data(), chunk);
        left -= chunk;
    }
    return dst.string();
}

static void expect_load_fails(const std::string & model, const std::string & mmproj, const char * what) {
    std::string err;
    std::unique_ptr<RajEngine> e = RajEngine::load(model, mmproj, RajParams(), &err);
    std::printf("load %s: %s, err=[%s]\n", what, e ? "LOADED" : "nullptr", err.c_str());
    CHECK(e == nullptr);
    CHECK(!err.empty());
}

static void load_fails_cleanly() {
    expect_load_fails(g_model + ".missing", g_mmproj, "missing model");
    expect_load_fails(g_model, g_mmproj + ".missing", "missing mmproj");
    expect_load_fails(truncated_copy(g_model, 10 << 20, "model-10mb.gguf"), g_mmproj, "model first 10 MB");
    expect_load_fails(g_model, truncated_copy(g_mmproj, 10 << 20, "mmproj-10mb.gguf"), "mmproj first 10 MB");
    expect_load_fails(truncated_copy(g_model, -1024, "model-cut.gguf"), g_mmproj, "model minus last 1 KB");
    expect_load_fails(g_model, truncated_copy(g_mmproj, -1024, "mmproj-cut.gguf"), "mmproj minus last 1 KB");
    expect_load_fails(truncated_copy(g_model, -1, "model-cut1.gguf"), g_mmproj, "model minus last byte");
    expect_load_fails(g_model, truncated_copy(g_mmproj, -1, "mmproj-cut1.gguf"), "mmproj minus last byte");
    std::unique_ptr<RajEngine> e = RajEngine::load(g_model, g_mmproj, RajParams(), nullptr);
    CHECK(e != nullptr);  // null err pointer is allowed
}

static std::vector<float> load_pcm(const RajEngine & e, const std::string & path) {
    mtmd_helper_bitmap_wrapper w = mtmd_helper_bitmap_init_from_file(e.mtmd(), path.c_str(), false,
                                                                     mtmd_helper_init_opt_default());
    mtmd::bitmap bmp(w.bitmap);
    CHECK(bmp.ptr != nullptr);
    std::vector<float> pcm(bmp.n_bytes() / sizeof(float));
    std::memcpy(pcm.data(), bmp.data(), pcm.size() * sizeof(float));
    return pcm;
}

// Starts transcribe on a thread, aborts after delay_ms, returns ms from abort to return.
static int64_t abort_after(RajEngine & e, const std::vector<float> & pcm, int delay_ms, RajResult & out) {
    Clock::time_point t_abort;
    std::thread worker([&] { out = e.transcribe(pcm.data(), pcm.size(), 512); });
    std::this_thread::sleep_for(std::chrono::milliseconds(delay_ms));
    t_abort = Clock::now();
    e.request_abort();
    worker.join();
    return ms_between(t_abort, Clock::now());
}

static void transcribe_repeat_and_abort() {
    std::string err;
    std::unique_ptr<RajEngine> e = RajEngine::load(g_model, g_mmproj, RajParams(), &err);
    CHECK(e != nullptr);
    const std::vector<float> pcm = load_pcm(*e, g_wav);

    const RajResult a = e->transcribe(pcm.data(), pcm.size(), 512);
    const RajResult b = e->transcribe(pcm.data(), pcm.size(), 512);
    std::printf("first: status %d, encode %lld ms, decode %lld ms, gen %d: %s\n", a.status,
                (long long) a.encode_ms, (long long) a.decode_ms, a.n_gen_tokens, a.text.c_str());
    CHECK(a.status == RAJ_STATUS_OK);
    CHECK(!a.text.empty());
    CHECK(b.status == RAJ_STATUS_OK);
    CHECK(b.raw == a.raw);

    // Abort while the audio encoder runs: lands when the encoded chunk reaches llama_decode.
    RajResult enc;
    const int64_t enc_ms = abort_after(*e, pcm, 50, enc);
    std::printf("abort during encode: status %d, returned %lld ms after abort (encode_ms %lld)\n", enc.status,
                (long long) enc_ms, (long long) enc.encode_ms);
    CHECK(enc.status == RAJ_STATUS_ABORTED);
    CHECK(enc.text.empty());

    // Abort mid-generation: checked per token.
    RajResult dec;
    const int64_t dec_ms = abort_after(*e, pcm, (int) a.encode_ms + (int) a.decode_ms / 2, dec);
    std::printf("abort during decode: status %d, returned %lld ms after abort, partial [%s]\n", dec.status,
                (long long) dec_ms, dec.raw.c_str());
    CHECK(dec.status == RAJ_STATUS_ABORTED);
    CHECK(dec.text.empty());
    CHECK(dec_ms < 100);

    const RajResult c = e->transcribe(pcm.data(), pcm.size(), 512);
    CHECK(c.status == RAJ_STATUS_OK);
    CHECK(c.raw == a.raw);

    const RajResult limited = e->transcribe(pcm.data(), pcm.size(), 5);
    CHECK(limited.status == RAJ_STATUS_OK);
    CHECK(limited.n_gen_tokens == 5);

    const RajResult empty = e->transcribe(pcm.data(), 0, 512);
    CHECK(empty.status == RAJ_STATUS_ERROR);
    CHECK(empty.text.empty());
    CHECK(!empty.raw.empty());
}

// Long audio against a 256-token context: generation stops at a full context, longer audio is refused.
static void context_limits() {
    RajParams p;
    p.n_ctx = 256;
    std::unique_ptr<RajEngine> e = RajEngine::load(g_model, g_mmproj, p, nullptr);
    CHECK(e != nullptr);
    const std::vector<float> pcm = load_pcm(*e, g_wav);
    std::vector<float> twice(pcm);
    twice.insert(twice.end(), pcm.begin(), pcm.end());
    const RajResult full = e->transcribe(twice.data(), twice.size(), 512);
    std::printf("n_ctx 256, %d audio tokens: status %d, gen %d\n", full.n_audio_tokens, full.status, full.n_gen_tokens);
    CHECK(full.status == RAJ_STATUS_OK);
    CHECK(full.n_gen_tokens > 0);
    CHECK(full.n_audio_tokens + full.n_gen_tokens < 256);

    std::vector<float> thrice(twice);
    thrice.insert(thrice.end(), pcm.begin(), pcm.end());
    const RajResult too_long = e->transcribe(thrice.data(), thrice.size(), 512);
    std::printf("n_ctx 256, 3x audio: status %d, raw [%s]\n", too_long.status, too_long.raw.c_str());
    CHECK(too_long.status == RAJ_STATUS_ERROR);
    CHECK(too_long.text.empty());
}

static void quiet_log(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        std::fputs(text, stderr);
    }
}

int main() {
    const std::string cache = env_or("HOME", ".") + "/.cache/budgetwise-eval";
    g_model = env_or("RAJ_MODEL", cache + "/raj-0.6b/gguf/Raj-0.6B-Q8_0.gguf");
    g_mmproj = env_or("RAJ_MMPROJ", cache + "/raj-0.6b/gguf/mmproj-Raj-0.6B-Q8_0.gguf");
    g_wav = env_or("RAJ_WAV", cache + "/clips/wav/fleurs_02.wav");
    for (const std::string & p : {g_model, g_mmproj, g_wav}) {
        if (!fs::exists(p)) {
            std::printf("raj_engine: SKIP, missing %s\n", p.c_str());
            return 77;
        }
    }
    g_tmp = fs::temp_directory_path() / "raj_engine_test";
    fs::create_directories(g_tmp);
    llama_log_set(quiet_log, nullptr);
    mtmd_helper_log_set(quiet_log, nullptr);

    load_fails_cleanly();
    transcribe_repeat_and_abort();
    context_limits();

    fs::remove_all(g_tmp);
    std::puts("raj_engine: all tests passed");
    return 0;
}
