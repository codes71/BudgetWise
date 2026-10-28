package app.budgetwise.asr

import android.content.Context

data class LicenseEntry(val component: String, val license: String, val text: String)

/** Model and runtime license notices bundled as assets, for the Open-source licenses screen. */
object AsrLicenses {
    private const val ROOT = "licenses"
    private const val SEPARATOR = "\n\n----------------------------------------\n\n"

    private class Source(val component: String, val license: String, val dir: String, val files: List<String>)

    private val SOURCES = listOf(
        Source("Raj-0.6B", "Apache-2.0", "raj-0.6b", listOf("NOTICE", "LICENSE", "UPSTREAM-LICENSE.txt")),
        Source("Raj-1.7B", "Apache-2.0", "raj-1.7b", listOf("NOTICE", "LICENSE", "UPSTREAM-LICENSE.txt")),
        Source("llama.cpp", "MIT", "llama.cpp", listOf("LICENSE")),
        // KleidiAI micro-kernels are compiled into every ggml CPU variant (GGML_CPU_KLEIDIAI=ON).
        Source("KleidiAI", "Apache-2.0", "kleidiai", listOf("NOTICE", "LICENSE")),
        Source("android-vad", "MIT", "android-vad", listOf("LICENSE")),
        // WebRTC VAD C sources are compiled into android-vad's native library; its license is separate.
        Source("WebRTC VAD", "BSD-3-Clause", "webrtc-vad", listOf("LICENSE")),
    )

    fun all(context: Context): List<LicenseEntry> = SOURCES.map { source ->
        val text = source.files.joinToString(SEPARATOR) { file ->
            context.assets.open("$ROOT/${source.dir}/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        LicenseEntry(source.component, source.license, text)
    }
}
