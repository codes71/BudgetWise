package app.budgetwise.asr.download

import app.budgetwise.asr.BuildConfig
import app.budgetwise.asr.ModelFile
import app.budgetwise.asr.ModelOrigin
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ModelSource(
    val huggingFace: HttpUrl = "https://huggingface.co/".toHttpUrl(),
    /** Directory URL ending in `/`; a `.invalid` host means the build was never given one. */
    val freococoBase: String = BuildConfig.FREOCOCO_MODEL_BASE_URL,
) {

    /** Throws [DownloadException.NotConfigured] for a freococo file when [freococoBase] is not a usable URL. */
    fun urlFor(f: ModelFile): HttpUrl = when (val origin = f.origin) {
        // `{base}{repo}/resolve/{revision}/{path}`, always the pinned commit, never `main`.
        is ModelOrigin.HuggingFace -> huggingFace.newBuilder()
            .addPathSegments(origin.repo)
            .addPathSegment("resolve")
            .addPathSegment(origin.revision)
            .addPathSegments(f.path)
            .build()
        ModelOrigin.FreococoHost -> {
            val base = freococoBase.toHttpUrlOrNull()?.takeUnless { it.host.endsWith(".invalid") }
                ?: throw DownloadException.NotConfigured(f.path)
            base.newBuilder().addPathSegments(f.path).build()
        }
    }
}
