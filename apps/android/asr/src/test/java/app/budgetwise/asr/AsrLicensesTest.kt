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
    fun includesBothSpeechModelsAsApache2() {
        val entries = AsrLicenses.all(context)
        val freococo = entries.single { it.component == "freococo/myanmar_asr" }
        assertEquals("Apache-2.0", freococo.license)
        assertTrue(freococo.text.contains("huggingface.co/freococo/myanmar_asr"))
        assertTrue(freococo.text.contains("Modification notice"))
        val omni = entries.single { it.component == "Omnilingual ASR CTC 1B" }
        assertEquals("Apache-2.0", omni.license)
        assertTrue(omni.text.contains("Meta Platforms, Inc. and affiliates"))
        assertTrue(omni.text.contains("17db76eab583b0b868ffb0df104ab879145087e5"))
        for (e in listOf(freococo, omni)) assertTrue(e.text.contains("Version 2.0, January 2004"))
    }

    @Test
    fun includesOnnxRuntimeMitAndThirdPartyNotices() {
        val ort = AsrLicenses.all(context).single { it.component == "ONNX Runtime" }
        assertEquals("MIT", ort.license)
        assertTrue(ort.text.contains("Copyright (c) Microsoft Corporation"))
        assertTrue(ort.text.contains("THIRD PARTY SOFTWARE NOTICES AND INFORMATION", ignoreCase = true))
        // KleidiAI is compiled into libonnxruntime.so for arm64.
        assertTrue(ort.text.contains("KleidiAI"))
    }

    @Test
    fun noRajOrLlamaCppLeft() {
        val components = AsrLicenses.all(context).map { it.component }
        assertTrue(components.none { it.contains("Raj") || it.contains("llama") })
    }

    @Test
    fun includesWebRtcVadBsd3() {
        val vad = AsrLicenses.all(context).single { it.component == "WebRTC VAD" }
        assertEquals("BSD-3-Clause", vad.license)
        assertTrue(vad.text.contains("WebRTC"))
        assertTrue(vad.text.contains("Redistribution and use in source and binary forms"))
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
