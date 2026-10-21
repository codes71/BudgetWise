package app.budgetwise.data.repo

import app.budgetwise.data.db.BudgetDao
import app.budgetwise.data.db.BudgetEntity
import app.budgetwise.data.db.toDomain
import app.budgetwise.data.model.Budget
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.safeApi
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Budgets are cached in the Room table `budget`; the UI observes the cache and [refresh] updates it. */
@Singleton
class BudgetRepository @Inject constructor(
    private val dao: BudgetDao,
    private val api: ApiService,
) {
    fun observe(): Flow<List<Budget>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** Replaces the cache with the server list. On any failure the cache is left as it was. */
    suspend fun refresh(): ApiResult<Unit> = when (val r = safeApi { api.budgets() }) {
        is ApiResult.Ok -> {
            dao.replaceAll(r.v.budgets.mapNotNull { dto -> toKyat(dto.limit)?.let { BudgetEntity(dto.category, it) } })
            ApiResult.Ok(Unit)
        }
        is ApiResult.Unauthorized -> r
        is ApiResult.Failure -> r
    }

    internal companion object {
        private const val MAX_KYAT = 9_007_199_254_740_991.0 // 2^53 - 1, the largest exact JSON integer

        /**
         * The server sends `limit` as a JSON number that may carry a fraction. Whole kyat on device: rounded
         * half up (1500.5 becomes 1501). A negative, non-finite or absurdly large limit is dropped.
         */
        fun toKyat(limit: Double): Long? {
            if (!limit.isFinite() || limit < 0 || limit > MAX_KYAT) return null
            return BigDecimal(limit).setScale(0, RoundingMode.HALF_UP).toLong()
        }
    }
}
