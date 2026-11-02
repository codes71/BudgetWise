package app.budgetwise.data

import app.budgetwise.data.text.BurmeseTextNormalizer
import app.budgetwise.data.text.ZawgyiConverter
import com.ibm.icu.text.Transliterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurmeseTextNormalizerTest {
    /** android.icu is absent on the plain JVM, so ICU4J runs the same "Zawgyi-my" transform. */
    private val icu = object : ZawgyiConverter {
        private val t = Transliterator.getInstance("Zawgyi-my")
        override fun convert(text: String): String = t.transliterate(text)
    }
    private val unavailable = object : ZawgyiConverter {
        override fun convert(text: String): String? = null
    }

    // "Myanmar language" and "I bought rice at the market today" in Unicode, with the Zawgyi encoding of each.
    private val uniWord = "မြန်မာဘာသာ"
    private val zawgyiWord = "ျမန္မာဘာသာ"
    private val uniSentence = "ဒီနေ့ ဈေးမှာ ဆန်ဝယ်ခဲ့တယ်"
    private val zawgyiSentence = "ဒီေန႔ ေဈးမွာ ဆန္ဝယ္ခဲ့တယ္"

    @Test
    fun zawgyiIsConvertedOrFlagged() {
        assertTrue(BurmeseTextNormalizer.looksLikeZawgyi(zawgyiWord))
        assertEquals(uniWord, BurmeseTextNormalizer.toUnicode(zawgyiWord, icu))
        assertTrue(BurmeseTextNormalizer.looksLikeZawgyi(zawgyiSentence))
        assertEquals(uniSentence, BurmeseTextNormalizer.toUnicode(zawgyiSentence, icu))
        // No converter on this device (API below 29): text comes back unchanged so the caller can flag it.
        assertEquals(zawgyiWord, BurmeseTextNormalizer.toUnicode(zawgyiWord, unavailable))
    }

    @Test
    fun unicodeInputUnchanged() {
        assertFalse(BurmeseTextNormalizer.looksLikeZawgyi(uniWord))
        assertFalse(BurmeseTextNormalizer.looksLikeZawgyi(uniSentence))
        assertEquals(uniWord, BurmeseTextNormalizer.toUnicode(uniWord, icu))
        assertEquals(uniSentence, BurmeseTextNormalizer.toUnicode(uniSentence, icu))
    }

    @Test
    fun latinAndEmptyTextAreNeverZawgyi() {
        assertFalse(BurmeseTextNormalizer.looksLikeZawgyi(""))
        assertFalse(BurmeseTextNormalizer.looksLikeZawgyi("Grab 5000"))
        assertEquals("Grab 5000", BurmeseTextNormalizer.toUnicode("Grab 5000", icu))
    }

    @Test
    fun normalizeTrimsAndCollapsesWhitespace() {
        assertEquals("rice at market", BurmeseTextNormalizer.normalize("  rice \t at\n\n market  "))
        assertEquals("ဆန် ဝယ်", BurmeseTextNormalizer.normalize(" ဆန်   ဝယ်　"))
        assertEquals("", BurmeseTextNormalizer.normalize("   "))
    }

    @Test
    fun normalizeAppliesNfc() {
        // e + combining acute becomes the single precomposed code point.
        assertEquals("café", BurmeseTextNormalizer.normalize("café"))
    }

    @Test
    fun englishCodeSwitchPreserved() {
        val mixed = "Grab ခ ၅၀၀၀"
        assertEquals(mixed, BurmeseTextNormalizer.normalize(mixed))
        assertEquals(mixed, BurmeseTextNormalizer.prepare(mixed, icu))
    }

    @Test
    fun prepareConvertsZawgyiThenNormalizes() {
        assertEquals(uniWord, BurmeseTextNormalizer.prepare("  $zawgyiWord  ", icu))
        assertEquals("Grab", BurmeseTextNormalizer.prepare(" Grab ", icu))
    }
}
