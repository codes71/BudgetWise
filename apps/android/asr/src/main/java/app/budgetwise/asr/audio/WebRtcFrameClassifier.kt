package app.budgetwise.asr.audio

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.io.Closeable

/** WebRTC VAD (android-vad 2.0.10) over 480-sample frames at 16 kHz, no extra duration smoothing. */
class WebRtcFrameClassifier : FrameClassifier, Closeable {
    private val vad = VadWebRTC(
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_480,
        mode = Mode.VERY_AGGRESSIVE,
        speechDurationMs = 0,
        silenceDurationMs = 0,
    )

    override fun isSpeech(frame: ShortArray): Boolean = vad.isSpeech(frame)

    override fun close() = vad.close()
}
