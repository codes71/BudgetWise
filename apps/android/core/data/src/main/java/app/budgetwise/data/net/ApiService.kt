package app.budgetwise.data.net

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Mobile REST API (shared contract, base `/api/v1`). `voice/parse` is deferred with voice and is
 * intentionally absent in M1.
 */
interface ApiService {
    @POST("auth/token")
    suspend fun login(@Body body: LoginBody): TokenResponse

    @GET("categories")
    suspend fun categories(): CategoriesResponse

    @GET("transactions")
    suspend fun transactions(@Query("updatedSince") since: String?): TransactionsResponse

    @POST("transactions")
    suspend fun saveTransactions(@Body body: TransactionsRequest): SavedResponse

    @DELETE("transactions/{id}")
    suspend fun delete(@Path("id") id: String): Response<Unit>

    @GET("budgets")
    suspend fun budgets(): BudgetsResponse
}

@Serializable
data class LoginBody(val email: String, val password: String)

@Serializable
data class UserDto(val id: String, val email: String, val fullName: String? = null)

@Serializable
data class TokenResponse(val token: String, val expiresAt: String, val user: UserDto)

@Serializable
data class CategoriesResponse(val categories: List<String>)

/** `limit` is read as a Double for the same reason as [TransactionDto.amount]. */
@Serializable
data class BudgetDto(val category: String, val limit: Double)

@Serializable
data class BudgetsResponse(val budgets: List<BudgetDto>)
