package app.budgetwise.asr.download

import app.budgetwise.asr.ModelFile
import app.budgetwise.asr.RajVariant
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class ModelSource(val baseUrl: HttpUrl = "https://huggingface.co/".toHttpUrl()) {

    /** `{base}{repo}/resolve/{revision}/{path}`, always the pinned commit, never `main`. */
    fun urlFor(v: RajVariant, f: ModelFile): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegments(v.repo)
            .addPathSegment("resolve")
            .addPathSegment(v.revision)
            .addPathSegments(f.path)
            .build()
}
