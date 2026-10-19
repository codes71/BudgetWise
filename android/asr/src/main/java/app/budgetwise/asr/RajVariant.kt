package app.budgetwise.asr

/** One downloadable GGUF file, pinned by size and sha256. [path] is relative to the repo root. */
data class ModelFile(val path: String, val bytes: Long, val sha256: String)

enum class RajVariant(
    val displayName: String,
    val repo: String,
    val revision: String,
    val model: ModelFile,
    val mmproj: ModelFile,
) {
    RAJ_0_6B(
        displayName = "Raj 0.6B",
        repo = "aungzaythant/Raj-0.6B",
        revision = "92b89bafb8b48fe47857665ca834390b7303c7d8",
        model = ModelFile(
            "gguf/Raj-0.6B-Q8_0.gguf",
            639_442_816L,
            "eb8b09623dcd0e6d135c7f119e8504b66edbeeacd5f961c014ac76974fc3b70a",
        ),
        mmproj = ModelFile(
            "gguf/mmproj-Raj-0.6B-Q8_0.gguf",
            207_941_216L,
            "a940a9645e849d1456c0c6e6297e902907794d8422014759f3d28593904fcab9",
        ),
    ),
    RAJ_1_7B(
        displayName = "Raj 1.7B",
        repo = "aungzaythant/Raj-1.7B",
        revision = "7dbed379408543ec4b88b6a28f2435422b83c5fe",
        model = ModelFile(
            "gguf/Raj-1.7B-Q8_0.gguf",
            1_834_422_144L,
            "7888844eb4b5f7a7d6123d07255cf93b6f35671abd2d4da13a557fc125ffd180",
        ),
        mmproj = ModelFile(
            "gguf/mmproj-Raj-1.7B-Q8_0.gguf",
            348_336_480L,
            "1345a70dda8fc0b4d7984961d6c5ad868d7d47cf91d3f7f963b30f218fbf16ba",
        ),
    );

    val downloadBytes: Long get() = model.bytes + mmproj.bytes

    /** Stable id such as "raj-0.6b-q8_0@92b89ba": quant is fixed to the published Q8_0 files. */
    val modelId: String
        get() = "${name.removePrefix("RAJ_").replace('_', '.').lowercase().let { "raj-$it" }}-q8_0@${revision.take(7)}"
}
