# WS2 Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the BudgetWise Android companion: hold-to-talk Burmese capture, on-device transcription via `:asr`, parsing via `:nlu`, a one-tap review sheet, Room persistence with an outbox, and idempotent sync to the `/api/v1` backend. Fully usable offline except sync.

**Architecture:** Single-activity Compose app (`:app`) over `:core:data` (Room, Retrofit, WorkManager, token store) and `:feature:voice` (recorder, pipeline, review). `:asr` and `:nlu` are consumed only through the shared contract interfaces and replaced by fakes in tests. Every write goes to Room first and enqueues an outbox row; `SyncWorker` drains the outbox in batches of at most 50 and then pulls `updatedSince`.

**Tech Stack:** Kotlin, Gradle Kotlin DSL + version catalog, Compose (BOM), Hilt, Room, WorkManager, Retrofit + OkHttp + kotlinx-serialization, DataStore, Keystore-backed AES-GCM, JUnit4 + Robolectric + Turbine + MockWebServer, Compose UI test.

**Spec:** `docs/superpowers/plans/2026-10-19-android-voice-budget/README.md` (shared contract)

## Decisions for the user

1. **minSdk 26 (Android 8.0).** Recommended. Keystore AES-GCM, `java.time` without desugaring, and `AudioRecord` float PCM (`ENCODING_PCM_FLOAT`, API 23) all work; llama.cpp via NDK supports arm64-v8a on 26+. Myanmar's market is dominated by budget Android 9-13 devices; 26 covers nearly all. Alternative minSdk 24 needs desugaring plus a Keystore fallback. Not verified against current Myanmar share data: UNVERIFIED.
2. **Retrofit 3 + OkHttp, not Ktor.** Recommended: Retrofit is already needed-shaped for six REST calls, has MockWebServer-friendly tests, and Hilt/OkHttp interceptors give clean auth and 401 handling. Ktor would add a second engine for no gain.
3. **JUnit4 (not JUnit5) for Android modules.** Robolectric and Compose UI tests require the JUnit4 runner; JUnit5 would need a community plugin. JUnit5 stays available for pure-JVM `:nlu` (WS4). The brief asked for JUnit5, so confirm.
4. **Default ASR variant.** Raj 0.6B as default (smaller download, runs on 4 GB RAM phones), 1.7B opt-in on settings. Confirm; WS3 owns real sizes.
5. **Cloud fallback copy.** Opt-in toggle off by default; when on, transcript text (never audio) goes to `/voice/parse` only if on-device parse yields zero drafts and the device is online. Confirm that sending transcript text is acceptable.
6. **Edits of synced transactions.** The contract has no PUT. Plan: synced rows are immutable on device; "edit" = delete + re-create with a new clientId. Confirm, or ask WS1 for `PUT /transactions/:id`.

## Global Constraints

- No em dashes in code comments, strings or docs. Burmese strings live in `res/values-my/strings.xml`; English in `values/`.
- Amounts are `Long` kyat everywhere on device; never `Double`. Dates are `LocalDate` and serialize as `YYYY-MM-DD`; timestamps as ISO-8601 UTC `Instant`.
- `clientId` is a UUIDv4 generated once at row creation and never changes; it is the only idempotency key.
- The UI never reads the network; it reads Room `Flow`s. Sync state is a separate `StateFlow`.
- Audio never leaves the device and is never persisted to disk. The `FloatArray` is released after transcription.
- Token lives only in the Keystore-encrypted store; never logged. OkHttp logging is `NONE` in release.
- `SpeechToText`, `ModelManager`, `BurmeseBudgetParser` are injected behind Hilt bindings so fakes replace them in `androidTest` and unit tests. WS2 must compile with fake bindings when `:asr` / `:nlu` are stubs.
- Burmese text: Unicode (Myanmar block) only. Zawgyi is detected but never produced (see Task 13).
- Versions (verified 2026-10-19 unless marked UNVERIFIED):
  - AGP 9.4.0, needs Gradle 9.6.0 and JDK 17, API 37 max: https://developer.android.com/build/releases/gradle-plugin
  - Compose BOM 2026.09.00 (Material3 1.4.0 mapping inferred): https://developer.android.com/develop/ui/compose/bom/bom-mapping
  - Room 2.8.5 (2026-09-09): https://developer.android.com/jetpack/androidx/releases/room
  - WorkManager 2.12.0 (2026-09-23, per search snippet, re-check): https://developer.android.com/jetpack/androidx/releases/work
  - Dagger/Hilt 2.60.1: https://github.com/google/dagger/releases
  - kotlinx-serialization 1.11.0 (built with Kotlin 2.3.20): https://github.com/Kotlin/kotlinx.serialization/releases
  - Retrofit 3.0.0 plus separate `converter-kotlinx-serialization`: https://github.com/square/retrofit/releases
  - Kotlin 2.3.20 (inferred from serialization build): UNVERIFIED. AGP 9 uses built-in Kotlin, so do not apply `org.jetbrains.kotlin.android`.
  - KSP, OkHttp, DataStore, Turbine, Robolectric, MockWebServer versions: UNVERIFIED, pick latest stable at execution and record in `libs.versions.toml`.
  - Hilt and KSP compatibility with AGP 9 built-in Kotlin: UNVERIFIED, Task 1 smoke build must prove it.

## Review Focus

Five failure modes no per-task happy-path test covers, each owned by a named test:

1. **Duplicate rows after a lost response.** POST succeeds server-side, response is dropped, retry re-sends. Test `SyncEngineTest.retryAfterLostResponseDoesNotDuplicate` (Task 6): fake server keyed on clientId, assert one row remote and outbox cleared.
2. **Expired token mid-sync.** A 401 during batch 2 must stop the worker, keep the outbox, and surface "sign in again" without data loss. Test `SyncEngineTest.unauthorizedKeepsOutboxAndFlagsReauth` (Task 6).
3. **Voice draft lost on process death or rotation.** Review sheet state must survive config change and app kill before confirm. Test `ReviewViewModelTest.draftsSurviveSavedStateRestore` (Task 9).
4. **Mic held across interruption.** Incoming call, app backgrounded, or permission revoked while recording must stop capture, discard or finalize cleanly, and release `AudioRecord`. Test `HoldToTalkRecorderTest.releasesOnInterruption` (Task 8).
5. **Zawgyi or mixed-script input corrupting descriptions.** Transcript or typed text containing Zawgyi must not be saved as-is and shown garbled. Test `BurmeseTextNormalizerTest.zawgyiIsConvertedOrFlagged` (Task 13).

## File Structure

```
android/
  settings.gradle.kts            build.gradle.kts           gradle.properties
  gradle/libs.versions.toml      gradle/wrapper/gradle-wrapper.properties
  app/
    build.gradle.kts             src/main/AndroidManifest.xml
    src/main/java/app/budgetwise/
      BudgetWiseApp.kt           MainActivity.kt            nav/AppNav.kt
      di/AppModule.kt            di/AsrNluBindings.kt
      ui/login/LoginScreen.kt    ui/login/LoginViewModel.kt
      ui/transactions/TransactionsScreen.kt  TransactionsViewModel.kt
      ui/manual/AddManualScreen.kt           AddManualViewModel.kt
      ui/budgets/BudgetsScreen.kt            BudgetsViewModel.kt
      ui/settings/SettingsScreen.kt          SettingsViewModel.kt
      ui/theme/Theme.kt          ui/theme/Type.kt
    src/main/res/font/noto_sans_myanmar*.ttf   res/values-my/strings.xml
    src/test/...                 src/androidTest/...
  core/data/
    build.gradle.kts
    src/main/java/app/budgetwise/data/
      model/Models.kt            db/BudgetWiseDb.kt   db/TransactionEntity.kt
      db/OutboxEntity.kt         db/TransactionDao.kt db/OutboxDao.kt
      net/ApiService.kt          net/Dtos.kt          net/AuthInterceptor.kt
      auth/TokenStore.kt         auth/SessionManager.kt
      sync/SyncEngine.kt         sync/SyncWorker.kt   sync/SyncScheduler.kt
      repo/TransactionRepository.kt AuthRepository.kt BudgetRepository.kt
      prefs/SettingsStore.kt     text/BurmeseTextNormalizer.kt
    src/test/...
  feature/voice/
    build.gradle.kts
    src/main/java/app/budgetwise/voice/
      HoldToTalkRecorder.kt      VoicePipeline.kt     ReviewViewModel.kt
      ReviewSheet.kt             VoiceScreen.kt       TranscribeService.kt
      PermissionGate.kt          fakes/FakeSpeechToText.kt FakeParser.kt
    src/test/...
```

## Tasks

### Task 1: Gradle multi-module skeleton and version catalog

**Files:** `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`, `android/core/data/build.gradle.kts`, `android/feature/voice/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/test/java/app/budgetwise/SmokeTest.kt`

**Interfaces:** Consumes: none. Produces: modules `:app`, `:core:data`, `:feature:voice`, and `include(":asr", ":nlu")` pointing at WS3/WS4 directories (stub `build.gradle.kts` with empty fakes only if those directories do not yet exist; do not overwrite theirs).

- [ ] Write failing test `SmokeTest.moduleGraphResolves`: `assertTrue(Class.forName("app.budgetwise.BudgetWiseApp") != null)`.
- [ ] Run `cd android && ./gradlew :app:testDebugUnitTest --tests app.budgetwise.SmokeTest`. Expected FAIL (no project).
- [ ] Implement catalog with the versions above, `minSdk = 26`, `compileSdk = 37`, `targetSdk = 36` (UNVERIFIED target policy), `isMinifyEnabled = true` in release with consumer rules; apply `ksp` + `hilt` plugins; no `kotlin-android` plugin (AGP 9 built-in Kotlin). `abiFilters += "arm64-v8a"` in `:app` (llama.cpp target; WS3 may extend).
- [ ] Implement `BudgetWiseApp : Application` annotated `@HiltAndroidApp`.
- [ ] Run the same command. Expected PASS. Also run `./gradlew assembleDebug` once to prove Hilt+KSP work under AGP 9 (resolves the UNVERIFIED note).
- [ ] Commit: `git add android && git commit -m "feat(android): gradle multi-module skeleton and version catalog"`

### Task 2: Domain models and DTO mappers

**Files:** `android/core/data/src/main/java/app/budgetwise/data/model/Models.kt`, `.../net/Dtos.kt`, `android/core/data/src/test/java/app/budgetwise/data/MappersTest.kt`

**Interfaces:** Consumes: `DraftTransaction`, `TxType` from `:nlu`. Produces:
```kotlin
data class Transaction(val clientId: String, val serverId: String?, val type: TxType, val amountKyat: Long,
  val category: String, val description: String, val date: LocalDate, val source: Source,
  val transcript: String?, val updatedAt: Instant, val syncState: SyncState)
enum class Source { VOICE, MANUAL, WEB }
enum class SyncState { PENDING, SYNCED, FAILED }
@Serializable data class TransactionDto(val id: String, val clientId: String, val type: String, val amount: Long,
  val category: String, val description: String, val date: String, val source: String,
  val transcript: String? = null, val createdAt: String, val updatedAt: String)
@Serializable data class TransactionInput(/* TransactionDto minus id, createdAt, updatedAt */)
fun Transaction.toInput(): TransactionInput
fun TransactionDto.toDomain(): Transaction
```

- [ ] Failing tests: `toInput_mapsEnumsToLowercaseWireValues` (EXPENSE -> "expense", VOICE -> "voice", date "2026-10-19"); `toDomain_roundTripsClientId`; `negativeAmountRejected` (`require(amountKyat > 0)` throws).
- [ ] Run `./gradlew :core:data:testDebugUnitTest --tests app.budgetwise.data.MappersTest`. Expected FAIL.
- [ ] Implement mappers in `Models.kt`/`Dtos.kt`; `Json { ignoreUnknownKeys = true; explicitNulls = false }` shared in `NetModule`.
- [ ] Run again. Expected PASS.
- [ ] Commit: `git add android/core/data && git commit -m "feat(android): domain models and DTO mappers"`

### Task 3: Room schema, DAOs, outbox

**Files:** `.../db/BudgetWiseDb.kt`, `TransactionEntity.kt`, `OutboxEntity.kt`, `TransactionDao.kt`, `OutboxDao.kt`, test `android/core/data/src/test/java/app/budgetwise/data/DaoTest.kt` (Robolectric, in-memory Room)

**Interfaces:** Produces:
```kotlin
@Dao interface TransactionDao {
  @Query("SELECT * FROM tx WHERE deleted = 0 ORDER BY date DESC, updatedAt DESC") fun observeAll(): Flow<List<TransactionEntity>>
  @Upsert suspend fun upsert(e: TransactionEntity)
  @Query("SELECT * FROM tx WHERE clientId = :id") suspend fun byClientId(id: String): TransactionEntity?
}
@Dao interface OutboxDao {
  @Insert suspend fun enqueue(o: OutboxEntity)                       // op: CREATE | DELETE
  @Query("SELECT * FROM outbox ORDER BY seq LIMIT :n") suspend fun head(n: Int): List<OutboxEntity>
  @Query("DELETE FROM outbox WHERE seq IN (:seqs)") suspend fun ack(seqs: List<Long>)
  @Query("UPDATE outbox SET attempts = attempts + 1 WHERE seq IN (:seqs)") suspend fun bump(seqs: List<Long>)
}
```
`tx.clientId` has a unique index; `deleted` is a local tombstone until the server confirms DELETE.

- [ ] Failing tests: `observeAll_ordersNewestFirst`; `enqueue_preservesFifoBySeq`; `upsertSameClientIdReplaces` (no duplicate rows); `tombstonedRowsHiddenFromObserve`.
- [ ] Run `./gradlew :core:data:testDebugUnitTest --tests app.budgetwise.data.DaoTest`. Expected FAIL.
- [ ] Implement entities/DAOs/DB (`exportSchema = true`, schema dir `core/data/schemas`, version 1; no destructive migration fallback).
- [ ] Run again. Expected PASS.
- [ ] Commit: `git add android/core/data && git commit -m "feat(android): room schema with outbox"`

### Task 4: API service, auth interceptor, error mapping

**Files:** `.../net/ApiService.kt`, `AuthInterceptor.kt`, `ApiError.kt`, `NetModule.kt`, test `.../net/ApiServiceTest.kt` (MockWebServer)

**Interfaces:** Produces:
```kotlin
interface ApiService {
  @POST("auth/token") suspend fun login(@Body b: LoginBody): TokenResponse
  @GET("categories") suspend fun categories(): CategoriesResponse
  @GET("transactions") suspend fun transactions(@Query("updatedSince") since: String?): TransactionsResponse
  @POST("transactions") suspend fun saveTransactions(@Body b: SaveBody): SavedResponse
  @DELETE("transactions/{id}") suspend fun delete(@Path("id") id: String): Response<Unit>
  @GET("budgets") suspend fun budgets(): BudgetsResponse
  @POST("voice/parse") suspend fun parse(@Body b: ParseBody): ParseResponse
}
sealed interface ApiResult<out T> { data class Ok<T>(val v: T); data class Unauthorized(val msg: String); data class Failure(val code: String, val msg: String, val retryable: Boolean) }
suspend fun <T> safeApi(block: suspend () -> T): ApiResult<T>
```
Base URL from `BuildConfig.API_BASE_URL` (debug `http://10.0.2.2:3000/api/v1/`, release HTTPS only; cleartext blocked by network security config except debug).

- [ ] Failing tests: `bearerHeaderAddedExceptOnAuthToken`; `http401MapsToUnauthorized`; `errorEnvelopeParsedIntoFailure` (`{error:{code,message}}`); `http5xxAndIOExceptionAreRetryable`; `http400IsNotRetryable`.
- [ ] Run `./gradlew :core:data:testDebugUnitTest --tests app.budgetwise.data.net.ApiServiceTest`. Expected FAIL.
- [ ] Implement with `Retrofit.Builder().addConverterFactory(json.asConverterFactory("application/json".toMediaType()))`; interceptor reads token synchronously from an in-memory cache fed by `TokenStore`.
- [ ] Run again. Expected PASS.
- [ ] Commit: `git add android/core/data && git commit -m "feat(android): retrofit api service and error mapping"`

### Task 5: Token storage and expiry handling

**Files:** `.../auth/TokenStore.kt`, `SessionManager.kt`, test `.../auth/SessionManagerTest.kt`

**Interfaces:** Produces:
```kotlin
interface TokenStore { suspend fun save(token: String, expiresAt: Instant, user: UserInfo); suspend fun load(): StoredSession?; suspend fun clear() }
class KeystoreTokenStore @Inject constructor(ctx: Context) : TokenStore   // AES-GCM key in AndroidKeyStore, ciphertext in DataStore
class SessionManager @Inject constructor(store: TokenStore, clock: Clock) {
  val state: StateFlow<SessionState>   // SignedOut | SignedIn(user) | Expired
  fun bearer(): String?                // null when expired
  suspend fun onUnauthorized()         // clears token, state = Expired, keeps Room data
}
```
Policy: JWT lasts 30 days, no refresh endpoint, so on expiry or 401 the app keeps all local data and the outbox, shows a re-login banner, and sync pauses. Warn banner at 3 days left. Logout clears token but asks before dropping unsynced rows.

- [ ] Failing tests (fake `TokenStore`, `Clock.fixed`): `expiredTokenYieldsExpiredState`; `bearerNullAfterExpiry`; `onUnauthorizedKeepsLocalData`; `warnsWhenUnderThreeDaysLeft`.
- [ ] Run `./gradlew :core:data:testDebugUnitTest --tests app.budgetwise.data.auth.SessionManagerTest`. Expected FAIL.
- [ ] Implement `SessionManager` pure-Kotlin; `KeystoreTokenStore` covered by an instrumented smoke test only (Keystore is not in Robolectric).
- [ ] Run again. Expected PASS.
- [ ] Commit: `git add android/core/data && git commit -m "feat(android): keystore token store and session expiry"`

### Task 6: SyncEngine and SyncWorker

**Files:** `.../sync/SyncEngine.kt`, `SyncWorker.kt`, `SyncScheduler.kt`, test `.../sync/SyncEngineTest.kt`

**Interfaces:** Consumes: `ApiService`, DAOs, `SessionManager`. Produces:
```kotlin
class SyncEngine @Inject constructor(api: ApiService, tx: TransactionDao, outbox: OutboxDao, session: SessionManager, clock: Clock) {
  suspend fun syncOnce(): SyncOutcome   // Success(pushed, pulled) | NeedsLogin | RetryLater | PermanentFailure(ids)
}
@HiltWorker class SyncWorker(...) : CoroutineWorker   // unique work "sync", constraint NetworkType.CONNECTED, backoff EXPONENTIAL 30s
object SyncScheduler { fun enqueue(wm: WorkManager); fun periodic(wm: WorkManager) }  // plus enqueue after every local write
```
Conflict rules (document in KDoc):
- Create: server wins on identical clientId; response `saved[]` overwrites local row (sets `serverId`, `updatedAt`, `SYNCED`).
- Pull merges by `clientId`; a row with a pending outbox op is never overwritten by pull (local intent wins until pushed).
- Delete of a never-synced row: remove locally and drop the outbox CREATE, send nothing.
- Delete of a synced row: tombstone, queue DELETE by `serverId`; 404 counts as success.
- Pull cursor `lastPulledAt` stored in `SettingsStore`; set to max `updatedAt` seen, with 2 minute overlap to tolerate clock skew. Known contract gap: no tombstones on pull, so server-side deletes from the web app do not reach the phone (see Contract notes).
- 4xx other than 401/404/408/429 marks the outbox row FAILED (surfaced in UI), never retried silently.

- [ ] Failing tests with an in-memory fake server: `pushesInBatchesOfFifty` (120 rows -> 3 POSTs); `retryAfterLostResponseDoesNotDuplicate`; `unauthorizedKeepsOutboxAndFlagsReauth`; `pullDoesNotOverwritePendingLocalRow`; `deleteOfUnsyncedRowSendsNothing`; `delete404IsSuccess`; `permanent400MarksFailedAndContinues`.
- [ ] Run `./gradlew :core:data:testDebugUnitTest --tests app.budgetwise.data.sync.SyncEngineTest`. Expected FAIL.
- [ ] Implement engine as above; `SyncWorker` maps outcomes to `Result.success/retry/failure`; `NeedsLogin` returns success (no retry storm) and emits via `SessionManager`.
- [ ] Run again. Expected PASS. Add one `SyncWorkerTest` using `TestListenableWorkerBuilder` asserting `RetryLater -> Result.retry()`.
- [ ] Commit: `git add android/core/data && git commit -m "feat(android): outbox sync engine and worker"`

### Task 7: Repositories and settings store

**Files:** `.../repo/TransactionRepository.kt`, `AuthRepository.kt`, `BudgetRepository.kt`, `.../prefs/SettingsStore.kt`, test `.../repo/TransactionRepositoryTest.kt`

**Interfaces:** Produces:
```kotlin
class TransactionRepository @Inject constructor(...) {
  fun observe(): Flow<List<Transaction>>
  suspend fun addAll(inputs: List<NewTransaction>): List<String>   // returns clientIds; one Room transaction: row + outbox + schedule sync
  suspend fun delete(clientId: String)
}
data class NewTransaction(val type: TxType, val amountKyat: Long, val category: String, val description: String,
  val date: LocalDate, val source: Source, val transcript: String?)
class AuthRepository { suspend fun login(email: String, password: String): ApiResult<UserInfo>; suspend fun logout(force: Boolean) }
class BudgetRepository { fun observe(): Flow<List<Budget>>; suspend fun refresh() }   // cached in Room table budget(category, limit)
interface SettingsStore {
  val autoSaveHighConfidence: Flow<Boolean>   // default false
  val cloudFallbackOptIn: Flow<Boolean>       // default false
  val rajVariant: Flow<RajVariant>            // default RAJ_0_6B
  val lastPulledAt: Flow<Instant?>
}
```

- [ ] Failing tests: `addAllWritesRowsAndOutboxAtomically` (force failure on outbox insert, assert zero rows); `addAllGeneratesUniqueUuidV4ClientIds`; `defaultsAreOff` (autoSave false, cloud false).
- [ ] Run `./gradlew :core:data:testDebugUnitTest --tests app.budgetwise.data.repo.TransactionRepositoryTest`. Expected FAIL.
- [ ] Implement with `db.withTransaction`; `SettingsStore` on Preferences DataStore.
- [ ] Run again. Expected PASS.
- [ ] Commit: `git add android/core/data && git commit -m "feat(android): repositories and settings store"`

### Task 8: Hold-to-talk recorder

**Files:** `android/feature/voice/src/main/java/app/budgetwise/voice/HoldToTalkRecorder.kt`, test `.../HoldToTalkRecorderTest.kt` (injectable `AudioSource` fake)

**Interfaces:** Produces:
```kotlin
interface AudioSource { fun start(); fun read(buf: FloatArray): Int; fun stop(); fun release() }   // real impl wraps AudioRecord 16000 Hz, CHANNEL_IN_MONO, ENCODING_PCM_FLOAT
class HoldToTalkRecorder @Inject constructor(source: () -> AudioSource, dispatcher: CoroutineDispatcher) {
  val state: StateFlow<RecorderState>   // Idle | Recording(elapsedMs, level) | TooShort | Failed(reason)
  fun press(); suspend fun release(): FloatArray?   // null if shorter than 400 ms
  fun cancel()                                      // interruption path
}
const val MAX_RECORD_MS = 60_000L
```
Notes: preallocate ring buffer for 60 s (3.84 MB), auto-stop at the cap, clamp samples to -1..1, compute RMS level for the UI meter.

- [ ] Failing tests: `tooShortReturnsNull`; `capsAtSixtySeconds`; `samplesClampedToUnitRange`; `releasesOnInterruption` (call `cancel()` mid-record, assert `release()` called on the source exactly once and state is Idle); `startFailureSurfacesFailedState`.
- [ ] Run `./gradlew :feature:voice:testDebugUnitTest --tests app.budgetwise.voice.HoldToTalkRecorderTest`. Expected FAIL.
- [ ] Implement with a read loop on `Dispatchers.IO`; always `try/finally { source.release() }`.
- [ ] Run again. Expected PASS.
- [ ] Commit: `git add android/feature/voice && git commit -m "feat(android): hold-to-talk recorder"`

### Task 9: Voice pipeline, review state, auto-save rule

**Files:** `.../VoicePipeline.kt`, `.../ReviewViewModel.kt`, `.../fakes/FakeSpeechToText.kt`, `.../fakes/FakeParser.kt`, test `.../ReviewViewModelTest.kt`, `.../VoicePipelineTest.kt`

**Interfaces:** Consumes: `SpeechToText`, `BurmeseBudgetParser` (wrapped as `interface DraftParser { fun parse(t: String, today: LocalDate, cats: List<String>): List<DraftTransaction> }` so the `object` is injectable and fakeable), `TransactionRepository`, `SettingsStore`, `ApiService.parse`. Produces:
```kotlin
class VoicePipeline @Inject constructor(stt: SpeechToText, parser: DraftParser, api: ApiService, settings: SettingsStore, conn: Connectivity, clock: Clock) {
  suspend fun run(audio: FloatArray, categories: List<String>): PipelineResult   // Drafts(transcript, drafts, viaCloud) | NoSpeech | AsrFailed(msg)
}
data class EditableDraft(val id: String, val type: TxType, val amountText: String, val category: String,
  val description: String, val date: LocalDate, val confidence: Float, val included: Boolean)
class ReviewViewModel @Inject constructor(saved: SavedStateHandle, repo: TransactionRepository, settings: SettingsStore) : ViewModel() {
  val ui: StateFlow<ReviewUi>
  fun load(transcript: String, drafts: List<DraftTransaction>)
  fun edit(id: String, f: (EditableDraft) -> EditableDraft); fun toggle(id: String)
  suspend fun confirm(): List<String>        // maps included drafts to NewTransaction(source=VOICE, transcript)
}
```
Rules: default is one-tap confirm. Auto-save only when the setting is on AND every included draft has `confidence >= 0.9`; otherwise show the sheet. Cloud parse only when settings opt-in AND online AND local parse returned zero drafts. Empty transcript -> `NoSpeech`. Amount edit validated as positive integer; invalid blocks confirm. Drafts persisted in `SavedStateHandle` (JSON string) until confirm or dismiss.

- [ ] Failing tests: `defaultNeverAutoSaves` (confidence 0.99, setting off -> sheet shown, repo untouched); `autoSaveOnlyWhenAllAtLeastPointNine` (0.95 and 0.85 -> sheet); `cloudParseSkippedWhenOffline`; `cloudParseSkippedWhenNotOptedIn`; `cloudParseOnlyWhenLocalEmpty`; `confirmMapsToVoiceSourceWithTranscript`; `invalidAmountBlocksConfirm`; `draftsSurviveSavedStateRestore`.
- [ ] Run `./gradlew :feature:voice:testDebugUnitTest --tests "app.budgetwise.voice.*"`. Expected FAIL.
- [ ] Implement; `FakeSpeechToText(text: String)` returns `AsrResult(text, "fake", 1000, 10)`; `FakeParser` returns scripted drafts.
- [ ] Run again. Expected PASS.
- [ ] Commit: `git add android/feature/voice && git commit -m "feat(android): voice pipeline and review state"`

### Task 10: Model manager integration and settings screen state

**Files:** `android/app/src/main/java/app/budgetwise/ui/settings/SettingsViewModel.kt`, `.../di/AsrNluBindings.kt`, test `android/app/src/test/java/app/budgetwise/ui/settings/SettingsViewModelTest.kt` (Turbine)

**Interfaces:** Consumes: `ModelManager`, `RajVariant`, `ModelState`, `SettingsStore`. Produces:
```kotlin
class SettingsViewModel @Inject constructor(models: ModelManager, store: SettingsStore) : ViewModel() {
  val ui: StateFlow<SettingsUi>   // variant, modelState, autoSave, cloudOptIn, wifiOnlyDownload
  fun selectVariant(v: RajVariant); fun download(); fun setAutoSave(b: Boolean); fun setCloudOptIn(b: Boolean)
}
```
Voice button is disabled with an explanatory label until `ModelState.Ready`. Download defaults to Wi-Fi only; a cellular confirm dialog states the size (size from WS3, UNVERIFIED).

- [ ] Failing tests: `voiceDisabledUntilModelReady`; `selectingVariantPersistsAndTriggersEnsureDownloaded`; `cloudOptInDefaultsOff`; `downloadingStateEmitsProgress` (Turbine on `ModelState.Downloading`).
- [ ] Run `./gradlew :app:testDebugUnitTest --tests app.budgetwise.ui.settings.SettingsViewModelTest`. Expected FAIL.
- [ ] Implement with a `FakeModelManager` in test sources.
- [ ] Run again. Expected PASS.
- [ ] Commit: `git add android/app && git commit -m "feat(android): settings and model download state"`

### Task 11: App shell, navigation, login, list, manual add, budgets

**Files:** `android/app/src/main/java/app/budgetwise/nav/AppNav.kt`, `ui/login/*`, `ui/transactions/*`, `ui/manual/*`, `ui/budgets/*`, `MainActivity.kt`, tests `ui/login/LoginViewModelTest.kt`, `ui/manual/AddManualViewModelTest.kt`, `src/androidTest/.../TransactionsScreenTest.kt`

**Interfaces:** Produces routes `login`, `transactions`, `manual`, `budgets`, `settings`, `voice`. Start destination is `login` when `SessionState != SignedIn`. `LoginViewModel.submit(email, password)`; `AddManualViewModel.save()` builds `NewTransaction(source = MANUAL)`; category chips come from `GET /categories`, cached, falling back to the eight built-in names (Groceries, Utilities, Entertainment, Transport, Housing, Health, Food and Drink Item, Other) when offline.

- [ ] Failing tests: `login_successNavigatesAndStoresToken`; `login_wrongPasswordShowsServerMessage`; `manual_rejectsZeroAmount`; `manual_savesOfflineWithPendingState`. Compose UI (`createComposeRule`): `transactionsList_showsPendingBadgeForUnsyncedRow`; `expiredSessionBannerShowsSignInAction`.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests "app.budgetwise.ui.*"`. Expected FAIL. UI tests: `./gradlew :app:connectedDebugAndroidTest` (needs a device; run on the user's machine only, not by the plan author).
- [ ] Implement screens with Material3, MMK formatting via `NumberFormat` + "Ks" suffix (`Myanmar digits` toggle off by default), swipe-to-delete calling `repo.delete`.
- [ ] Run unit tests again. Expected PASS.
- [ ] Commit: `git add android/app && git commit -m "feat(android): app shell and core screens"`

### Task 12: Voice screen, permission flow, foreground behaviour

**Files:** `android/feature/voice/src/main/java/app/budgetwise/voice/VoiceScreen.kt`, `ReviewSheet.kt`, `PermissionGate.kt`, `TranscribeService.kt`, manifest entries, tests `PermissionGateTest.kt`, `src/androidTest/.../ReviewSheetTest.kt`

**Interfaces:** Produces:
```kotlin
sealed interface MicPermission { Granted; NeedsRationale; Denied; PermanentlyDenied }
fun resolveMicPermission(granted: Boolean, shouldShowRationale: Boolean, askedBefore: Boolean): MicPermission
@Composable fun VoiceScreen(onDone: () -> Unit)         // press-and-hold button, level meter, transcript preview
@Composable fun ReviewSheet(ui: ReviewUi, onEdit: ..., onConfirm: () -> Unit, onDismiss: () -> Unit)   // ModalBottomSheet, one primary button "Save all"
class TranscribeService : Service   // foregroundServiceType="shortService"? see below
```
Behaviour: ask `RECORD_AUDIO` on first press with a rationale dialog; permanently denied routes to app settings. Transcription of 60 s audio with the 1.7B model can take well over the recording length on low-end phones. While decoding, run inside a foreground service (type `dataSync` UNVERIFIED, confirm the correct `foregroundServiceType` for on-device inference on API 34+ and the matching permission) with an ongoing notification, so the OS does not kill decoding when the user leaves the app; keep the screen on with `FLAG_KEEP_SCREEN_ON` only while the screen is foreground. Cancel button aborts decode.

- [ ] Failing tests: `permissionGate_firstAskRequestsPermission`; `permissionGate_permanentDenialOpensSettings`; Compose: `reviewSheet_oneTapSavesAllIncludedDrafts`; `reviewSheet_excludedDraftNotSaved`; `reviewSheet_showsBurmeseDescriptionWithoutTruncation`.
- [ ] Run `./gradlew :feature:voice:testDebugUnitTest --tests app.budgetwise.voice.PermissionGateTest`. Expected FAIL. UI tests via `:feature:voice:connectedDebugAndroidTest` on the user's device.
- [ ] Implement; route `HoldToTalkRecorder.cancel()` from `Lifecycle.ON_STOP`, `AudioManager` focus loss and permission revocation (feeds failure mode 4).
- [ ] Run unit tests again. Expected PASS.
- [ ] Commit: `git add android/feature/voice android/app/src/main/AndroidManifest.xml && git commit -m "feat(android): voice screen, review sheet, permission flow"`

### Task 13: Burmese rendering and Zawgyi guard

**Files:** `android/core/data/src/main/java/app/budgetwise/data/text/BurmeseTextNormalizer.kt`, `android/app/src/main/res/font/*`, `ui/theme/Type.kt`, test `android/core/data/src/test/java/app/budgetwise/data/BurmeseTextNormalizerTest.kt`

**Interfaces:** Produces:
```kotlin
object BurmeseTextNormalizer {
  fun looksLikeZawgyi(s: String): Boolean        // heuristic on Zawgyi-only code point sequences, e.g. U+1031 before consonant, U+103B..U+103E ordering
  fun toUnicode(s: String): String               // applied to typed description and transcript before save
  fun normalize(s: String): String               // NFC + trim + collapse whitespace
}
```
Rendering: bundle Noto Sans Myanmar as the Burmese `FontFamily` in `Type.kt` (many budget phones ship Zawgyi-patched or old system fonts, so do not rely on the system font). Android 9+ ships Unicode Myanmar in most builds, but a Zawgyi-rendering device font can still mangle Unicode text; bundling avoids that. Set `lineHeight` about 1.6x because Myanmar stacked diacritics clip at default heights, and never apply letter spacing. Use `TextOverflow.Ellipsis` with `softWrap = true`; avoid `maxLines = 1` on descriptions. Zawgyi is a conversion decision: ship a minimal rule-based `toUnicode` (or adopt a vetted converter library, UNVERIFIED which, evaluate Google's Myanmar Tools detector https://github.com/googlei18n/myanmar-tools). ASR output from Raj is expected to be Unicode; the guard covers typed input and pasted text.

- [ ] Failing tests: `zawgyiIsConvertedOrFlagged` (known Zawgyi sample -> `looksLikeZawgyi` true and `toUnicode` output equals the Unicode form); `unicodeInputUnchanged`; `normalizeTrimsAndCollapsesWhitespace`; `englishCodeSwitchPreserved` ("Grab ခ ၅၀၀၀" keeps Latin letters intact).
- [ ] Run `./gradlew :core:data:testDebugUnitTest --tests app.budgetwise.data.BurmeseTextNormalizerTest`. Expected FAIL.
- [ ] Implement, call from `TransactionRepository.addAll` on `description` and `transcript`.
- [ ] Run again. Expected PASS. Manually verify rendering on one low-end device (UNVERIFIED until done).
- [ ] Commit: `git add android/core/data android/app && git commit -m "feat(android): burmese font bundle and zawgyi guard"`

## Contract notes for the lead (problems found)

- **No update endpoint.** There is no `PUT /transactions/:id`, so editing a synced item is impossible; plan uses delete + recreate (Decision 6).
- **No delete tombstones on pull.** `GET /transactions?updatedSince=` cannot report server-side deletions, so web deletions never reach the phone. Ask WS1 for `deletedAt` or a `deleted: true` flag.
- **Idempotency semantics unspecified.** WS1 must state that re-POSTing an existing `clientId` returns the stored row unchanged (not 409 and not an update).
- **`DELETE /transactions/:id` needs the server id,** which offline-created rows lack until synced; handled locally, but a `DELETE by clientId` option would be simpler.
- **Contract gaps in `:asr`:** `transcribe` has no cancellation or progress contract, `RajVariant` exposes no size or URL for the download UI, and `ModelManager` has no `delete`. `BurmeseBudgetParser` is an `object`, so WS2 wraps it in `DraftParser` for testing.
- **`DraftTransaction.category` must be one of the passed categories;** WS4 should guarantee that, else WS2 coerces unknowns to "Other".
- **No refresh token;** the 30 day JWT forces full re-login, handled in Task 5.
