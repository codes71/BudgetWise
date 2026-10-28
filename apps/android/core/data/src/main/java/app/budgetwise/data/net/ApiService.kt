package app.budgetwise.data.net

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Mobile REST API (shared contract, base `/api/v1`). `voice/parse` is deferred with voice and is
 * intentionally absent in M1.
 *
 * Every call except [login] takes the `Authorization` value of the account it is made for ([authorization]): with
 * several accounts on the phone, an inactive account pushes its outbox with its own token, never the active one's.
 */
interface ApiService {
    @POST("auth/token")
    suspend fun login(@Body body: LoginBody): TokenResponse

    @GET("categories")
    suspend fun categories(@Header(AUTHORIZATION) auth: String): CategoriesResponse

    @GET("transactions")
    suspend fun transactions(@Header(AUTHORIZATION) auth: String, @Query("updatedSince") since: String?): TransactionsResponse

    @POST("transactions")
    suspend fun saveTransactions(@Header(AUTHORIZATION) auth: String, @Body body: TransactionsRequest): SavedResponse

    @DELETE("transactions/{id}")
    suspend fun delete(@Header(AUTHORIZATION) auth: String, @Path("id") id: String): Response<Unit>

    @GET("budgets")
    suspend fun budgets(@Header(AUTHORIZATION) auth: String): BudgetsResponse
}

private const val AUTHORIZATION = "Authorization"

/** The `Authorization` header value for [token]. */
fun authorization(token: String): String = "Bearer $token"

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
