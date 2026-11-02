package app.budgetwise.data.text

import android.os.Build
import com.google.myanmartools.ZawgyiDetector
import java.text.Normalizer

/** Platform seam for Zawgyi to Unicode conversion; returns null when this device cannot convert. */
fun interface ZawgyiConverter {
    fun convert(text: String): String?
}

/** `android.icu` "Zawgyi-my" transliterator, available from API 29. Below that nothing is converted. */
object PlatformZawgyiConverter : ZawgyiConverter {
    override fun convert(text: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            android.icu.text.Transliterator.getInstance("Zawgyi-my").transliterate(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Guards typed and pasted Burmese text. Burmese is stored as Unicode only; Zawgyi is detected, converted when the
 * device can, and never produced. Voice transcripts are already Unicode but take the same path.
 */
object BurmeseTextNormalizer {
    /** Probability at or above which myanmar-tools output is treated as Zawgyi (the library's own guidance is 0.95). */
    const val ZAWGYI_THRESHOLD = 0.95

    private val detector = ZawgyiDetector()
    private val whitespaceRun = Regex("[\\s\\p{Z}]+")

    fun looksLikeZawgyi(s: String): Boolean =
        s.isNotBlank() && detector.getZawgyiProbability(s) >= ZAWGYI_THRESHOLD

    /**
     * Converts detected Zawgyi to Unicode; anything else comes back unchanged. When the device has no converter
     * (API below 29) Zawgyi text also comes back unchanged, so the caller can still compare with [looksLikeZawgyi].
     */
    fun toUnicode(s: String, converter: ZawgyiConverter = PlatformZawgyiConverter): String =
        if (looksLikeZawgyi(s)) converter.convert(s) ?: s else s

    /** NFC, trimmed, every whitespace run collapsed to one space. Latin letters and digits are untouched. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).replace(whitespaceRun, " ").trim()

    /** What the repository stores: [toUnicode] first (it needs the raw bytes), then [normalize]. */
    fun prepare(s: String, converter: ZawgyiConverter = PlatformZawgyiConverter): String =
        normalize(toUnicode(s, converter))
}
