package app.budgetwise.asr.demo

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper

/**
 * Captures microphone audio into memory only. [onLimit] runs on the main thread when the
 * [Pcm.MAX_SECONDS] cap is reached; the caller should then call [stop]. [onError] runs on the main
 * thread when capture dies mid-recording (a negative read code); the caller should then call [stop].
 */
internal class PushToTalkRecorder(
    private val onLimit: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val buffer = PcmBuffer(Pcm.SAMPLE_RATE * Pcm.MAX_SECONDS)
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /**
     * The caller must hold RECORD_AUDIO. Throws IllegalStateException with a user-facing message when
     * the mic cannot start.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        check(record == null) { "already recording" }
        buffer.clear()
        val rec = try {
            open()
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(MicErrors.UNAVAILABLE, e)
        }
        record = rec
        running = true
        thread = Thread({ pump(rec) }, "asr-demo-capture").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord {
        val minBytes = AudioRecord.getMinBufferSize(
            Pcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBytes > 0) { MicErrors.UNAVAILABLE }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            Pcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes, Pcm.SAMPLE_RATE / 5 * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error(MicErrors.UNAVAILABLE)
        }
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release()
            throw IllegalStateException(MicErrors.UNAVAILABLE, e)
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            error(MicErrors.UNAVAILABLE)
        }
        return rec
    }

    private fun pump(rec: AudioRecord) {
        val chunk = ShortArray(Pcm.SAMPLE_RATE / 50)
        while (running) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n < 0) {
                if (running) {
                    running = false
                    main.post { onError(MicErrors.readError(n)) }
                }
                break
            }
            if (n == 0) continue
            if (!buffer.append(chunk, n)) {
                running = false
                main.post(onLimit)
            }
        }
    }

    /** Stops capture and returns the audio as floats. The internal copy is zeroed. */
    fun stop(): FloatArray {
        running = false
        // Stop first: it unblocks a pending read so the join below returns promptly.
        record?.let { runCatching { it.stop() } }
        thread?.join(JOIN_MS)
        thread = null
        record?.release()
        record = null
        val audio = buffer.toFloatArray()
        buffer.clear()
        return audio
    }

    fun isRecording(): Boolean = record != null

    private companion object {
        const val JOIN_MS = 1_000L
    }
}
