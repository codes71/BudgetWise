package app.budgetwise.asr

/** Where a [ModelFile] is downloaded from. Every origin is pinned, so a file's URL never changes. */
sealed interface ModelOrigin {
    /** `https://huggingface.co/{repo}/resolve/{revision}/{path}`, always a pinned commit, never `main`. */
    data class HuggingFace(val repo: String, val revision: String) : ModelOrigin

    /**
     * `{BuildConfig.FREOCOCO_MODEL_BASE_URL}{path}`: freococo's ONNX export. By default its Hugging Face repo at a
     * pinned commit; a Gradle property can point it elsewhere.
     */
    data object FreococoHost : ModelOrigin
}

/** One downloadable file, pinned by size and sha256. [path] is relative to its [origin]. */
data class ModelFile(val origin: ModelOrigin, val path: String, val bytes: Long, val sha256: String) {
    val name: String get() = path.substringAfterLast('/')
}

/** The speech models that transcribe every utterance, in the order they run. Both transcripts go to the caller. */
enum class VoiceModel(val displayName: String, val id: String) {
    /** freococo/myanmar_asr exported to ONNX: 16 kHz audio in, Burmese syllable ids out. */
    FREOCOCO("freococo", "freococo-myanmar-asr@e40d48e"),

    /** Meta Omnilingual ASR CTC 1B, the int8 ONNX export published by sherpa-onnx. */
    OMNILINGUAL("Omnilingual", "omnilingual-ctc-1b-int8@17db76e"),
}

/**
 * Every file the [VoiceModel]s need, downloaded together as one pack (about 1.1 GB). A new [VERSION] gets a new
 * directory, and the files of older packs are deleted before it downloads.
 */
object VoicePack {
    const val VERSION = "v1"
    const val DISPLAY_NAME = "voice models"

    private val omnilingual = ModelOrigin.HuggingFace(
        repo = "csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-1B-ctc-int8-2025-11-12",
        revision = "17db76eab583b0b868ffb0df104ab879145087e5",
    )

    /** Made by `asr/host/export/freococo_onnx.py` from freococo/myanmar_asr at e40d48e. */
    val FREOCOCO_MODEL = ModelFile(
        ModelOrigin.FreococoHost,
        "freococo.onnx",
        78_115_082L,
        "07f445eb6fd7b558e3d59accd8bdbd579f4af13ef15cd2efb8d271eca0209a3c",
    )
    val FREOCOCO_VOCAB = ModelFile(
        ModelOrigin.FreococoHost,
        "vocab.json",
        60_989L,
        "5154e188dec1e665f0d6411f003ba91bbdf62757f45c5188f39d018e058a4e8e",
    )
    val OMNILINGUAL_MODEL = ModelFile(
        omnilingual,
        "model.int8.onnx",
        1_031_628_252L,
        "f7b74c964039162423b83e3fa950ce24810c9a635d9ff8468b5f4d142b7c1e8c",
    )
    val OMNILINGUAL_TOKENS = ModelFile(
        omnilingual,
        "tokens.txt",
        86_423L,
        "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31",
    )

    /** freococo's files come first: their host is the one that can be unset, so that fails before the 1 GB file. */
    val files: List<ModelFile> = listOf(FREOCOCO_MODEL, FREOCOCO_VOCAB, OMNILINGUAL_MODEL, OMNILINGUAL_TOKENS)

    val downloadBytes: Long = files.sumOf { it.bytes }
}
