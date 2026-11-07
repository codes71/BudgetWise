# Pocket Peek Data safety working inventory

Candidate: Pocket Peek `app.pocketpeek`, version `0.3.0` (code `4`). Source preparation updated on 8 November 2026 on PR25; these changes are not deployed or submitted. This is a draft for the Console form, grounded in the implementation. It is not a submitted or approved declaration. The owner must confirm provider contracts, operational logging/retention and the precise choices offered by the current form.

| Data | Observed collection and purpose | Choice and remaining verification |
|---|---|---|
| Email, account ID, name | Account creation/authentication and cross-device account management. Name can be derived from email. | Collected: personal information (email, user IDs, name). Account management and app functionality. Email/credentials are required; assess optional name separately. |
| Expenses, income, budgets, plans, merchant and receipt details | Stored in MongoDB Atlas for the synced ledger, budgeting and display. | Collected: financial information, including purchase history and other financial info. App functionality. Sync is not local-only or ephemeral. No bank-login or card-entry integration is implemented, but chosen receipt/payment images may contain further sensitive details. |
| Receipt/payment photos | Chosen images go to the server and an explicitly permitted eligible Anthropic/OpenAI provider for draft extraction; the scan route does not save the original to the account database. | Collected: photos, for app functionality. Optional scanning. Photos and sensitive information shown in them leave the device. Do not claim ephemeral processing without verified provider retention. |
| Voice-derived text | Audio is recognized on-device; recognized words/categories can go to AI for parsing. Saved entries may include recognized text. | Collected: recognized text when cloud voice is used, for app functionality. Optional voice/cloud features. Raw audio upload was not found in this path; microphone permission alone is not off-device audio collection. Assess other user-generated content and financial details in the text. |
| Web budget observations / optional categorization | Budget observations now use fixed comparisons of the signed-in account’s saved current-month expenses and budgets; no AI provider receives that comparison. Optional categorization still sends a description and categories only when a provider is eligible. | Keep the web feature distinct from native extraction. This source change does not establish a financial-policy exemption. |
| Security/diagnostics and network metadata | API abuse counters use IP/account-derived keys and request counts; content-free logs include fixed error classes, timing and usage. Hosting receives connection metadata and derives approximate city/country; request country restricts provider eligibility. | App interactions, Diagnostics and Approximate location are collected for functionality/security/diagnostics as applicable. Retain approximate-location sharing unless actual provider-purpose evidence justifies otherwise. Vercel plan/products/preferences and retention remain unverified. No native marketing purpose was found. Rate-window expiry is not record deletion. |

The direct Android dependencies do not declare advertising, app-analytics, Crashlytics or Sentry integrations. ONNX Runtime has an upstream telemetry initializer explicitly removed by the ASR manifest; `OnnxSessions` also disables telemetry. Check the final merged artifact before certifying device-ID or telemetry behavior. This bounded source finding is not a guarantee that no network/provider metadata is collected.

The release build requires an HTTPS API URL, and the configured production API host remains `https://budgetwis3.vercel.app/api/v1/`. This source audit did not inspect live production traffic. Do not describe transport encryption as end-to-end encryption: the service reads records and AI providers process chosen content. Microphone/camera permissions are feature-specific. First-use AI disclosure identifies the sent content, purpose and recipients before an affirmative choice, with manual entry available.

Provider transfers may qualify for Google's service-provider sharing exception only if the actual relationship meets its conditions. Source dependencies alone cannot establish the exception. Do not mark "no sharing" merely because a provider hosts the processing.

Deletion is password-confirmed, removes active cloud account records and owned financial descendants, invalidates ordinary access, and clears the requesting Android account locally after confirmation. Shared security records and provider operational logs have separately disclosed limits. Confirm backup and log retention before publishing a final retention statement.

## Scope and evidence for the form

Google's current guidance exempts apps active exclusively on internal testing from completing Data safety. This inventory still describes actual collection and is preparation for later tracks, not evidence that the form was submitted or approved. Account-backed collection is **Yes**; a blanket **No sharing** answer cannot be established from source alone. Sharing exceptions require the actual processing relationship or applicable user consent to meet Google's rules.

The Android signup DTO has email, password and name, with no phone or profile-photo upload endpoint. The shared web profile separately supports phone number and profile-photo URL in `apps/web/src/lib/users.ts`. Keep web-only fields distinct when answering the Android package's form; review any in-app webview or linked flow before deciding its scope.

| Source | What it establishes |
|---|---|
| `apps/android/core/data/src/main/java/app/budgetwise/data/net/ApiService.kt` | Auth/register, ledger/budget sync, password-confirmed deletion, photo requests and recognized-text requests. |
| `apps/android/core/data/src/main/java/app/budgetwise/data/net/Dtos.kt` | Financial fields, merchant/items and optional saved transcript. |
| `apps/web/src/lib/mobile/schemas.ts` | Server-accepted account, transaction, photo and transcript payloads. |
| `apps/web/src/app/api/v1/scan/route.ts` and `apps/web/src/app/api/v1/voice/parse/route.ts` | Optional authenticated cloud processing; categories accompany content; scan route does not save the original image. |
| `apps/web/src/ai/generate.ts`, `provider-policy.ts` | Explicit eligible provider order, account/country gating and final refusals. Defaults deny cloud calls; a key is not eligibility. |
| `apps/android/app/src/main/res/values/privacy_disclosure.xml` | In-app content/recipient disclosure, affirmative AI choice and manual alternative. |
| `apps/web/src/lib/account-deletion.ts`, `apps/web/src/lib/account-access.ts`, `apps/web/src/lib/mobile/rate-limit.ts` | Cloud deletion/access restrictions; account counters versus retained shared security records. |
| `apps/web/src/lib/logger.ts`, scan route and AI generator | Server diagnostics/timing/error paths. Actual provider retention remains unverified. |
| `apps/android/asr/src/main/AndroidManifest.xml`, `apps/android/asr/src/main/java/app/budgetwise/asr/engine/OnnxEngines.kt` | Telemetry initializer removal and native telemetry protections; verify merged artifact. |

Do not claim independent security certification, completed provider-contract review, a fixed backup/log retention interval, or successful Pocket Peek deletion/reviewer tests from this source inventory.

Sources: [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469), [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311), [account deletion](https://support.google.com/googleplay/android-developer/answer/13327111).

Migration recovery copies remain separately in the preserved Firestore source and encrypted backups until their retention/deletion handling is settled. Do not claim immediate removal from those copies or a verified fixed retention interval. The privacy policy discloses this limitation; resolve it before public-release declarations.


## Prepared changes and remaining gates

The source privacy policy now explains approximate city/country derived from IP and the use of country to restrict cloud AI. It does not request GPS permission. Provider country lists and account/entity eligibility are separate: Myanmar appears on OpenAI’s current API list and not Anthropic’s. No live provider configuration was changed. See [provider policy and activation gates](../ai-provider-policy.md).

Name may be optional as an input, but the server can derive it from email, so omitting the field in the Android screen does not mean no name is collected. Optional scan/voice content remains collected even when a user may decline the feature. No raw audio transfer is present in the native ASR path. Whole-photo transfer can include incidental account/payment details; assess Google's `User payment info` category against supported synthetic photo types rather than asserting the extraction schema proves absence. Do not access real user images to settle this preparation.

A detailed file-only recommended Console/reviewer handoff is prepared in `console-preparation-update.md`. No pending submission has been edited. The working deletion journal records one initiated request and pending recovery cleanup; physical historical-copy deletion and actual retention/expiry remain separately authorized operational work. Do not mark those complete from source/tests alone.
