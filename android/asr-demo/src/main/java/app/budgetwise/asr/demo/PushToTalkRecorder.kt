package app.budgetwise.asr.demo

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper

/**
 * Captures microphone audio into memory only. [onLimit] runs on the main thread when the
 * [Pcm.MAX_SECONDS] cap is reached; the caller should then call [stop].
 */
internal class PushToTalkRecorder(private val onLimit: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val buffer = PcmBuffer(Pcm.SAMPLE_RATE * Pcm.MAX_SECONDS)
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** The caller must hold RECORD_AUDIO. Throws IllegalStateException when the mic cannot start. */
    @SuppressLint("MissingPermission")
    fun start() {
        check(record == null) { "already recording" }
        buffer.clear()
        val minBytes = AudioRecord.getMinBufferSize(
            Pcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBytes > 0) { "microphone unavailable" }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            Pcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes, Pcm.SAMPLE_RATE / 5 * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("microphone could not be opened")
        }
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release()
            throw e
        }
        record = rec
        running = true
        thread = Thread({ pump(rec) }, "asr-demo-capture").also { it.start() }
    }

    private fun pump(rec: AudioRecord) {
        val chunk = ShortArray(Pcm.SAMPLE_RATE / 50)
        while (running) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) break
            if (!buffer.append(chunk, n)) {
                running = false
                main.post(onLimit)
            }
        }
    }

    /** Stops capture and returns the audio as floats. The internal copy is zeroed. */
    fun stop(): FloatArray {
        running = false
        thread?.join()
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        val audio = buffer.toFloatArray()
        buffer.clear()
        return audio
    }

    fun isRecording(): Boolean = record != null
}
