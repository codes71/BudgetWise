package app.budgetwise.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsrLicensesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun includesRajNoticeVerbatim() {
        val entries = AsrLicenses.all(context)
        for (component in listOf("Raj-0.6B", "Raj-1.7B")) {
            val text = entries.filter { it.component == component }.joinToString("\n") { it.text }
            assertTrue("$component missing Raj notice", text.contains("Aung Zay Thant"))
            assertTrue("$component missing base model", text.contains("Qwen/Qwen3-ASR"))
        }
    }

    @Test
    fun includesLlamaCppMit() {
        val llama = AsrLicenses.all(context).single { it.component == "llama.cpp" }
        assertEquals("MIT", llama.license)
        assertTrue(llama.text.contains("MIT License"))
    }

    @Test
    fun includesWebRtcVadBsd3() {
        val vad = AsrLicenses.all(context).single { it.component == "WebRTC VAD" }
        assertEquals("BSD-3-Clause", vad.license)
        assertTrue(vad.text.contains("WebRTC"))
        assertTrue(vad.text.contains("Redistribution and use in source and binary forms"))
    }

    @Test
    fun includesKleidiAiApache2() {
        val kai = AsrLicenses.all(context).single { it.component == "KleidiAI" }
        assertEquals("Apache-2.0", kai.license)
        assertTrue(kai.text.contains("Arm Limited"))
        assertTrue(kai.text.contains("Apache License"))
        assertTrue(kai.text.contains("Version 2.0, January 2004"))
    }

    @Test
    fun everyEntryNonEmpty() {
        val entries = AsrLicenses.all(context)
        assertTrue(entries.isNotEmpty())
        for (e in entries) {
            assertTrue("blank component", e.component.isNotBlank())
            assertTrue("blank license for ${e.component}", e.license.isNotBlank())
            assertTrue("blank text for ${e.component}", e.text.isNotBlank())
        }
    }
}
