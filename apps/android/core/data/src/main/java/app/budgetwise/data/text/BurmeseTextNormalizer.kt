package app.budgetwise.data.text

import android.os.Build
import com.google.myanmartools.TransliterateZ2U
import com.google.myanmartools.ZawgyiDetector
import java.text.Normalizer

/** Platform seam for Zawgyi to Unicode conversion; returns null when this device cannot convert. */
fun interface ZawgyiConverter {
    fun convert(text: String): String?
}

/**
 * `android.icu` "Zawgyi-my" transliterator from API 29, and myanmar-tools' own converter ([LibraryZawgyiConverter])
 * below that or where the platform lacks the transform, so every supported phone (API 26 and up) converts.
 */
object PlatformZawgyiConverter : ZawgyiConverter {
    override fun convert(text: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return LibraryZawgyiConverter.convert(text)
        return try {
            android.icu.text.Transliterator.getInstance("Zawgyi-my").transliterate(text)
        } catch (_: IllegalArgumentException) {
            LibraryZawgyiConverter.convert(text)
        }
    }
}

/** myanmar-tools' Zawgyi to Unicode rules, plain Java, so it runs on every API level (and on the JVM in tests). */
object LibraryZawgyiConverter : ZawgyiConverter {
    // The library does not say it is thread-safe, and typing, the scan and the sync can convert at once.
    private val z2u by lazy { TransliterateZ2U("Zawgyi to Unicode") }

    override fun convert(text: String): String = synchronized(this) { z2u.convert(text) }
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
     * Converts detected Zawgyi to Unicode; anything else comes back unchanged. A [converter] that cannot convert (null)
     * leaves Zawgyi text unchanged too, so the caller can still compare with [looksLikeZawgyi].
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
