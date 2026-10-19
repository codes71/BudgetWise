#include <jni.h>

#include <dlfcn.h>

#include <string>

#include "ggml-backend.h"

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

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_budgetwise_asr_engine_RajNative_initBackends(JNIEnv *env, jobject /* this */, jstring native_lib_dir) {
    const char *dir = env->GetStringUTFChars(native_lib_dir, nullptr);
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(native_lib_dir, dir);

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

    return env->NewStringUTF(out.c_str());
}
