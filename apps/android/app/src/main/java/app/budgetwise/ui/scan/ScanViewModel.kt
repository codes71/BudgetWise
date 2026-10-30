package app.budgetwise.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.CategorySource
import app.budgetwise.ReceiptScanner
import app.budgetwise.TransactionStore
import app.budgetwise.data.model.Source
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.NETWORK_ERROR_CODE
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.repo.CategoryRepository
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.data.repo.ScanItem
import app.budgetwise.data.repo.ScanKind
import app.budgetwise.data.repo.ScanReading
import app.budgetwise.di.DeviceClock
import app.budgetwise.nlu.TxType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a photo gave nothing to check. */
enum class ScanProblem {
    /** Not a receipt or KPay screen, blurred, or the AI read failed. */
    UNREADABLE,

    /** No connection: reading needs the server. The photo is kept for Try again. */
    NO_INTERNET,

    /** 20 scans an hour. */
    TOO_MANY,

    /** The server failed (5xx). */
    SERVER,

    /** The session has ended; sign in again. */
    SIGN_IN,
}

/** Where the scan is: aiming the camera, waiting on the server, a problem card, or checking what was read. */
enum class ScanStage { CAMERA, READING, PROBLEM, REVIEW }

/**
 * One line of the review. On a receipt it is one category's share, with its items; on a KPay slip or history it is one
 * payment. Each kept line becomes its own entry.
 */
data class ReviewLine(
    val type: TxType,
    val amountKyat: Long,
    val category: String,
    val description: String,
    val date: LocalDate,
    val clientId: String?,
    val items: List<ScanItem>,
    val kept: Boolean = true,
)

data class ScanUiState(
    val today: LocalDate,
    val stage: ScanStage = ScanStage.CAMERA,
    val problem: ScanProblem? = null,
    val kind: ScanKind = ScanKind.UNKNOWN,
    val merchant: String? = null,
    /** The receipt's date; null when none could be read (the entries then use today). */
    val date: LocalDate? = null,
    val totalKyat: Long? = null,
    val lines: List<ReviewLine> = emptyList(),
    val categories: List<String> = CategoryRepository.BUILT_IN,
    val myanmarDigits: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    val keptLines: List<ReviewLine> get() = lines.filter { it.kept }

    /** What the review adds up to; the receipt's printed total when it has one. */
    val shownTotal: Long get() = totalKyat ?: keptLines.sumOf { it.amountKyat }

    /** How much the receipt's lines fall short of its printed total, so the rest can go under Other. */
    val shortBy: Long?
        get() {
            if (kind != ScanKind.RECEIPT) return null
            val total = totalKyat ?: return null
            return (total - lines.sumOf { it.amountKyat }).takeIf { it > 0 }
        }

    val canSave: Boolean get() = !saving && keptLines.isNotEmpty()
}

sealed interface ScanEvent {
    /** The entries are in Room (and the outbox): [clientIds] for Undo, [merchant] for the notice. Fires once. */
    data class Saved(val clientIds: List<String>, val merchant: String?) : ScanEvent
}

/**
 * Receipt scanning: a photo goes to `POST /scan`, the reply becomes lines to check, and the kept lines are saved
 * offline-first like the add form. The photo stays in memory until the scan ends, so Try again needs no new photo.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanner: ReceiptScanner,
    private val transactions: TransactionStore,
    categories: CategorySource,
    settings: SettingsStore,
    @DeviceClock clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(ScanUiState(today = LocalDate.now(clock)))
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events: Flow<ScanEvent> = _events.receiveAsFlow()

    /** The photo being read or last read; the screen shows it behind the reading and problem cards. */
    var photo: PreparedPhoto? = null
        private set

    private var reading: Job? = null

    init {
        viewModelScope.launch {
            categories.categories.collect { names ->
                _state.update { it.copy(categories = names.filter { n -> n != CategoryRepository.INCOME }) }
            }
        }
        viewModelScope.launch { settings.myanmarDigits.collect { on -> _state.update { it.copy(myanmarDigits = on) } } }
    }

    fun read(prepared: PreparedPhoto) {
        photo = prepared
        readPhoto()
    }

    /** Reads the kept photo again (No internet, Server); without one, back to the camera. */
    fun tryAgain() = if (photo != null) readPhoto() else retake()

    /** Drops the photo and the reply: back to the camera. Cancels a read in progress. */
    fun retake() {
        reading?.cancel()
        reading = null
        photo = null
        _state.update { ScanUiState(today = it.today, categories = it.categories, myanmarDigits = it.myanmarDigits) }
    }

    private fun readPhoto() {
        val prepared = photo ?: return
        reading?.cancel()
        _state.update { it.copy(stage = ScanStage.READING, problem = null) }
        reading = viewModelScope.launch {
            val result = try {
                scanner.scan(prepared.bytes, prepared.mediaType)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A Keystore failure reading the token, not an answer from the server.
                ApiResult.Failure("local", "Could not send the photo", retryable = true)
            }
            when (result) {
                is ApiResult.Ok -> show(result.v)
                is ApiResult.Unauthorized -> fail(ScanProblem.SIGN_IN)
                is ApiResult.Failure -> fail(
                    when {
                        result.code == NETWORK_ERROR_CODE -> ScanProblem.NO_INTERNET
                        result.code == "rate_limited" || result.code == "http_429" -> ScanProblem.TOO_MANY
                        result.code == "scan_failed" -> ScanProblem.UNREADABLE
                        result.retryable -> ScanProblem.SERVER
                        else -> ScanProblem.UNREADABLE
                    },
                )
            }
        }
    }

    private fun fail(problem: ScanProblem) = _state.update { it.copy(stage = ScanStage.PROBLEM, problem = problem) }

    private fun show(reading: ScanReading) {
        if (reading.kind == ScanKind.UNKNOWN || reading.entries.isEmpty()) return fail(ScanProblem.UNREADABLE)
        val lines = reading.entries.map {
            ReviewLine(it.type, it.amountKyat, it.category, it.description, it.date, it.clientId, it.items)
        }
        _state.update {
            it.copy(
                stage = ScanStage.REVIEW,
                problem = null,
                kind = reading.kind,
                merchant = reading.merchant,
                date = reading.date,
                totalKyat = reading.totalKyat,
                lines = lines,
            )
        }
    }

    /** A receipt line under another category; it joins the line already there for that category, if any. */
    fun move(index: Int, category: String) = _state.update { s ->
        val line = s.lines.getOrNull(index) ?: return@update s
        if (line.category == category) return@update s
        val moved = line.copy(category = category)
        val rest = s.lines.filterIndexed { i, _ -> i != index }
        val joinAt = rest.indexOfFirst { it.type == moved.type && it.category == category }
        val lines = if (joinAt < 0) {
            s.lines.toMutableList().also { it[index] = moved }
        } else {
            rest.toMutableList().also {
                val into = it[joinAt]
                it[joinAt] = into.copy(amountKyat = into.amountKyat + moved.amountKyat, items = into.items + moved.items)
            }
        }
        s.copy(lines = lines, saveFailed = false)
    }

    /** The part of the receipt's total its lines miss, as an Other line (or added to the Other line there). */
    fun addShortAsOther() = _state.update { s ->
        val short = s.shortBy ?: return@update s
        val at = s.lines.indexOfFirst { it.type == TxType.EXPENSE && it.category == OTHER }
        val lines = if (at >= 0) {
            s.lines.toMutableList().also { it[at] = it[at].copy(amountKyat = it[at].amountKyat + short) }
        } else {
            s.lines + ReviewLine(TxType.EXPENSE, short, OTHER, s.merchant ?: OTHER, s.date ?: s.today, null, emptyList())
        }
        s.copy(lines = lines, saveFailed = false)
    }

    /** A KPay history row in or out. */
    fun toggle(index: Int) = _state.update { s ->
        val line = s.lines.getOrNull(index) ?: return@update s
        s.copy(lines = s.lines.toMutableList().also { it[index] = line.copy(kept = !line.kept) }, saveFailed = false)
    }

    /** Each kept line as its own entry. [describe] writes a receipt line's description in the app's language. */
    fun addEntries(describe: (ReviewLine) -> String) {
        val s = _state.value
        if (!s.canSave) return
        save(s.keptLines.map { it.toNew(if (s.kind == ScanKind.RECEIPT) describe(it) else it.description) })
    }

    /** The whole receipt as one entry, under its largest category, for [description] (the shop's name). */
    fun addAsOne(description: String) {
        val s = _state.value
        if (!s.canSave || s.kind != ScanKind.RECEIPT) return
        val largest = s.keptLines.maxBy { it.amountKyat }
        save(listOf(largest.copy(amountKyat = s.shownTotal, clientId = null).toNew(description)))
    }

    private fun ReviewLine.toNew(description: String) = NewTransaction(
        type = type,
        amountKyat = amountKyat,
        category = if (type == TxType.INCOME) CategoryRepository.INCOME else category,
        description = description.trim().ifEmpty { category }.take(MAX_DESCRIPTION),
        date = date,
        // The server takes manual or voice; a scanned entry was checked and kept by hand.
        source = Source.MANUAL,
        transcript = null,
        clientId = clientId,
    )

    private fun save(inputs: List<NewTransaction>) {
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val ids = try {
                transactions.addAll(inputs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Room failed; nothing was saved (one transaction). The review stays for another try.
                _state.update { it.copy(saving = false, saveFailed = true) }
                return@launch
            }
            photo = null
            _events.send(ScanEvent.Saved(ids, _state.value.merchant))
        }
    }

    companion object {
        const val OTHER = "Other"
        private const val MAX_DESCRIPTION = 200
    }
}
