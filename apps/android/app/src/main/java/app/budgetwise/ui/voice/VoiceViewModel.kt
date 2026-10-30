package app.budgetwise.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.BudgetSource
import app.budgetwise.CategorySource
import app.budgetwise.OnDeviceSpeech
import app.budgetwise.TransactionStore
import app.budgetwise.VoiceParser
import app.budgetwise.data.model.Source
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.NETWORK_ERROR_CODE
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.data.repo.TransactionRepository
import app.budgetwise.data.repo.VoiceEntry
import app.budgetwise.data.repo.VoiceMetric
import app.budgetwise.data.repo.VoiceParse
import app.budgetwise.data.text.BurmeseTextNormalizer
import app.budgetwise.di.DeviceClock
import app.budgetwise.nlu.TxType
import app.budgetwise.voice.MicUnavailableException
import app.budgetwise.voice.Microphone
import app.budgetwise.voice.Recording
import app.budgetwise.voice.RecordingEnd
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why voice stopped short; each has its own card over the stopped listening screen. */
sealed interface VoiceProblem {
    /** "Understand what I say" is off in Settings. */
    data object VoiceOff : VoiceProblem

    /** No microphone permission. [canAsk]: Android still shows its own question; otherwise only Settings can allow it. */
    data class MicDenied(val canAsk: Boolean) : VoiceProblem

    /** Another app holds the microphone, or it stopped mid-recording. */
    data object MicBusy : VoiceProblem

    /** The speech models heard nothing. */
    data object NothingHeard : VoiceProblem

    /** Something was heard, but no amount (or nothing about money). */
    data class NoAmount(val heard: String) : VoiceProblem

    /** No connection to read what was said; the words are kept for Try again. */
    data object Offline : VoiceProblem

    /** The server could not read them; the words are kept for Try again. */
    data object Failed : VoiceProblem

    /** The session ended. */
    data object SignedOut : VoiceProblem

    /** On-device recognition failed (out of memory, the models were removed). */
    data object ListenFailed : VoiceProblem
}

enum class WorkStep { LISTENING_BACK, UNDERSTANDING }

data class ReviewState(
    val heard: String,
    val entries: List<VoiceEntry>,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    /** What the big number shows: the expenses, or what came in when nothing was spent. */
    val totalType: TxType get() = if (entries.any { it.type == TxType.EXPENSE }) TxType.EXPENSE else TxType.INCOME

    val totalKyat: Long get() = entries.filter { it.type == totalType }.sumOf { it.amountKyat }

    val guessedDates: Int get() = entries.count { it.dateGuessed }
}

sealed interface VoicePhase {
    /** Before the microphone starts: voice is checked, then the permission. */
    data object Starting : VoicePhase

    data object Listening : VoicePhase

    data class Working(val step: WorkStep) : VoicePhase

    data class Problem(val problem: VoiceProblem) : VoicePhase

    /** Entries to check before they are saved. */
    data class Review(val review: ReviewState) : VoicePhase

    /** A question answered from the phone's own data. [showEntries] lists the entries instead of the categories. */
    data class Answer(val heard: String, val answer: VoiceAnswer, val showEntries: Boolean) : VoicePhase
}

data class VoiceUiState(
    val today: LocalDate,
    val phase: VoicePhase = VoicePhase.Starting,
    /** Seconds since listening started; it stops with the microphone. */
    val elapsedSeconds: Int = 0,
    /** The microphone's loudness, 0..1, while listening. */
    val level: Float = 0f,
    val myanmarDigits: Boolean = false,
    val categories: List<String> = CategoryRepository.BUILT_IN,
)

sealed interface VoiceEvent {
    /** Check the microphone permission (asking when needed), then call [VoiceViewModel.listen] or [VoiceViewModel.micDenied]. */
    data object NeedMic : VoiceEvent

    /** The entries are in Room (and the outbox): close voice. Fires once. */
    data object Saved : VoiceEvent
}

/**
 * Voice entry, from the microphone to saved entries or an answer. The audio stays in memory: the on-device models
 * ([OnDeviceSpeech]) turn it into text and it is zeroed. Only that text goes to the server ([VoiceParser]), which
 * reads it into entries to check, or a question this class answers from Room ([answer]).
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val microphone: Microphone,
    private val speech: OnDeviceSpeech,
    private val parser: VoiceParser,
    private val settings: SettingsStore,
    private val transactions: TransactionStore,
    private val budgets: BudgetSource,
    categories: CategorySource,
    @DeviceClock clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(VoiceUiState(today = LocalDate.now(clock)))
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _events = Channel<VoiceEvent>(Channel.BUFFERED)
    val events: Flow<VoiceEvent> = _events.receiveAsFlow()

    private var recording: Recording? = null
    private var listenJob: Job? = null
    private var workJob: Job? = null

    /** What the speech models heard, kept so Try again does not need the user to speak again. */
    private var transcripts: List<String> = emptyList()

    init {
        viewModelScope.launch { settings.myanmarDigits.collect { on -> _state.update { it.copy(myanmarDigits = on) } } }
        viewModelScope.launch { categories.categories.collect { names -> _state.update { it.copy(categories = names) } } }
        // Loading the models takes a few seconds the first time; do it while the user speaks.
        viewModelScope.launch {
            try {
                speech.warmUp()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The first transcription tries again and reports a failure itself.
            }
        }
        viewModelScope.launch {
            if (settings.cloudParseEnabled.first()) _events.send(VoiceEvent.NeedMic) else problem(VoiceProblem.VoiceOff)
        }
    }

    private fun problem(problem: VoiceProblem) = _state.update { it.copy(phase = VoicePhase.Problem(problem), level = 0f) }

    /** Starts listening; the caller holds the microphone permission. */
    fun listen() {
        if (recording != null) return
        workJob?.cancel()
        val rec = try {
            microphone.start()
        } catch (_: MicUnavailableException) {
            problem(VoiceProblem.MicBusy)
            return
        }
        recording = rec
        transcripts = emptyList()
        _state.update { it.copy(phase = VoicePhase.Listening, elapsedSeconds = 0, level = 0f) }
        listenJob = viewModelScope.launch {
            launch {
                // The microphone stops itself well before this (its time limit); the bound only keeps the loop finite.
                repeat(MAX_TIMER_SECONDS) {
                    delay(1_000)
                    _state.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }
                }
            }
            launch { rec.level.collect { level -> _state.update { it.copy(level = level) } } }
            when (rec.ended.filterNotNull().first()) {
                // The time limit keeps what was said, as if the user had tapped Done.
                RecordingEnd.LIMIT -> done()
                RecordingEnd.ERROR -> {
                    stopRecording()
                    problem(VoiceProblem.MicBusy)
                }
            }
        }
    }

    fun micDenied(canAsk: Boolean) {
        stopRecording()
        problem(VoiceProblem.MicDenied(canAsk))
    }

    /** Stops listening and works out what was said. */
    fun done() {
        val rec = recording ?: return
        recording = null
        listenJob?.cancel()
        val audio = rec.stop()
        _state.update { it.copy(phase = VoicePhase.Working(WorkStep.LISTENING_BACK), level = 0f) }
        workJob = viewModelScope.launch {
            val heard = try {
                speech.transcribe(audio).transcripts.map { it.text.trim() }.filter { it.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                problem(VoiceProblem.ListenFailed)
                return@launch
            } finally {
                audio.fill(0f)
            }
            if (heard.isEmpty()) {
                problem(VoiceProblem.NothingHeard)
                return@launch
            }
            transcripts = heard
            understand()
        }
    }

    /** Sends the kept words again (after no connection, or a server failure). */
    fun retry() {
        if (transcripts.isEmpty() || workJob?.isActive == true) return
        workJob = viewModelScope.launch { understand() }
    }

    /** Back to listening, from a problem, the review or an answer. */
    fun sayAgain() {
        stopRecording()
        workJob?.cancel()
        _state.update { it.copy(phase = VoicePhase.Starting) }
        _events.trySend(VoiceEvent.NeedMic)
    }

    /** Turns "Understand what I say" back on and starts listening. */
    fun turnOnVoice() {
        viewModelScope.launch {
            try {
                settings.setCloudParseEnabled(true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch // DataStore could not write; the card stays.
            }
            _state.update { it.copy(phase = VoicePhase.Starting) }
            _events.send(VoiceEvent.NeedMic)
        }
    }

    private suspend fun understand() {
        _state.update { it.copy(phase = VoicePhase.Working(WorkStep.UNDERSTANDING)) }
        val next = when (val r = parser.parse(transcripts)) {
            is ApiResult.Ok -> when (val parse = r.v) {
                is VoiceParse.Add -> VoicePhase.Review(ReviewState(parse.heard, parse.entries.map(::notAfterToday)))
                is VoiceParse.Question -> VoicePhase.Answer(
                    heard = parse.heard,
                    answer = answer(parse.question, transactions.observe().first(), budgets.observe().first()),
                    showEntries = parse.question.metric == VoiceMetric.LIST,
                )
                is VoiceParse.Other ->
                    VoicePhase.Problem(if (parse.heard.isBlank()) VoiceProblem.NothingHeard else VoiceProblem.NoAmount(parse.heard))
            }
            is ApiResult.Unauthorized -> VoicePhase.Problem(VoiceProblem.SignedOut)
            is ApiResult.Failure ->
                VoicePhase.Problem(if (r.code == NETWORK_ERROR_CODE) VoiceProblem.Offline else VoiceProblem.Failed)
        }
        _state.update { it.copy(phase = next) }
    }

    /** The server allows tomorrow in Yangon; the phone's form never takes a future day, so neither does voice. */
    private fun notAfterToday(entry: VoiceEntry): VoiceEntry {
        val today = _state.value.today
        return if (entry.date.isAfter(today)) entry.copy(date = today) else entry
    }

    private fun editReview(change: (ReviewState) -> ReviewState) = _state.update { s ->
        val review = (s.phase as? VoicePhase.Review)?.review ?: return@update s
        if (review.saving) return@update s
        s.copy(phase = VoicePhase.Review(change(review).copy(saveFailed = false)))
    }

    /** The user changed an entry; a changed date is no longer a guess. */
    fun updateEntry(index: Int, entry: VoiceEntry) = editReview { review ->
        val old = review.entries.getOrNull(index) ?: return@editReview review
        val fixed = notAfterToday(entry.copy(dateGuessed = old.dateGuessed && entry.date == old.date))
        val income = fixed.type == TxType.INCOME
        val filed = fixed.copy(category = if (income) CategoryRepository.INCOME else fixed.category)
        review.copy(entries = review.entries.toMutableList().also { it[index] = filed })
    }

    fun removeEntry(index: Int) = editReview { review ->
        review.copy(entries = review.entries.filterIndexed { i, _ -> i != index })
    }

    fun toggleAnswerEntries() = _state.update { s ->
        val answer = s.phase as? VoicePhase.Answer ?: return@update s
        s.copy(phase = answer.copy(showEntries = !answer.showEntries))
    }

    /** Saves every entry with [Source.VOICE] and what was heard, offline-first like the add form. */
    fun save() {
        val review = (_state.value.phase as? VoicePhase.Review)?.review ?: return
        if (review.saving || review.entries.isEmpty()) return
        val heard = review.heard.ifBlank { null }?.take(MAX_TRANSCRIPT)
        val inputs = review.entries.map { entry ->
            NewTransaction(
                type = entry.type,
                amountKyat = entry.amountKyat,
                category = entry.category,
                description = BurmeseTextNormalizer.prepare(entry.description).take(TransactionRepository.MAX_DESCRIPTION),
                date = entry.date,
                source = Source.VOICE,
                transcript = heard,
            )
        }
        _state.update { it.copy(phase = VoicePhase.Review(review.copy(saving = true, saveFailed = false))) }
        viewModelScope.launch {
            try {
                transactions.addAll(inputs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Room failed; nothing was saved (one transaction). The review stays for another try.
                _state.update { it.copy(phase = VoicePhase.Review(review.copy(saving = false, saveFailed = true))) }
                return@launch
            }
            _events.send(VoiceEvent.Saved)
        }
    }

    private fun stopRecording() {
        listenJob?.cancel()
        recording?.cancel()
        recording = null
    }

    override fun onCleared() {
        stopRecording()
    }

    private companion object {
        /** The server keeps up to 2,000 characters of a transcript; what was heard is far shorter. */
        const val MAX_TRANSCRIPT = 2_000
        const val MAX_TIMER_SECONDS = 60
    }
}
