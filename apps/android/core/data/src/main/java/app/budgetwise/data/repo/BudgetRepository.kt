package app.budgetwise.data.repo

import app.budgetwise.data.auth.SessionManager
import app.budgetwise.data.db.BudgetDao
import app.budgetwise.data.db.BudgetEntity
import app.budgetwise.data.db.toDomain
import app.budgetwise.data.model.Budget
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.ApiService
import app.budgetwise.data.net.authorization
import app.budgetwise.data.net.safeApi
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Budgets are cached in the Room table `budget`, per account; the UI observes the active account's cache and [refresh]
 * updates it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class BudgetRepository @Inject constructor(
    private val dao: BudgetDao,
    private val api: ApiService,
    private val session: SessionManager,
) {
    fun observe(): Flow<List<Budget>> = session.activeUserId.flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else dao.observeAll(owner).map { rows -> rows.map { it.toDomain() } }
    }

    /**
     * Replaces the active account's cache with the server list, fetched with that account's token. On any failure the
     * cache is left as it was; without a valid token nothing is sent.
     */
    suspend fun refresh(): ApiResult<Unit> {
        val owner = session.activeUserId.value ?: return NOT_SIGNED_IN
        val token = session.bearerFor(owner) ?: return NOT_SIGNED_IN
        return when (val r = safeApi { api.budgets(authorization(token)) }) {
            is ApiResult.Ok -> {
                dao.replaceAll(
                    owner,
                    r.v.budgets.mapNotNull { dto -> toKyat(dto.limit)?.let { BudgetEntity(ownerId = owner, category = dto.category, limitKyat = it) } },
                )
                ApiResult.Ok(Unit)
            }
            is ApiResult.Unauthorized -> {
                session.onUnauthorized(owner, token)
                r
            }
            is ApiResult.Failure -> r
        }
    }

    internal companion object {
        private val NOT_SIGNED_IN = ApiResult.Unauthorized("No valid session")
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
