package app.budgetwise.data.net

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Mobile REST API (shared contract, base `/api/v1`).
 *
 * Every call except [login] and [register] takes the `Authorization` value of the account it is made for ([authorization]): with
 * several accounts on the phone, an inactive account pushes its outbox with its own token, never the active one's.
 */
interface ApiService {
    @POST("auth/token")
    suspend fun login(@Body body: LoginBody): TokenResponse

    /** Creates the account (as the web signup does) and signs it in: the same reply as [login]. 409 email_taken. */
    @POST("auth/register")
    suspend fun register(@Body body: RegisterBody): TokenResponse

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

    /** Plans of the months [from] to [to] (`YYYY-MM`, both included, at most 24), oldest first; only months with one. */
    @GET("budget-months")
    suspend fun monthPlans(
        @Header(AUTHORIZATION) auth: String,
        @Query("from") from: String,
        @Query("to") to: String,
    ): MonthPlansResponse

    /**
     * Creates or replaces the plan of [month] (`YYYY-MM`); the server keeps its end check. 422 `split_over_total` or
     * `unknown_category`.
     */
    @PUT("budget-months/{month}")
    suspend fun saveMonthPlan(
        @Header(AUTHORIZATION) auth: String,
        @Path("month") month: String,
        @Body body: MonthPlanBody,
    ): MonthPlanResponse

    /** 404 `not_found` when [month] has no plan, 422 `month_not_over` before it has ended. Saving again replaces it. */
    @PUT("budget-months/{month}/end-check")
    suspend fun saveEndCheck(
        @Header(AUTHORIZATION) auth: String,
        @Path("month") month: String,
        @Body body: EndCheckBody,
    ): MonthPlanResponse

    /**
     * What the phone's speech models heard, as text (never audio), read into entries or a spending question. Nothing
     * is saved. When Claude fails the server asks OpenAI, so an answer can take up to its 60 s limit.
     */
    @SlowCall(readSeconds = 65)
    @POST("voice/parse")
    suspend fun parseVoice(@Header(AUTHORIZATION) auth: String, @Body body: VoiceParseBody): VoiceParseResponse

    /**
     * Reads a receipt, KPay slip or KPay history screenshot into entries; saves nothing. Uploads up to 3 MB, then an
     * AI read with the same 60 s server limit as voice.
     */
    @SlowCall(readSeconds = 65, writeSeconds = 60)
    @POST("scan")
    suspend fun scan(@Header(AUTHORIZATION) auth: String, @Body body: ScanBody): ScanResponse
}

/**
 * A call that may outlast the client's default timeouts; [createOkHttpClient] gives it [readSeconds], and
 * [writeSeconds] when above 0 (a large upload).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SlowCall(val readSeconds: Int, val writeSeconds: Int = 0)

/** [image] is plain base64 of at most 3 MB of image bytes. */
@Serializable
data class ScanBody(val image: String, val mediaType: String)

@Serializable
data class ScanItemDto(val name: String, val amount: Long, val quantity: Int = 1, val unitPrice: Long? = null)

@Serializable
data class ScanEntryDto(
    val type: String,
    val amount: Long,
    val category: String,
    val description: String,
    val date: String,
    val dateGuessed: Boolean = false,
    val reference: String? = null,
    val clientId: String? = null,
    val items: List<ScanItemDto> = emptyList(),
)

@Serializable
data class ScanResponse(
    val kind: String,
    val merchant: String? = null,
    val date: String? = null,
    val total: Long? = null,
    val entries: List<ScanEntryDto> = emptyList(),
)

private const val AUTHORIZATION = "Authorization"

/** The `Authorization` header value for [token]. */
fun authorization(token: String): String = "Bearer $token"

@Serializable
data class LoginBody(val email: String, val password: String)

@Serializable
data class RegisterBody(val email: String, val password: String, val fullName: String)

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

/**
 * A month's plan on the wire. Amounts are Doubles for the same reason as [BudgetDto.limit]; each share is a category
 * and limit like a [BudgetDto]. `updatedAt` is not read.
 */
@Serializable
data class MonthPlanDto(
    val month: String,
    val newMoney: Double,
    val carriedOver: Double,
    val shares: List<BudgetDto> = emptyList(),
    val endCheck: EndCheckDto? = null,
)

@Serializable
data class EndCheckDto(val onHand: Double, val checkedAt: String)

@Serializable
data class MonthPlansResponse(val months: List<MonthPlanDto>)

@Serializable
data class MonthPlanResponse(val month: MonthPlanDto)

/** Whole kyat; at most 50 shares, each category once. */
@Serializable
data class MonthPlanBody(val newMoney: Long, val carriedOver: Long, val shares: List<ShareBody>)

@Serializable
data class ShareBody(val category: String, val limit: Long)

@Serializable
data class EndCheckBody(val onHand: Long)

/** One to three transcripts of the same words, up to 500 characters each. */
@Serializable
data class VoiceParseBody(val transcripts: List<String>)

/** `intent` is "add", "question" or "other"; read as strings so a new value does not fail the whole reply. */
@Serializable
data class VoiceParseResponse(
    val intent: String,
    val heard: String = "",
    val entries: List<VoiceEntryDto> = emptyList(),
    val question: VoiceQuestionDto? = null,
)

/** `amount` is a Double for the same reason as [TransactionDto.amount]. [dateGuessed]: nothing was said about when. */
@Serializable
data class VoiceEntryDto(
    val type: String,
    val amount: Double,
    val category: String,
    val description: String,
    val date: String,
    val dateGuessed: Boolean = false,
)

/** `metric` is "list", "total" or "over_budget"; a null [category] means all spending. */
@Serializable
data class VoiceQuestionDto(val metric: String, val category: String? = null, val from: String, val to: String)
