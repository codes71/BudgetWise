package app.budgetwise.data.repo

import androidx.room.withTransaction
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.db.BudgetEntity
import app.budgetwise.data.db.BudgetWiseDb
import app.budgetwise.data.db.toDomain
import app.budgetwise.data.db.toEntity
import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.EndCheck
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.EndCheckBody
import app.budgetwise.data.net.MonthPlanBody
import app.budgetwise.data.net.MonthPlanDto
import app.budgetwise.data.net.MonthPlanResponse
import app.budgetwise.data.net.ShareBody
import app.budgetwise.data.net.authorization
import app.budgetwise.data.net.safeApi
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Month plans are cached in the Room table `budget_month`, per account; the UI observes the active account's cache and
 * [refresh] updates it. Saves need the network and are never queued: a plan is checked against the server's categories
 * and totals, so only the server's answer is written to the cache.
 *
 * [clock] is the UTC clock from [app.budgetwise.data.auth.AuthModule]; "this month" is its instant in the zone the phone
 * is set to at the time of the call, so a changed time zone counts from then on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MonthPlanRepository @Inject constructor(
    private val db: BudgetWiseDb,
    private val api: ApiService,
    private val session: SessionManager,
    private val clock: Clock,
    private val writes: CacheWrites,
) {
    private val dao = db.monthPlanDao()

    fun observe(month: YearMonth): Flow<MonthPlan?> = session.activeUserId.flatMapLatest { owner ->
        if (owner == null) flowOf(null) else dao.observe(owner, month).map { it?.toDomain() }
    }

    /** The active account's cached plans from [from] to [to], both included, oldest first. */
    fun observeRange(from: YearMonth, to: YearMonth): Flow<List<MonthPlan>> = session.activeUserId.flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else dao.observeRange(owner, from, to).map { rows -> rows.map { it.toDomain() } }
    }

    /**
     * Replaces the active account's cached months [from] to [to] with the server's plans for them, so a month the server
     * has no plan for is removed. On any failure the cache is left as it was; without a valid token nothing is sent.
     * An answer that a save or an end check overtook (saved while it was on its way) is dropped: the cache already has
     * something newer.
     */
    suspend fun refresh(from: YearMonth, to: YearMonth): ApiResult<Unit> {
        val owner = session.activeUserId.value ?: return NOT_SIGNED_IN
        val token = session.bearerFor(owner) ?: return NOT_SIGNED_IN
        val since = writes.generation(owner, CacheWrites.Table.MONTH_PLANS)
        return when (val r = safeApi { api.monthPlans(authorization(token), from.toString(), to.toString()) }) {
            is ApiResult.Ok -> {
                val plans = r.v.months.mapNotNull(::planOf).filter { it.month in from..to }.distinctBy { it.month }
                writes.writeIfUnchanged(owner, CacheWrites.Table.MONTH_PLANS, since) {
                    dao.replaceRange(owner, from, to, plans.map { it.toEntity(owner) })
                }
                ApiResult.Ok(Unit)
            }
            is ApiResult.Unauthorized -> {
                session.onUnauthorized(owner, token)
                r
            }
            is ApiResult.Failure -> r
        }
    }

    /** Creates or replaces [plan]'s month on the server; its end check there is kept, so [MonthPlan.endCheck] is not sent. */
    suspend fun save(plan: MonthPlan, expectedOwner: String? = session.writableUserId): ApiResult<MonthPlan> = send(plan.month, expectedOwner) { auth ->
        val body = MonthPlanBody(plan.newMoneyKyat, plan.carriedOverKyat, plan.shares.map { ShareBody(it.category, it.limitKyat) })
        api.saveMonthPlan(auth, plan.month.toString(), body)
    }

    suspend fun saveEndCheck(month: YearMonth, onHandKyat: Long, expectedOwner: String? = session.writableUserId): ApiResult<MonthPlan> = send(month, expectedOwner) { auth ->
        api.saveEndCheck(auth, month.toString(), EndCheckBody(onHandKyat))
    }

    /**
     * Sends a write for [month] with the active account's token and caches the plan the server answers with, for the
     * account that sent it even if another one became active meanwhile. A failure writes nothing.
     */
    private suspend fun send(month: YearMonth, expectedOwner: String?, call: suspend (auth: String) -> MonthPlanResponse): ApiResult<MonthPlan> {
        val owner = expectedOwner ?: return NOT_SIGNED_IN
        if (session.writableUserId != owner) return NOT_SIGNED_IN
        val token = session.bearerFor(owner) ?: return NOT_SIGNED_IN
        return when (val r = safeApi { call(authorization(token)) }) {
            is ApiResult.Ok -> {
                val saved = planOf(r.v.month)?.takeIf { it.month == month } ?: return BAD_RESPONSE
                cache(owner, saved)
                ApiResult.Ok(saved)
            }
            is ApiResult.Unauthorized -> {
                session.onUnauthorized(owner, token)
                r
            }
            is ApiResult.Failure -> r
        }
    }

    /**
     * The server mirrors this month's plan into the old per-category budgets, so the legacy cache gets its shares too
     * and voice answers about budgets agree with the plan. Another month leaves it alone.
     */
    private suspend fun cache(owner: String, plan: MonthPlan) {
        val thisMonth = plan.month == currentMonth()
        val tables = if (thisMonth) listOf(CacheWrites.Table.MONTH_PLANS, CacheWrites.Table.BUDGETS) else listOf(CacheWrites.Table.MONTH_PLANS)
        writes.writeSaved(owner, tables) {
            db.withTransaction {
                dao.upsert(plan.toEntity(owner))
                if (thisMonth) {
                    db.budgetDao().replaceAll(
                        owner,
                        plan.shares.map { BudgetEntity(ownerId = owner, category = it.category, limitKyat = it.limitKyat) },
                    )
                }
            }
        }
    }

    private fun currentMonth(): YearMonth = YearMonth.now(clock.withZone(ZoneId.systemDefault()))

    internal companion object {
        private val NOT_SIGNED_IN = ApiResult.Unauthorized("No valid session")
        private val BAD_RESPONSE = ApiResult.Failure("bad_response", "Unexpected server response", retryable = false)

        /**
         * Whole kyat like [BudgetRepository.toKyat]. A plan with a bad month, a bad amount or a total that is not above 0
         * is dropped; so is a share whose limit does not round to at least 1 Ks (the server would refuse it on the next
         * save) or that repeats a category. An end check that cannot be read counts as none.
         */
        fun planOf(dto: MonthPlanDto): MonthPlan? {
            val month = try {
                YearMonth.parse(dto.month)
            } catch (_: DateTimeParseException) {
                return null
            }
            val newMoneyKyat = BudgetRepository.toKyat(dto.newMoney) ?: return null
            val carriedOverKyat = BudgetRepository.toKyat(dto.carriedOver) ?: return null
            if (newMoneyKyat + carriedOverKyat <= 0) return null
            val shares = dto.shares
                .mapNotNull { s -> BudgetRepository.toKyat(s.limit)?.takeIf { it > 0 }?.let { Budget(s.category, it) } }
                .distinctBy { it.category }
            val endCheck = dto.endCheck?.let { e ->
                val onHand = BudgetRepository.toKyat(e.onHand)
                val at = try {
                    Instant.parse(e.checkedAt)
                } catch (_: DateTimeParseException) {
                    null
                }
                if (onHand != null && at != null) EndCheck(onHand, at) else null
            }
            return MonthPlan(month, newMoneyKyat, carriedOverKyat, shares, endCheck)
        }
    }
}
