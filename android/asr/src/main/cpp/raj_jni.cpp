#include <jni.h>

#include <dlfcn.h>

#include <exception>
#include <memory>
#include <new>
#include <mutex>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "raj_engine.h"
#include "raj_text.h"

namespace {

// ggml registers every CPU variant under the name "CPU", so the winning variant is
// only visible through the shared object that provides the backend's entry points.
std::string library_of(ggml_backend_reg_t reg) {
    void *fn = ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features");
    if (fn == nullptr) {
        fn = ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads");
    }
    Dl_info info;
    if (fn != nullptr && dladdr(fn, &info) != 0 && info.dli_fname != nullptr) {
        std::string path = info.dli_fname;
        const size_t slash = path.find_last_of('/');
        return slash == std::string::npos ? path : path.substr(slash + 1);
    }
    return "built-in";
}

// What a jlong handle points to. The engine is shared so that free can drop it while a transcribe
// that already copied the pointer finishes on its own reference. The Kotlin side guarantees the
// wrapper itself is not deleted while any other native call on it is running.
struct Handle {
    std::mutex mu;
    std::shared_ptr<RajEngine> engine;
    std::string last_error;
};

// load returns 0 and has no handle to hang its error on.
std::mutex g_load_error_mu;
std::string g_load_error;

void set_load_error(const std::string &msg) {
    std::lock_guard<std::mutex> lock(g_load_error_mu);
    g_load_error = msg;
}

// Model output and error text are not guaranteed valid UTF-8 or modified UTF-8, so strings are
// always built from UTF-16 with NewString, never NewStringUTF.
jstring to_jstring(JNIEnv *env, const std::string &utf8) {
    const std::u16string u16 = raj_utf8_to_utf16(utf8);
    return env->NewString(reinterpret_cast<const jchar *>(u16.data()), static_cast<jsize>(u16.size()));
}

// Owns the result of GetStringUTFChars so every path releases it.
class Utf8Chars {
public:
    Utf8Chars(JNIEnv *env, jstring str) : env_(env), str_(str) {
        if (str != nullptr) {
            chars_ = env->GetStringUTFChars(str, nullptr);
        }
    }
    ~Utf8Chars() {
        if (chars_ != nullptr) {
            env_->ReleaseStringUTFChars(str_, chars_);
        }
    }
    Utf8Chars(const Utf8Chars &) = delete;
    Utf8Chars &operator=(const Utf8Chars &) = delete;
    const char *get() const { return chars_; }

private:
    JNIEnv *env_;
    jstring str_;
    const char *chars_ = nullptr;
};

void throw_new(JNIEnv *env, const char *cls, const char *msg) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass(cls);
    if (c != nullptr) {
        env->ThrowNew(c, msg);
        env->DeleteLocalRef(c);
    }
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_budgetwise_asr_engine_RajNative_initBackends(JNIEnv *env, jobject /* this */, jstring native_lib_dir) {
    if (native_lib_dir == nullptr) {
        throw_new(env, "java/lang/NullPointerException", "nativeLibDir");
        return nullptr;
    }
    Utf8Chars dir(env, native_lib_dir);
    if (dir.get() == nullptr) {
        return nullptr;  // OutOfMemoryError is pending
    }
    ggml_backend_load_all_from_path(dir.get());

    std::string out;
    const size_t regs = ggml_backend_reg_count();
    for (size_t i = 0; i < regs; ++i) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        out += "backend: ";
        out += ggml_backend_reg_name(reg);
        out += " (";
        out += library_of(reg);
        out += ")\n";
    }

    const size_t devs = ggml_backend_dev_count();
    for (size_t i = 0; i < devs; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        out += "device: ";
        out += ggml_backend_dev_name(dev);
        out += " | ";
        out += ggml_backend_dev_description(dev);
        out += '\n';
    }

    return to_jstring(env, out);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_budgetwise_asr_engine_RajNative_load(JNIEnv *env, jobject /* this */, jstring model, jstring mmproj,
                                              jint threads, jint n_ctx, jstring forced) {
    if (model == nullptr || mmproj == nullptr) {
        set_load_error("model or mmproj path is null");
        return 0;
    }
    Utf8Chars model_path(env, model);
    if (model_path.get() == nullptr) return 0;  // OutOfMemoryError is pending
    Utf8Chars mmproj_path(env, mmproj);
    if (mmproj_path.get() == nullptr) return 0;
    std::string forced_language;
    if (forced != nullptr) {
        Utf8Chars chars(env, forced);
        if (chars.get() == nullptr) return 0;
        forced_language = chars.get();
    }

    RajParams params;
    params.n_threads = threads;
    params.n_ctx = n_ctx;
    params.forced_language = forced_language;

    std::string err;
    std::unique_ptr<RajEngine> engine;
    try {
        engine = RajEngine::load(model_path.get(), mmproj_path.get(), params, &err);
    } catch (const std::exception &e) {
        err = std::string("load threw: ") + e.what();
    } catch (...) {
        err = "load threw an unknown exception";
    }
    if (!engine) {
        set_load_error(err.empty() ? "load failed" : err);
        return 0;
    }
    set_load_error("");
    auto *handle = new (std::nothrow) Handle();
    if (handle == nullptr) {
        set_load_error("out of memory");
        return 0;
    }
    handle->engine = std::move(engine);
    return reinterpret_cast<jlong>(handle);
}

// Returns null on ERROR (message in lastError). ABORTED throws CancellationException("aborted").
extern "C" JNIEXPORT jobject JNICALL
Java_app_budgetwise_asr_engine_RajNative_transcribe(JNIEnv *env, jobject /* this */, jlong handle_ptr,
                                                    jfloatArray pcm, jint max_new_tokens) {
    auto *handle = reinterpret_cast<Handle *>(handle_ptr);
    if (handle == nullptr || pcm == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples;
    try {
        samples.resize(static_cast<size_t>(n));
    } catch (const std::bad_alloc &) {
        throw_new(env, "java/lang/OutOfMemoryError", "pcm copy");
        return nullptr;
    }
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());
    if (env->ExceptionCheck()) return nullptr;

    std::shared_ptr<RajEngine> engine;
    {
        std::lock_guard<std::mutex> lock(handle->mu);
        engine = handle->engine;
    }

    RajResult result;
    std::string failure;
    if (!engine) {
        failure = "engine is closed";
    } else {
        try {
            result = engine->transcribe(samples.data(), samples.size(), max_new_tokens);
        } catch (const std::exception &e) {
            failure = std::string("transcribe threw: ") + e.what();
        } catch (...) {
            failure = "transcribe threw an unknown exception";
        }
    }
    if (failure.empty() && result.status == RAJ_STATUS_ABORTED) {
        throw_new(env, "java/util/concurrent/CancellationException", "aborted");
        return nullptr;
    }
    if (failure.empty() && result.status != RAJ_STATUS_OK) {
        failure = result.raw.empty() ? "transcribe failed" : result.raw;
    }
    if (!failure.empty()) {
        std::lock_guard<std::mutex> lock(handle->mu);
        handle->last_error = failure;
        return nullptr;
    }

    jclass cls = env->FindClass("app/budgetwise/asr/engine/EngineOutput");
    if (cls == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;IJJ)V");
    jstring text = ctor != nullptr ? to_jstring(env, result.text) : nullptr;
    jstring raw = text != nullptr ? to_jstring(env, result.raw) : nullptr;
    jobject out = nullptr;
    if (raw != nullptr) {
        out = env->NewObject(cls, ctor, text, raw, static_cast<jint>(result.n_gen_tokens),
                             static_cast<jlong>(result.encode_ms), static_cast<jlong>(result.decode_ms));
    }
    env->DeleteLocalRef(cls);
    if (text != nullptr) env->DeleteLocalRef(text);
    if (raw != nullptr) env->DeleteLocalRef(raw);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_app_budgetwise_asr_engine_RajNative_abort(JNIEnv * /* env */, jobject /* this */, jlong handle_ptr) {
    auto *handle = reinterpret_cast<Handle *>(handle_ptr);
    if (handle == nullptr) return;
    std::shared_ptr<RajEngine> engine;
    {
        std::lock_guard<std::mutex> lock(handle->mu);
        engine = handle->engine;
    }
    if (engine) engine->request_abort();
}

// The caller guarantees this runs at most once per handle and that no other call on the handle is
// in flight. A transcribe that copied the engine earlier is not affected, it owns its reference.
extern "C" JNIEXPORT void JNICALL
Java_app_budgetwise_asr_engine_RajNative_free(JNIEnv * /* env */, jobject /* this */, jlong handle_ptr) {
    auto *handle = reinterpret_cast<Handle *>(handle_ptr);
    if (handle == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(handle->mu);
        handle->engine.reset();
    }
    delete handle;
}

// Handle 0 returns the error of the last failed load.
extern "C" JNIEXPORT jstring JNICALL
Java_app_budgetwise_asr_engine_RajNative_lastError(JNIEnv *env, jobject /* this */, jlong handle_ptr) {
    std::string err;
    auto *handle = reinterpret_cast<Handle *>(handle_ptr);
    if (handle == nullptr) {
        std::lock_guard<std::mutex> lock(g_load_error_mu);
        err = g_load_error;
    } else {
        std::lock_guard<std::mutex> lock(handle->mu);
        err = handle->last_error;
    }
    return to_jstring(env, err);
}
