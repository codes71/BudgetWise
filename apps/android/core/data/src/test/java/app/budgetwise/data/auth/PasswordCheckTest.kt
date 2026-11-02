package app.budgetwise.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordCheckTest {
    @Test
    fun matchesOnlyTheSamePassword() {
        val check = PasswordCheck.create("tea-shop-2026")
        assertTrue(check.matches("tea-shop-2026"))
        assertFalse(check.matches("tea-shop-2027"))
        assertFalse(check.matches("Tea-shop-2026"))
        assertFalse(check.matches(""))
    }

    @Test
    fun aBurmesePasswordWorksToo() {
        val check = PasswordCheck.create("လက်ဖက်ရည်၂၀၂၆")
        assertTrue(check.matches("လက်ဖက်ရည်၂၀၂၆"))
        assertFalse(check.matches("လက်ဖက်ရည်၂၀၂၇"))
    }

    @Test
    fun saltedPbkdf2WithEnoughIterations() {
        val a = PasswordCheck.create("same")
        val b = PasswordCheck.create("same")
        assertEquals(16, a.salt.size)
        assertEquals(32, a.hash.size)
        assertTrue(a.iterations >= 120_000)
        assertFalse("a fresh salt each time", a.salt.contentEquals(b.salt))
        assertFalse(a.hash.contentEquals(b.hash))
        assertNotEquals(a, b)
    }

    @Test
    fun aTamperedCheckNeverMatches() {
        val check = PasswordCheck.create("pw")
        assertFalse(PasswordCheck(check.salt, check.hash, check.iterations - 1).matches("pw"))
        assertFalse(PasswordCheck(check.salt, ByteArray(0), check.iterations).matches("pw"))
        assertFalse(PasswordCheck(check.salt, check.hash, 0).matches("pw"))
        assertEquals(check, PasswordCheck(check.salt.copyOf(), check.hash.copyOf(), check.iterations))
    }

    @Test
    fun toStringShowsNoSaltOrHash() {
        val text = PasswordCheck(ByteArray(16) { 7 }, ByteArray(32) { 9 }, 5).toString()
        assertFalse(text.contains("[7") || text.contains("[9"))
        assertTrue(text.contains("redacted"))
    }
}
