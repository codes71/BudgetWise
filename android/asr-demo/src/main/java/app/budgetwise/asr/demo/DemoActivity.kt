package app.budgetwise.asr.demo

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.budgetwise.asr.AsrException
import app.budgetwise.asr.AsrLicenses
import app.budgetwise.asr.DeviceProfile
import app.budgetwise.asr.ModelState
import app.budgetwise.asr.RajVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hold the button, speak Burmese, read what Raj heard. Audio stays in memory only. */
class DemoActivity : ComponentActivity() {

    private enum class Phase { IDLE, LISTENING, TRANSCRIBING }

    private val app get() = application as DemoApp
    private val variant = RajVariant.RAJ_0_6B

    private lateinit var header: TextView
    private lateinit var modelStateView: TextView
    private lateinit var downloadButton: Button
    private lateinit var meteredCheck: CheckBox
    private lateinit var deleteButton: Button
    private lateinit var licensesButton: Button
    private lateinit var status: TextView
    private lateinit var talkButton: Button
    private lateinit var transcript: TextView
    private lateinit var metrics: TextView
    private lateinit var historyView: TextView

    private var modelState: ModelState = ModelState.Missing
    private var phase = Phase.IDLE
    private var errorLine: String? = null
    private var micDeniedHint = false
    private var recorder: PushToTalkRecorder? = null

    private val history = ArrayDeque<String>()

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micDeniedHint = !granted
            render()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Denied only hides the download notification; the download still runs.
            startDownload()
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo)
        header = findViewById(R.id.header)
        modelStateView = findViewById(R.id.modelState)
        downloadButton = findViewById(R.id.downloadButton)
        meteredCheck = findViewById(R.id.meteredCheck)
        deleteButton = findViewById(R.id.deleteButton)
        licensesButton = findViewById(R.id.licensesButton)
        status = findViewById(R.id.status)
        talkButton = findViewById(R.id.talkButton)
        transcript = findViewById(R.id.transcript)
        metrics = findViewById(R.id.metrics)
        historyView = findViewById(R.id.history)

        val device = DeviceProfile.detect(this)
        header.text = getString(
            R.string.header_line,
            device.socModel.ifBlank { "unknown SoC" },
            device.totalRamBytes / 1e9,
            device.threads(),
        )

        downloadButton.setOnClickListener { onDownloadClicked() }
        deleteButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    app.modelManager.delete(variant)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorLine = getString(R.string.error_line, e.message ?: e.javaClass.simpleName)
                    render()
                }
            }
        }
        licensesButton.setOnClickListener { showLicenses() }
        talkButton.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    onTalkDown()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    onTalkUp()
                }
            }
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.modelManager.state.collect { state ->
                    modelState = state
                    if (state is ModelState.Ready) warmUpOnce()
                    render()
                }
            }
        }
        render()
    }

    override fun onStop() {
        super.onStop()
        // Releasing the button never arrives once we are backgrounded, so drop the take.
        if (phase == Phase.LISTENING) finishRecording(transcribe = false)
    }

    override fun onDestroy() {
        recorder?.stop()
        recorder = null
        if (isFinishing) app.closeSpeech()
        super.onDestroy()
    }

    private fun warmUpOnce() {
        val stt = app.speech()
        if (app.warmedUp) return
        app.warmedUp = true
        lifecycleScope.launch {
            try {
                stt.warmUp()
            } catch (e: CancellationException) {
                app.warmedUp = false
                throw e
            } catch (e: Exception) {
                app.warmedUp = false
                errorLine = getString(R.string.error_line, e.message ?: e.javaClass.simpleName)
                render()
            }
        }
    }

    private fun onDownloadClicked() {
        errorLine = null
        val needsNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotif) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startDownload()
    }

    private fun startDownload() {
        val allowMetered = meteredCheck.isChecked
        lifecycleScope.launch {
            try {
                app.modelManager.ensureDownloaded(variant, allowMetered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorLine = getString(R.string.error_line, e.message ?: e.javaClass.simpleName)
                render()
            }
        }
    }

    private fun onTalkDown() {
        if (modelState !is ModelState.Ready || phase != Phase.IDLE) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        micDeniedHint = false
        errorLine = null
        val rec = PushToTalkRecorder(
            onLimit = { if (phase == Phase.LISTENING) finishRecording(transcribe = true) },
            onError = { message ->
                if (phase == Phase.LISTENING) {
                    finishRecording(transcribe = false)
                    errorLine = message
                    render()
                }
            },
        )
        try {
            rec.start()
        } catch (e: IllegalStateException) {
            errorLine = e.message ?: MicErrors.UNAVAILABLE
            render()
            return
        }
        recorder = rec
        phase = Phase.LISTENING
        render()
    }

    private fun onTalkUp() {
        if (phase == Phase.LISTENING) finishRecording(transcribe = true)
    }

    private fun finishRecording(transcribe: Boolean) {
        val audio = recorder?.stop() ?: FloatArray(0)
        recorder = null
        if (!transcribe) {
            audio.fill(0f)
            phase = Phase.IDLE
            render()
            return
        }
        if (audio.size < MIN_SAMPLES) {
            audio.fill(0f)
            errorLine = getString(R.string.status_too_short)
            phase = Phase.IDLE
            render()
            return
        }
        phase = Phase.TRANSCRIBING
        render()
        val stt = app.speech()
        lifecycleScope.launch {
            try {
                val result = stt.transcribe(audio)
                val text = result.text.trim().ifEmpty { getString(R.string.no_speech) }
                showTranscript(text, Pcm.metricsLine(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AsrException.ModelNotReady) {
                errorLine = getString(R.string.error_line, getString(R.string.status_not_ready))
            } catch (e: AsrException.EngineFailure) {
                errorLine = getString(R.string.error_line, "engine failure (${e.code}): ${e.message}")
            } catch (e: Exception) {
                errorLine = getString(R.string.error_line, e.message ?: e.javaClass.simpleName)
            } finally {
                audio.fill(0f)
                phase = Phase.IDLE
                render()
            }
        }
    }

    private fun showTranscript(text: String, metricsText: String?) {
        transcript.text = text
        metrics.text = metricsText.orEmpty()
        history.addFirst(text)
        while (history.size > HISTORY_SIZE) history.removeLast()
        historyView.text = history.joinToString("\n\n")
    }

    private fun showLicenses() {
        lifecycleScope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    AsrLicenses.all(this@DemoActivity).joinToString("\n\n========================================\n\n") {
                        "${it.component} (${it.license})\n\n${it.text}"
                    }
                }
            } catch (e: Exception) {
                "Could not load licenses: ${e.message}"
            }
            val body = TextView(this@DemoActivity).apply {
                this.text = text
                textSize = 12f
                setTextIsSelectable(true)
                setPadding(PAD, PAD, PAD, PAD)
            }
            AlertDialog.Builder(this@DemoActivity)
                .setTitle(R.string.licenses_button)
                .setView(ScrollView(this@DemoActivity).apply { addView(body) })
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun render() {
        val state = modelState
        modelStateView.text = when (state) {
            is ModelState.Missing -> getString(R.string.model_missing)
            is ModelState.Downloading -> getString(R.string.model_downloading, (state.progress * 100).toInt())
            is ModelState.Ready -> getString(R.string.model_ready)
            is ModelState.Failed -> getString(R.string.model_failed, state.reason)
        }
        val downloading = state is ModelState.Downloading
        val ready = state is ModelState.Ready
        downloadButton.isEnabled = !downloading && !ready
        meteredCheck.isEnabled = !downloading && !ready
        deleteButton.isEnabled = phase == Phase.IDLE && (ready || downloading || state is ModelState.Failed)

        talkButton.isEnabled = ready && phase != Phase.TRANSCRIBING
        status.text = errorLine ?: when {
            !ready -> getString(R.string.status_not_ready)
            phase == Phase.LISTENING -> getString(R.string.status_listening)
            phase == Phase.TRANSCRIBING -> getString(R.string.status_transcribing)
            micDeniedHint -> getString(R.string.status_mic_denied)
            else -> getString(R.string.status_idle)
        }
    }

    private companion object {
        const val HISTORY_SIZE = 10
        const val PAD = 32

        /** Taps shorter than 0.3 s hold no speech worth decoding. */
        const val MIN_SAMPLES = Pcm.SAMPLE_RATE * 3 / 10
    }
}
