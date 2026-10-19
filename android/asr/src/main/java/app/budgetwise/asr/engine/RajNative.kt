package app.budgetwise.asr.engine

internal object RajNative {
    init {
        System.loadLibrary("raj_jni")
    }

    /**
     * Loads every ggml backend library found in [nativeLibDir] and returns a
     * description of the registered backends, one per line.
     */
    external fun initBackends(nativeLibDir: String): String
}
