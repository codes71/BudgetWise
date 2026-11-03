package app.budgetwise.ui.budgets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.budgetwise.MonthPlanSource
import app.budgetwise.SyncControls
import app.budgetwise.TransactionStore
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.model.Source
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.NETWORK_ERROR_CODE
import app.budgetwise.data.prefs.SettingsStore
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.data.sync.SyncOutcome
import app.budgetwise.di.DeviceClock
import app.budgetwise.nlu.TxType
import app.budgetwise.reminders.MonthEndReminders
import app.budgetwise.ui.common.typedKyatDigits
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The answer to "Do you have about this much on hand?". */
enum class OnHand { YES, NO }

enum class EndCheckError { NETWORK, NOT_OVER, NO_PLAN, SIGN_IN, ENTRY_FAILED, ACCOUNT_CHANGED, OTHER }

/**
 * The pull of the ledger this screen waits for: an entry made on the web and not on the phone yet would otherwise be
 * missing from the month's spending, and come back as money that "went unrecorded".
 */
enum class LedgerPull { RUNNING, DONE, FAILED, SIGN_IN }

data class MonthEndUiState(
    val month: YearMonth,
    /** False until the month's plan has been read from the cache and, when it was not there, asked for. */
    val loaded: Boolean = false,
    val plan: MonthPlan? = null,
    /** Every expense in [month]. */
    val spentKyat: Long = 0,
    val answer: OnHand? = null,
    /** "I have", as typed: plain digits. */
    val onHandDigits: String = "",
    /** Add what went unrecorded as an expense; ticked until the user unticks it. */
    val addGap: Boolean = true,
    val saving: Boolean = false,
    val error: EndCheckError? = null,
    val myanmarDigits: Boolean = false,
    /** Until it is [LedgerPull.DONE] the leftover is not shown and the question cannot be answered. */
    val ledger: LedgerPull = LedgerPull.RUNNING,
) {
    val totalKyat: Long get() = plan?.totalKyat ?: 0L

    /** What is left of the month's budget; negative when the month went over. */
    val leftKyat: Long get() = totalKyat - spentKyat

    /** What should be on hand: the leftover, never below 0. */
    val expectedKyat: Long get() = leftKyat.coerceAtLeast(0)

    /** The amount the check saves: the leftover for Yes, what was typed for No; null until there is one. */
    val onHandKyat: Long?
        get() = when (answer) {
            OnHand.YES -> expectedKyat
            OnHand.NO -> onHandDigits.toLongOrNull()
            null -> null
        }

    /** Expected minus on hand: above 0 went unrecorded, below 0 is more than expected. */
    val gapKyat: Long get() = onHandKyat?.let { expectedKyat - it } ?: 0L

    /** The leftover can be trusted: the plan is there and this screen's pull of the ledger went through. */
    val counted: Boolean get() = loaded && plan != null && ledger == LedgerPull.DONE

    val canGoOn: Boolean get() = counted && onHandKyat != null && !saving

    val nextMonth: YearMonth get() = month.plusMonths(1)
}

sealed interface MonthEndEvent {
    /** The check is saved (and the gap entry added): plan [next]. */
    data class Checked(val next: YearMonth) : MonthEndEvent
}

/** The category and day of the entry for money that went unrecorded: Other, on the month's last day. */
internal const val GAP_CATEGORY = "Other"

/**
 * After a month ends: is what should be left really on hand? Yes saves the leftover as the end check; No saves the
 * amount typed and, when it is less, can add the difference as an expense so the month's numbers match. The check is
 * saved first (online only); the entry is added only after the server has it.
 *
 * The leftover counts only once the ledger was pulled while this screen is open ([LedgerPull]): the check needs the
 * network anyway, and without the pull an entry made on the web would be added a second time as "Not recorded".
 */
@HiltViewModel
class MonthEndViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val plans: MonthPlanSource,
    private val transactions: TransactionStore,
    settings: SettingsStore,
    @DeviceClock clock: Clock,
    private val reminders: MonthEndReminders,
    private val sync: SyncControls,
    private val session: SessionManager,
) : ViewModel() {
    val month: YearMonth = handle.get<String>(ARG_MONTH)?.let(PlanEditorViewModel::parseMonth) ?: YearMonth.now(clock).minusMonths(1)

    private val asked = MutableStateFlow(false)

    // What the user types is written here at once (a text field must read back what it was given); the plan and the
    // month's spending are merged in as Room answers.
    private val _state = MutableStateFlow(MonthEndUiState(month = month))
    val state: StateFlow<MonthEndUiState> = _state.asStateFlow()

    private val _events = Channel<MonthEndEvent>(Channel.BUFFERED)
    val events: Flow<MonthEndEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(
                plans.observe(month),
                transactions.observe().catch { emit(emptyList()) },
                settings.myanmarDigits,
                asked,
            ) { plan, rows, digits, wasAsked -> Loaded(plan, spentIn(rows, month), digits, wasAsked) }
                .collect { loaded ->
                    _state.update {
                        it.copy(
                            // A plan in the cache shows at once; without one, the server is asked before "no budget" shows.
                            loaded = loaded.plan != null || loaded.asked,
                            plan = loaded.plan,
                            spentKyat = loaded.spentKyat,
                            myanmarDigits = loaded.myanmarDigits,
                        )
                    }
                }
        }
        pullLedger()
        viewModelScope.launch {
            try {
                plans.refresh(month, month)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Writing the cache failed (Room); what it has is shown.
            } finally {
                asked.value = true
            }
        }
    }

    private data class Loaded(val plan: MonthPlan?, val spentKyat: Long, val myanmarDigits: Boolean, val asked: Boolean)

    /** Pushes and pulls the active account's ledger and waits for it; [retryPull] runs it again after a failure. */
    private fun pullLedger() {
        _state.update { it.copy(ledger = LedgerPull.RUNNING) }
        viewModelScope.launch {
            val ledger = try {
                when (sync.syncAndWait()) {
                    is SyncOutcome.Success, is SyncOutcome.PermanentFailure -> LedgerPull.DONE
                    SyncOutcome.NeedsLogin -> LedgerPull.SIGN_IN
                    SyncOutcome.RetryLater -> LedgerPull.FAILED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LedgerPull.FAILED
            }
            _state.update { it.copy(ledger = ledger) }
        }
    }

    fun retryPull() {
        if (_state.value.ledger == LedgerPull.FAILED) pullLedger()
    }

    fun onAnswer(answer: OnHand) = _state.update { it.copy(answer = answer, error = null) }

    fun onOnHandChange(text: String) {
        val digits = typedKyatDigits(text) ?: return
        _state.update { it.copy(onHandDigits = digits, error = null) }
    }

    fun onAddGap(add: Boolean) = _state.update { it.copy(addGap = add) }

    /**
     * Saves the end check, then, only once the server has it, adds the unrecorded difference as an expense named
     * [notRecorded] (in the app's language, like the label that offered it). Both run to the end together even if the
     * screen goes away, so the entry is never added without the check or lost after it.
     */
    fun next(notRecorded: String) {
        val s = _state.value
        val onHand = s.onHandKyat ?: return
        if (!s.canGoOn) return
        val gap = s.gapKyat.takeIf { s.addGap && it > 0 }
        // The account the screen was answered for. The check and the entry go to the active account, so each waits
        // until it is sure that is still this one.
        val owner = session.activeUserId.value
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val error = withContext(NonCancellable) {
                if (owner == null) return@withContext EndCheckError.SIGN_IN
                if (session.activeUserId.value != owner) return@withContext EndCheckError.ACCOUNT_CHANGED
                when (val result = plans.saveEndCheckSafely(month, onHand)) {
                    is ApiResult.Ok -> {
                        // The month is checked: its reminder goes, whatever happens to the entry.
                        reminders.checked(month)
                        when {
                            gap == null -> null
                            // Another account became active while the check was saved: its ledger is not this one's.
                            session.activeUserId.value != owner -> EndCheckError.ACCOUNT_CHANGED
                            !addGapEntry(gap, notRecorded) -> EndCheckError.ENTRY_FAILED
                            else -> null
                        }
                    }
                    is ApiResult.Unauthorized -> EndCheckError.SIGN_IN
                    is ApiResult.Failure -> errorFor(result.code)
                }
            }
            _state.update { it.copy(saving = false, error = error) }
            if (error == null) _events.send(MonthEndEvent.Checked(month.plusMonths(1)))
        }
    }

    private suspend fun MonthPlanSource.saveEndCheckSafely(month: YearMonth, onHand: Long) = try {
        saveEndCheck(month, onHand)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The server's answer could not be cached (Room).
        ApiResult.Failure("local", "Could not save the check on the phone", retryable = true)
    }

    /** False when Room could not save it; the check is saved, so Next again saves it again and retries the entry. */
    private suspend fun addGapEntry(gap: Long, description: String): Boolean = try {
        transactions.addAll(
            listOf(NewTransaction(TxType.EXPENSE, gap, GAP_CATEGORY, description, month.atEndOfMonth(), Source.MANUAL, transcript = null)),
        )
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    companion object {
        const val ARG_MONTH = PlanEditorViewModel.ARG_MONTH

        internal fun errorFor(code: String): EndCheckError = when (code) {
            NETWORK_ERROR_CODE -> EndCheckError.NETWORK
            "month_not_over" -> EndCheckError.NOT_OVER
            "not_found" -> EndCheckError.NO_PLAN
            else -> EndCheckError.OTHER
        }
    }
}
