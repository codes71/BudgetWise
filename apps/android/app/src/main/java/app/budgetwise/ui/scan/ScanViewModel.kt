package app.budgetwise.ui.scan

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.CategorySource
import app.budgetwise.NetworkStatus
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
import app.budgetwise.data.repo.ScanReason
import app.budgetwise.data.repo.ScanReading
import app.budgetwise.data.repo.ScanRepository
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Why a photo gave nothing to check. */
enum class ScanProblem {
    /** A receipt or KPay screen the AI could not read (blurred, cut off), or nothing on it could be used. */
    UNREADABLE,

    /** The server saw no receipt or KPay screen in the photo at all: a computer screen, a document, a person. */
    NOT_A_RECEIPT,

    /** More than one receipt in the photo; each must be scanned separately. */
    MULTIPLE_RECEIPTS,

    /** Another wallet or bank app, which the scan route does not support. */
    UNSUPPORTED_WALLET,

    /** BudgetWise keeps kyat only; no foreign amount is offered for saving. */
    FOREIGN_CURRENCY,

    /** The receipt's total is readable, but there are no usable entries or items. */
    TOTAL_ONLY,

    /** A camera photo with no bright part at all ([PhotoPrep.tooDark]). Nothing was sent. */
    TOO_DARK,

    /** A chosen picture far taller than a phone screen ([PhotoPrep.tooLong]). Nothing was sent. */
    TOO_LONG,

    /** A chosen photo that could not be opened as an image. Nothing was sent. */
    CANNOT_OPEN,

    /** No connection: reading needs the server. The photo is kept for Try again. */
    NO_INTERNET,

    /** Online, but the read took too long or the connection dropped on the way. The photo is kept for Try again. */
    SLOW,

    /** 20 scans an hour. */
    TOO_MANY,

    /** The server failed (5xx), or answered with something the phone cannot use. The photo is kept. */
    SERVER,

    /** The phone closed BudgetWise while the photo was being read. The photo is kept for Try again. */
    INTERRUPTED,

    /** The session has ended; sign in again. */
    SIGN_IN,
}

/**
 * Where the scan is: aiming the camera, getting a photo ready to send (the viewfinder stays, under a busy card), waiting
 * on the server, a problem card, or checking what was read.
 */
enum class ScanStage { CAMERA, PREPARING, READING, PROBLEM, REVIEW }

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
    val currency: String? = null,
    val lines: List<ReviewLine> = emptyList(),
    val categories: List<String> = CategoryRepository.BUILT_IN,
    val myanmarDigits: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    /** The photo being read or last read; the screen shows it behind the reading and problem cards. */
    val photo: PreparedPhoto? = null,
    /** The read has taken long enough for the reading card to say it can take up to a minute. */
    val slow: Boolean = false,
    /** The last shutter press gave no photo; the camera says so. */
    val captureFailed: Boolean = false,
    /** On [ScanProblem.TOO_MANY]: the minutes until scanning opens again, when the server said; Try again waits for it. */
    val waitMinutes: Int? = null,
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
 * offline-first like the add form. The photo stays until the scan ends, so Try again needs no new photo.
 *
 * Getting a photo ready and reading it run here, not in the screen, so turning the phone does not lose them. A read is
 * cut off after [READ_LIMIT], and the card says it can take a while after [SLOW_NOTICE].
 *
 * Process death: the scan's stage, problem and review (with every change) are kept in [SavedStateHandle], and the photo
 * in [ScanPhotos] (too large for the saved state). A read the phone cut off comes back as [ScanProblem.INTERRUPTED],
 * never sent again by itself: each send counts toward the 20 scans an hour.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanner: ReceiptScanner,
    private val transactions: TransactionStore,
    categories: CategorySource,
    settings: SettingsStore,
    private val network: NetworkStatus,
    private val photos: ScanPhotos,
    private val saved: SavedStateHandle,
    @DeviceClock clock: Clock,
) : ViewModel() {
    private val keptPhoto = photos.begin(saved.get<String>(KEY_PHOTO_ID))
    private val sameOwner = keptPhoto.owner != null && saved.get<String>(KEY_PHOTO_OWNER) == keptPhoto.owner
    init {
        if (!sameOwner) saved.keys().filter { it in setOf(KEY_SCAN, KEY_PHOTO, KEY_PHOTO_ID, KEY_PHOTO_OWNER) }
            .forEach { saved.remove<Any>(it) }
        saved[KEY_PHOTO_ID] = keptPhoto.id
        saved[KEY_PHOTO_OWNER] = keptPhoto.owner
    }
    private val _state = MutableStateFlow(restore(ScanUiState(today = LocalDate.now(clock))))
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events: Flow<ScanEvent> = _events.receiveAsFlow()

    /** The photo being read or last read. */
    val photo: PreparedPhoto? get() = _state.value.photo

    /** Getting a photo ready, the read, and the too-many countdown; [cancelWork] stops all three. */
    private var preparing: Job? = null
    private var reading: Job? = null
    private var waiting: Job? = null

    /** Set once the entries are saved: nothing about this scan is kept after that. */
    private var ended = false

    init {
        viewModelScope.launch {
            categories.categories.collect { names ->
                _state.update { it.copy(categories = names.filter { n -> n != CategoryRepository.INCOME }) }
            }
        }
        viewModelScope.launch { settings.myanmarDigits.collect { on -> _state.update { it.copy(myanmarDigits = on) } } }
        if (saved.get<Boolean>(KEY_PHOTO) == true) {
            viewModelScope.launch {
                val restoring = keptPhoto.id
                val kept = keptPhoto.kept()
                if (keptPhoto.id != restoring || !keptPhoto.active) return@launch
                if (kept != null) {
                    _state.update { it.copy(photo = kept) }
                } else {
                    saved.remove<Boolean>(KEY_PHOTO)
                    // Try again needs the photo; without it, a new one.
                    if (_state.value.stage == ScanStage.PROBLEM && _state.value.problem in NEEDS_PHOTO) retake()
                }
            }
        } else {
            // A photo left by a scan the phone closed, which the user never came back to.
            keptPhoto.drop()
        }
        viewModelScope.launch {
            _state.map { it.toSaved() }.distinctUntilChanged().collect { if (!ended) saved[KEY_SCAN] = it }
        }
    }

    /** A camera photo, upright, on the main thread. One too dark to read is not sent. */
    fun captured(bitmap: Bitmap) = prepare(onError = ::captureFailed) {
        if (photos.tooDark(bitmap)) Prepared.Refused(ScanProblem.TOO_DARK) else Prepared.Ready(photos.prepare(bitmap))
    }

    /** A photo chosen from the phone. */
    fun picked(uri: Uri) = prepare(onError = { fail(ScanProblem.CANNOT_OPEN) }) {
        when (val opened = photos.open(uri)) {
            is OpenedPhoto.Photo -> Prepared.Ready(photos.prepare(opened.bitmap))
            OpenedPhoto.TooLong -> Prepared.Refused(ScanProblem.TOO_LONG)
            OpenedPhoto.CannotOpen -> Prepared.Refused(ScanProblem.CANNOT_OPEN)
        }
    }

    /** A photo ready to send, or why it is not sent. */
    private sealed interface Prepared {
        class Ready(val photo: PreparedPhoto) : Prepared

        class Refused(val problem: ScanProblem) : Prepared
    }

    /** The shutter gave no photo (the camera failed, or the capture could not be turned into one). */
    fun captureFailed() = _state.update {
        if (it.stage == ScanStage.CAMERA || it.stage == ScanStage.PREPARING) {
            it.copy(stage = ScanStage.CAMERA, captureFailed = true)
        } else {
            it
        }
    }

    /**
     * Gets a photo ready to send with [work], then reads it; [onError] when [work] fails. Only from the camera or a
     * problem card: a second photo while one is on its way is ignored.
     */
    private fun prepare(onError: () -> Unit, work: suspend () -> Prepared) {
        val stage = _state.value.stage
        if (stage != ScanStage.CAMERA && stage != ScanStage.PROBLEM) return
        cancelWork()
        _state.update { it.copy(stage = ScanStage.PREPARING, problem = null, photo = null, captureFailed = false) }
        preparing = viewModelScope.launch {
            val outcome = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Out of memory on a huge picture, or a photo that could not be encoded.
                null
            }
            when (outcome) {
                is Prepared.Ready -> {
                    keep(outcome.photo)
                    if (!keptPhoto.active) {
                        retake()
                        return@launch
                    }
                    _state.update { it.copy(photo = outcome.photo) }
                    readPhoto()
                }
                is Prepared.Refused -> fail(outcome.problem)
                null -> onError()
            }
        }
    }

    /** On disk too, for after process death. A full disk only means the photo would not survive that. */
    private suspend fun keep(prepared: PreparedPhoto) {
        try {
            keptPhoto.keep(prepared.bytes)
            saved[KEY_PHOTO_ID] = keptPhoto.id
            saved[KEY_PHOTO] = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            saved.remove<Boolean>(KEY_PHOTO)
        }
    }

    /** Reads [prepared] (tests, and a photo already prepared). */
    fun read(prepared: PreparedPhoto) {
        cancelWork()
        _state.update { it.copy(photo = prepared) }
        readPhoto()
    }

    /** Reads the kept photo again (No internet, Server, Slow); without one, back to the camera. */
    fun tryAgain() = if (photo != null) readPhoto() else retake()

    /** Drops the photo and the reply: back to the camera. Cancels getting a photo ready or a read in progress. */
    fun retake() {
        cancelWork()
        dropPhoto()
        _state.update { ScanUiState(today = it.today, categories = it.categories, myanmarDigits = it.myanmarDigits) }
    }

    private fun cancelWork() {
        preparing?.cancel()
        preparing = null
        reading?.cancel()
        reading = null
        waiting?.cancel()
        waiting = null
    }

    private fun dropPhoto() {
        saved.remove<Boolean>(KEY_PHOTO)
        keptPhoto.drop()
    }

    private fun readPhoto() {
        if (!keptPhoto.active) {
            retake()
            return
        }
        val owner = keptPhoto.owner ?: return
        val prepared = photo ?: return
        reading?.cancel()
        waiting?.cancel()
        _state.update { it.copy(stage = ScanStage.READING, problem = null, slow = false, waitMinutes = null, currency = null) }
        reading = viewModelScope.launch {
            val notice = launch {
                delay(SLOW_NOTICE)
                _state.update { it.copy(slow = true) }
            }
            // The whole read, upload and AI together: a slow upload that keeps moving never hits a socket timeout.
            val result = withTimeoutOrNull(READ_LIMIT) {
                try {
                    scanner.scan(prepared.bytes, prepared.mediaType, owner)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A Keystore failure reading the token, not an answer from the server.
                    ApiResult.Failure("local", "Could not send the photo", retryable = true)
                }
            }
            notice.cancel()
            if (!keptPhoto.active) {
                retake()
                return@launch
            }
            when (result) {
                null -> fail(ScanProblem.SLOW)
                is ApiResult.Ok -> show(result.v)
                is ApiResult.Unauthorized -> fail(ScanProblem.SIGN_IN)
                is ApiResult.Failure -> {
                    val problem = problemFor(result)
                    fail(problem)
                    if (problem == ScanProblem.TOO_MANY) result.retryAfterSec?.let(::waitOut)
                }
            }
        }
    }

    private suspend fun problemFor(failure: ApiResult.Failure): ScanProblem = when (failure.code) {
        // Every IOException is "network": a timeout or a reply cut off on a phone that is online is a slow connection.
        NETWORK_ERROR_CODE -> if (online()) ScanProblem.SLOW else ScanProblem.NO_INTERNET
        ScanRepository.RATE_LIMITED, "http_429" -> ScanProblem.TOO_MANY
        // A photo the server would not take; this phone always sends a JPEG under the limit, so it is an odd photo.
        "invalid_image", "image_too_large" -> ScanProblem.UNREADABLE
        // The AI read failed (scan_failed), the server is down, or its reply was not one the phone can use (an HTML
        // page from a firewall, a deployment without /scan): none of these is the photo's fault, and it is kept.
        else -> ScanProblem.SERVER
    }

    private suspend fun online(): Boolean = try {
        network.online.first()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Counts down the minutes on the too-many card; Try again comes back when they are up. */
    private fun waitOut(seconds: Long) {
        waiting?.cancel()
        waiting = viewModelScope.launch {
            var left = seconds
            while (left > 0) {
                val minutes = (left + 59) / 60
                _state.update { it.copy(waitMinutes = minutes.toInt()) }
                val step = left - (minutes - 1) * 60
                delay(step.seconds)
                left -= step
            }
            _state.update { it.copy(waitMinutes = null) }
        }
    }

    private fun fail(problem: ScanProblem) =
        _state.update { it.copy(stage = ScanStage.PROBLEM, problem = problem, slow = false, waitMinutes = null) }

    private fun show(reading: ScanReading) {
        val problem = when {
            reading.kind == ScanKind.UNKNOWN -> when (reading.reason) {
                ScanReason.NOT_MONEY -> ScanProblem.NOT_A_RECEIPT
                ScanReason.MULTIPLE -> ScanProblem.MULTIPLE_RECEIPTS
                ScanReason.UNSUPPORTED -> ScanProblem.UNSUPPORTED_WALLET
                ScanReason.FOREIGN_CURRENCY -> ScanProblem.FOREIGN_CURRENCY
                else -> if (reading.notMoney) ScanProblem.NOT_A_RECEIPT else ScanProblem.UNREADABLE
            }
            reading.entries.isEmpty() && reading.kind == ScanKind.RECEIPT && reading.totalKyat != null -> ScanProblem.TOTAL_ONLY
            reading.entries.isEmpty() -> ScanProblem.UNREADABLE
            else -> null
        }
        _state.update {
            it.copy(
                stage = if (problem == null) ScanStage.REVIEW else ScanStage.PROBLEM,
                problem = problem,
                slow = false,
                kind = reading.kind,
                merchant = reading.merchant,
                date = reading.date,
                // A foreign currency amount must never become an amount in kyat, even from a malformed reply.
                totalKyat = reading.totalKyat.takeUnless { reading.kind == ScanKind.UNKNOWN },
                currency = reading.currency,
                lines = if (problem == null) reading.entries.map { entry ->
                    ReviewLine(entry.type, entry.amountKyat, entry.category, entry.description, entry.date, entry.clientId, entry.items)
                } else emptyList(),
            )
        }
    }

    /** The user explicitly chooses to review the printed total as one expense, with no invented items. */
    fun reviewTotal() = _state.update { s ->
        val total = s.totalKyat?.takeIf { it in 1..MAX_KYAT } ?: return@update s
        if (s.problem != ScanProblem.TOTAL_ONLY || s.kind != ScanKind.RECEIPT) return@update s
        s.copy(
            stage = ScanStage.REVIEW,
            problem = null,
            lines = listOf(ReviewLine(TxType.EXPENSE, total, OTHER, s.merchant ?: OTHER, s.receiptDate, null, emptyList())),
        )
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
        if (!keptPhoto.active) {
            retake()
            return
        }
        val owner = keptPhoto.owner ?: return
        _state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val ids = try {
                transactions.addAllForOwner(owner, inputs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Room failed; nothing was saved (one transaction). The review stays for another try.
                _state.update { it.copy(saving = false, saveFailed = true) }
                return@launch
            }
            // Before Saved: a kill from here on must not bring back a review that is already saved.
            ended = true
            saved.remove<String>(KEY_SCAN)
            dropPhoto()
            saved.remove<String>(KEY_PHOTO_ID)
            saved.remove<String>(KEY_PHOTO_OWNER)
            _state.update { it.copy(photo = null) }
            _events.send(ScanEvent.Saved(ids, _state.value.merchant))
        }
    }

    override fun onCleared() {
        // Scanning was closed: the receipt photo goes. Not called on process death, when it must stay.
        keptPhoto.drop()
    }

    /** The scan as it was when the phone closed BudgetWise, if it was past the camera. */
    private fun restore(fresh: ScanUiState): ScanUiState {
        val s = saved.get<String>(KEY_SCAN)?.let { fresh.restoredFrom(it) } ?: return fresh
        return when (s.stage) {
            ScanStage.CAMERA, ScanStage.PREPARING -> fresh
            // The request died with the process; sending it again is the user's call.
            ScanStage.READING -> s.copy(stage = ScanStage.PROBLEM, problem = ScanProblem.INTERRUPTED)
            ScanStage.PROBLEM -> if (s.problem == null) fresh else s
            ScanStage.REVIEW -> if (s.lines.isEmpty()) fresh else s
        }
    }

    companion object {
        const val OTHER = "Other"

        /** How long a read runs before the card says it can take up to a minute. */
        val SLOW_NOTICE = 15.seconds

        /**
         * The most a whole read may take, upload included: the server's own limit is 60 s, so past this the connection
         * is the problem.
         */
        val READ_LIMIT = 90.seconds

        /** The problems whose Try again sends the same photo. */
        private val NEEDS_PHOTO = setOf(
            ScanProblem.NO_INTERNET, ScanProblem.SLOW, ScanProblem.TOO_MANY, ScanProblem.SERVER, ScanProblem.INTERRUPTED,
        )

        // SavedStateHandle keys: the scan as one JSON string, and whether ScanPhotos holds its photo.
        private const val KEY_SCAN = "scan"
        private const val KEY_PHOTO = "photoKept"
        private const val KEY_PHOTO_ID = "photoScanId"
        private const val KEY_PHOTO_OWNER = "photoOwner"
    }
}

/**
 * What [ScanUiState] keeps across process death, as JSON (Bundle types only, and no serialization plugin in :app); the
 * lines with every change, as [linesJson] writes them. Not kept: [ScanUiState.today], the categories and digits (they
 * come back from the clock and settings), saving, and the photo (in [ScanPhotos]).
 */
private fun ScanUiState.toSaved(): String = buildJsonObject {
    put("stage", stage.name)
    problem?.let { put("problem", it.name) }
    put("kind", kind.name)
    merchant?.let { put("merchant", it) }
    date?.let { put("date", it.toEpochDay()) }
    totalKyat?.let { put("total", it) }
    currency?.let { put("currency", it) }
    put("lines", lines.linesJson())
}.toString()

/** This state with what [json] kept; null when it cannot be read (an older build's, or damaged). */
private fun ScanUiState.restoredFrom(json: String): ScanUiState? = try {
    val o = Json.parseToJsonElement(json).jsonObject
    copy(
        stage = ScanStage.valueOf(o.string("stage")!!),
        problem = o.string("problem")?.let(ScanProblem::valueOf),
        kind = ScanKind.valueOf(o.string("kind")!!),
        merchant = o.string("merchant"),
        date = o["date"]?.jsonPrimitive?.long?.let(LocalDate::ofEpochDay),
        totalKyat = o["total"]?.jsonPrimitive?.long,
        currency = o.string("currency"),
        lines = o["lines"]!!.jsonArray.toLines(),
    )
} catch (_: RuntimeException) {
    // JSON that is not what this build writes (a missing key, a renamed stage): start the scan afresh.
    null
}
