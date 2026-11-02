package app.budgetwise.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [Microphone] on [AudioRecord]: 16 kHz mono 16-bit from the voice recognition source, read on its own thread.
 * A recording stops by itself after [maxSeconds]: the speech models were measured on up to 20 s of audio.
 */
class AudioRecordMicrophone(private val maxSeconds: Int = 20) : Microphone {
    override fun start(): Recording = AudioRecordRecording(open(), Pcm.SAMPLE_RATE * maxSeconds)

    @SuppressLint("MissingPermission") // The caller holds RECORD_AUDIO ([Microphone.start]).
    private fun open(): AudioRecord {
        val minBytes = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) throw MicUnavailableException("no buffer size ($minBytes)")
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Pcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes, Pcm.SAMPLE_RATE / 5 * 2),
            )
        } catch (e: IllegalArgumentException) {
            throw MicUnavailableException("bad parameters", e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicUnavailableException("not initialized")
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            throw MicUnavailableException("could not start", e)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // Another app holds the microphone.
            record.release()
            throw MicUnavailableException("in use")
        }
        return record
    }
}

private class AudioRecordRecording(private val record: AudioRecord, maxSamples: Int) : Recording {
    private val buffer = PcmBuffer(maxSamples)
    private val lock = Any()
    private val _level = MutableStateFlow(0f)
    private val _ended = MutableStateFlow<RecordingEnd?>(null)
    override val level: StateFlow<Float> = _level.asStateFlow()
    override val ended: StateFlow<RecordingEnd?> = _ended.asStateFlow()

    @Volatile
    private var running = true
    private var finished = false

    /** Whoever finishes last, [finish] or a capture thread still inside a read, releases the recorder. */
    private var recordReleased = false
    private val thread = Thread(::pump, "voice-capture").apply { start() }

    private fun pump() {
        val chunk = ShortArray(Pcm.SAMPLE_RATE / 50)
        while (running) {
            val n = record.read(chunk, 0, chunk.size)
            if (n < 0) {
                if (running) _ended.value = RecordingEnd.ERROR
                break
            }
            if (n == 0) continue
            _level.value = Pcm.level(chunk, n)
            val fitted = synchronized(lock) { buffer.append(chunk, n) }
            if (!fitted) {
                _ended.value = RecordingEnd.LIMIT
                break
            }
        }
        chunk.fill(0)
        // finish() gave up waiting for this thread (a read that stop() did not unblock): release here, after the read.
        synchronized(lock) { if (finished) releaseRecord() }
    }

    override fun stop(): FloatArray {
        finish()
        return synchronized(lock) {
            val audio = buffer.toFloatArray()
            buffer.clear()
            audio
        }
    }

    override fun cancel() {
        finish()
        synchronized(lock) { buffer.clear() }
    }

    private fun finish() {
        synchronized(lock) {
            if (finished) return
            finished = true
        }
        running = false
        // Stopping first unblocks a pending read, so the join normally returns within one 20 ms read.
        runCatching { record.stop() }
        thread.join(JOIN_MS)
        // Never release under a read still in progress: if the thread is alive, it releases when its read returns.
        synchronized(lock) { if (!thread.isAlive) releaseRecord() }
        _level.value = 0f
    }

    /** Called with [lock] held. */
    private fun releaseRecord() {
        if (recordReleased) return
        recordReleased = true
        record.release()
    }

    private companion object {
        const val JOIN_MS = 1_000L
    }
}
