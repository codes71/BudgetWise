package app.budgetwise.ui.scan

import androidx.lifecycle.SavedStateHandle
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
import app.budgetwise.data.repo.TransactionRepository
import app.budgetwise.data.text.BurmeseTextNormalizer
import app.budgetwise.di.DeviceClock
import app.budgetwise.nlu.TxType
import app.budgetwise.ui.common.MAX_KYAT
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
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
 *
 * [originalKyat] and [originalItems] are the line as read (or as saved, when a receipt's items are changed later);
 * [items] are the same items with the counts and prices the user set, in the same order.
 */
data class ReviewLine(
    val type: TxType,
    val originalKyat: Long,
    val category: String,
    val description: String,
    val date: LocalDate,
    val clientId: String?,
    val items: List<ScanItem>,
    val kept: Boolean = true,
    val originalItems: List<ScanItem> = items,
) {
    /**
     * What the line comes to: as read, moved by what the changed items add or take off. A part the items do not cover
     * (the short-by amount put under Other) stays.
     */
    val amountKyat: Long
        get() = (originalKyat + items.sumOf { it.amountKyat } - originalItems.sumOf { it.amountKyat }).coerceAtLeast(0)

    /** How many items have another count or price than they were read with. */
    val changedItems: Int get() = items.indices.count { items[it] != originalItems.getOrNull(it) }

    /** The items an entry keeps: one left at 0 was not bought. */
    val savedItems: List<ScanItem> get() = items.filter { it.quantity > 0 }

    /** [count] of the item at [index], from 0 (left out) to [MAX_COUNT]. */
    fun withCount(index: Int, count: Int): ReviewLine = withItem(index) { it.copy(quantity = count.coerceIn(0, MAX_COUNT)) }

    /** The item at [index] at [price] kyat each. */
    fun withPrice(index: Int, price: Long): ReviewLine = withItem(index) { it.copy(unitPriceKyat = price.coerceIn(0, MAX_KYAT)) }

    private fun withItem(index: Int, change: (ScanItem) -> ScanItem): ReviewLine {
        val item = items.getOrNull(index) ?: return this
        return copy(items = items.toMutableList().also { it[index] = change(item) })
    }

    companion object {
        /** The most of one item a receipt line takes, as the scan reads it. */
        const val MAX_COUNT = 999
    }
}

data class ScanUiState(
    val today: LocalDate,
    val stage: ScanStage = ScanStage.CAMERA,
    val problem: ScanProblem? = null,
    val kind: ScanKind = ScanKind.UNKNOWN,
    val merchant: String? = null,
    /** The receipt's date; null when none could be read (the entries then use [receiptDate]). */
    val date: LocalDate? = null,
    val totalKyat: Long? = null,
    val lines: List<ReviewLine> = emptyList(),
    val categories: List<String> = CategoryRepository.BUILT_IN,
    val myanmarDigits: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    /** The lines to save. A receipt line whose items were all set to 0 is not one. */
    val keptLines: List<ReviewLine> get() = lines.filter { it.kept && it.amountKyat > 0 }

    /** Receipt items whose count or price the user changed. */
    val changedItems: Int get() = lines.sumOf { it.changedItems }

    /** What was read: the receipt's printed total, else what its lines came to. */
    val scannedTotal: Long get() = totalKyat ?: lines.sumOf { it.originalKyat }

    /**
     * The one date every entry of a receipt is saved with: the receipt's, else the date its lines were given (today,
     * where the server is). One receipt is one line in the list, on one day.
     */
    val receiptDate: LocalDate get() = date ?: lines.firstOrNull()?.date ?: today

    /**
     * What the review adds up to. A receipt: what its kept lines come to now, which is what Add saves; when that is not
     * the printed total, a notice says by how much ([shortBy], [overBy]). A KPay slip: its amount as read. Not shown
     * for a KPay history.
     */
    val shownTotal: Long
        get() = keptLines.sumOf { it.amountKyat }.let { kept -> if (kind == ScanKind.RECEIPT) kept else totalKyat ?: kept }

    /**
     * The receipt's printed total less what its lines come to now; null when they match or there is no printed total.
     * Read again after each change of a count or a price, so a correction that closes the gap puts its notice away.
     */
    private val receiptGap: Long?
        get() = totalKyat?.takeIf { kind == ScanKind.RECEIPT }?.let { it - keptLines.sumOf { line -> line.amountKyat } }?.takeIf { it != 0L }

    /** How much the receipt's lines fall short of its printed total, so the rest can go under Other. */
    val shortBy: Long? get() = receiptGap?.takeIf { it > 0 }

    /** How much the receipt's lines come to over its printed total (a discount or a price misread). */
    val overBy: Long? get() = receiptGap?.takeIf { it < 0 }?.let { -it }

    val canSave: Boolean get() = !saving && keptLines.isNotEmpty()

    /** The receipt can be one entry: more than one kept line, and no more items together than an entry keeps. */
    val canAddAsOne: Boolean
        get() = kind == ScanKind.RECEIPT && keptLines.size > 1 && keptLines.sumOf { it.savedItems.size } <= TransactionRepository.MAX_ITEMS
}

sealed interface ScanEvent {
    /** The entries are in Room (and the outbox): [clientIds] for Undo, [merchant] for the notice. Fires once. */
    data class Saved(val clientIds: List<String>, val merchant: String?) : ScanEvent
}

/**
 * Receipt scanning: a photo goes to `POST /scan`, the reply becomes lines to check, and the kept lines are saved
 * offline-first like the add form. The photo stays in memory until the scan ends, so Try again needs no new photo.
 * The review, with the user's changes, is kept in [saved] until it is saved or dropped, so it outlives the process.
 * Reading and a problem card are not: after the process ends there is no photo left to read or retry.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanner: ReceiptScanner,
    private val transactions: TransactionStore,
    categories: CategorySource,
    settings: SettingsStore,
    @DeviceClock clock: Clock,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(restored(ScanUiState(today = LocalDate.now(clock))))
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    /** The entries are in Room: the review is not kept any more, so it never comes back to be saved twice. */
    private var savedEntries = false

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
        viewModelScope.launch {
            _state.collect { s ->
                if (s.stage == ScanStage.REVIEW && !savedEntries) saved[REVIEW] = s.toSaved() else saved.remove<SavedReview>(REVIEW)
            }
        }
    }

    /** [fresh] with the review kept from before the process ended, if there was one; never a save in progress. */
    private fun restored(fresh: ScanUiState): ScanUiState {
        val review = saved.get<SavedReview>(REVIEW) ?: return fresh
        return fresh.copy(
            stage = ScanStage.REVIEW,
            kind = review.kind,
            merchant = review.merchant,
            date = review.date,
            totalKyat = review.totalKyat,
            lines = review.lines.map { it.toLine() },
        )
    }

    private fun ScanUiState.toSaved() = SavedReview(kind, merchant, date, totalKyat, lines.map { it.toSaved() })

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
        saved.remove<SavedReview>(REVIEW)
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
                        // The AI read failed (the server says to try again): resending the same photo can work.
                        result.code == "scan_failed" -> ScanProblem.SERVER
                        result.retryable -> ScanProblem.SERVER
                        else -> ScanProblem.UNREADABLE
                    },
                )
            }
        }
    }

    /** A chosen photo that could not be opened as an image: the couldn't-read card, nothing sent. */
    fun cannotOpen() {
        photo = null
        fail(ScanProblem.UNREADABLE)
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

    /**
     * A receipt line under another category; it joins the line already there for that category, if any, unless the
     * two together have more items than an entry keeps. Then it stays a line of its own, so no item is cut.
     */
    fun move(index: Int, category: String) = _state.update { s ->
        val line = s.lines.getOrNull(index) ?: return@update s
        if (line.category == category) return@update s
        val moved = line.copy(category = category)
        val rest = s.lines.filterIndexed { i, _ -> i != index }
        val joinAt = rest.indexOfFirst {
            it.type == moved.type && it.category == category && it.items.size + moved.items.size <= TransactionRepository.MAX_ITEMS
        }
        val lines = if (joinAt < 0) {
            s.lines.toMutableList().also { it[index] = moved }
        } else {
            rest.toMutableList().also {
                val into = it[joinAt]
                it[joinAt] = into.copy(
                    originalKyat = into.originalKyat + moved.originalKyat,
                    items = into.items + moved.items,
                    originalItems = into.originalItems + moved.originalItems,
                )
            }
        }
        s.copy(lines = lines, saveFailed = false)
    }

    /** The part of the receipt's total its lines miss, as an Other line (or added to the Other line there). */
    fun addShortAsOther() = _state.update { s ->
        val short = s.shortBy ?: return@update s
        val at = s.lines.indexOfFirst { it.type == TxType.EXPENSE && it.category == OTHER }
        val lines = if (at >= 0) {
            s.lines.toMutableList().also { it[at] = it[at].copy(originalKyat = it[at].originalKyat + short) }
        } else {
            s.lines + ReviewLine(TxType.EXPENSE, short, OTHER, s.merchant ?: OTHER, s.receiptDate, null, emptyList())
        }
        s.copy(lines = lines, saveFailed = false)
    }

    /** A KPay history row in or out. */
    fun toggle(index: Int) = edit(index) { it.copy(kept = !it.kept) }

    /** How many of a receipt item were bought: the item at [item] in the line at [line]; 0 leaves it out. */
    fun setCount(line: Int, item: Int, count: Int) = edit(line) { it.withCount(item, count) }

    /** A receipt item's price each, as typed. */
    fun setPrice(line: Int, item: Int, price: Long) = edit(line) { it.withPrice(item, price) }

    private fun edit(index: Int, change: (ReviewLine) -> ReviewLine) = _state.update { s ->
        val line = s.lines.getOrNull(index) ?: return@update s
        s.copy(lines = s.lines.toMutableList().also { it[index] = change(line) }, saveFailed = false)
    }

    /**
     * Each kept line as its own entry. [describe] writes a receipt line's description in the app's language. A
     * receipt's entries all take [ScanUiState.receiptDate]; a KPay row keeps its own date.
     */
    fun addEntries(describe: (ReviewLine) -> String) {
        val s = _state.value
        if (!s.canSave) return
        val receipt = s.receiptOrNull()
        save(
            s.keptLines.map {
                if (receipt == null) it.toNew(it.description, null) else it.toNew(describe(it), receipt, s.receiptDate)
            },
        )
    }

    /**
     * The whole receipt as one entry, under its largest category, for [description] (the shop's name): at what the kept
     * lines come to, the same [ScanUiState.shownTotal] Add would save as separate entries.
     */
    fun addAsOne(description: String) {
        val s = _state.value
        if (!s.canSave || !s.canAddAsOne) return
        val largest = s.keptLines.maxBy { it.amountKyat }
        val items = s.keptLines.flatMap { it.savedItems }
        val all = largest.copy(originalKyat = s.shownTotal, clientId = null, items = items, originalItems = items)
        save(listOf(all.toNew(description, s.receiptOrNull(), s.receiptDate)))
    }

    /** A receipt's entries share a new id and keep the shop; a KPay slip's payments are separate entries. */
    private fun ScanUiState.receiptOrNull(): Pair<String, String?>? =
        if (kind == ScanKind.RECEIPT) UUID.randomUUID().toString() to merchant else null

    private fun ReviewLine.toNew(description: String, receipt: Pair<String, String?>?, date: LocalDate = this.date) = NewTransaction(
        type = type,
        amountKyat = amountKyat,
        category = if (type == TxType.INCOME) CategoryRepository.INCOME else category,
        // Prepared before it is cut, as the repository checks the prepared length (Zawgyi to Unicode can lengthen it).
        description = BurmeseTextNormalizer.prepare(description).ifEmpty { category }.take(TransactionRepository.MAX_DESCRIPTION),
        date = date,
        source = Source.SCAN,
        transcript = null,
        clientId = clientId,
        receiptId = receipt?.first,
        merchant = receipt?.second,
        items = if (receipt != null) savedItems else emptyList(),
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
            savedEntries = true
            saved.remove<SavedReview>(REVIEW)
            photo = null
            _events.send(ScanEvent.Saved(ids, _state.value.merchant))
        }
    }

    companion object {
        const val OTHER = "Other"

        /** Where [saved] keeps the review. */
        private const val REVIEW = "scan_review"
    }
}
