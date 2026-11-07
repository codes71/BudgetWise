# Console submission materials

PR25 source preparation update, 8 November 2026: see [recommended Console/reviewer changes](console-preparation-update.md). These are file-only recommendations. No pending submission or saved Console setting was changed. Historical evidence below remains dated.

Prepared for developer `thurashark`, support `thurashark@gmail.com`, portfolio `https://thuracodes.vercel.app/`. These are drafts for the owner to check against the actual Console forms. Nothing in this file establishes an app submission or approval.

## Current candidate and readiness

Coordinator-verified snapshot, 7 November 2026. Preparation, test results, Console saves, deployment and rollout are separate states.

- Product: **Pocket Peek** (keep the space). Package: `app.pocketpeek`. Signed candidate: `0.3.0`, version code `4`.
- App ID `4973206447585100258`, internal release originally prepared as draft 1. Name/notes are verified saved. After the owner enabled extension file-URL access, signed AAB 4 (`0.3.0`) was uploaded and accepted; Play reports Supports 16 KB. Internal preflight has no errors and one optional native debug-symbol warning; R8 mapping is attached. The authorized Save and publish action completed. Parent verified Console screenshot and saved DOM show the internal track Active, latest release `0.3.0 (4) Pocket Peek internal`, Available to internal testers, released 7 November 2026 at 12:58 PM Myanmar time.
- The approved Folded Pocket logo and 20 custom icons are integrated without UI redesign. Local [store graphics](graphics/README.md) and four real 1080 x 1920 English [screenshots](screenshots/README.md) are ready; optional Myanmar captures are documented. The captures use an optimized debug-signed QA build, not a Play-delivered APK.
- Native signup/login, persistence, existing light/dark themes and English/Myanmar QA passed on a disposable emulator with synthetic data. Web production build passed. The final full suite passed after the own-key prototype-mapping fix: 29 files, 394 tests.
- The dedicated reviewer account's prior JWT reads remained identical after cutover; fresh password login returned 200 with the same identity. A temporary reviewer-only 1-kyat transaction saved/replayed with 200, stored one record, then cleanup returned 204 and the reviewer account is empty again. No existing user's financial data was modified. Credentials remain private; the owner's Console App access entry and full restricted-feature review are separate.
- Owner selected 18+ as the intended audience, but its Console form is locked until App access is completed. No ads, Government No, Health None, Finance category and contact details are saved. App-level financial-feature classification, account eligibility and final public declarations remain unresolved.
- MongoDB migration and backend cutover are complete. Canonical `https://budgetwis3.vercel.app` serves normal deployment `dpl_4GZiWjRfKQSb539T3VUs7hLru19t` from reviewed runtime commit `4a9f51e`, in personal Vercel project `codes-t/budget-wise`. Exact parity between 242 source and target records plus 11 indexes with no TTL was verified independently; the frozen source remained unchanged. The overnight 241 to final 242 drift was only `rateLimits` increasing from 17 to 18, with unchanged account/financial collection hashes. Firestore is retained; old-host mutation WAF protection remains active and normal Mongo writes are live. **Do not blindly roll back to stale Firestore.** The company-default account `thura-s` is unrelated, remains untouched and must never be used or changed for this project.
- Default store-listing name Pocket Peek, 78-character short description and 1510-character full description are draft-saved and verified live with “Your changes have been saved”. The approved logo, feature graphic and all four English screenshots are now saved. The internal-track publication is verified separately. This does not establish a reviewed public listing.
- See [form-answer facts](#form-answer-facts) and the [Data safety inventory](data-safety-draft.md). The normal backend and internal track are live. Google public-policy approval, a Play-delivered install and device acceptance remain separate, unverified checks. App access, owner-selected 18+ audience and public declarations did not block this internal preflight and remain separate work.

### Verified internal tester access

- The retained `Mytesters` list is checked and contains four users.
- Actual Console opt-in URL: [Pocket Peek internal invitation](https://play.google.com/apps/internaltest/4699527614541883626). The parent independently verified the live invitation page.
- Testers currently see `app.pocketpeek` (unreviewed) until setup and first review are complete. This is not a public listing approval.
- No physical-phone install or Play-delivered device acceptance is claimed. There is no closed-track or public production release.
- Internal testing does not count toward the new personal-account production-access requirement. A separate closed test requires at least 12 testers opted in continuously for 14 days, followed by an application for production access. [Google testing guidance](https://support.google.com/googleplay/android-developer/answer/14151465).
- The private reviewer App access entry, owner-selected 18+ audience and public declarations remain separate work. Public financial/account eligibility remains unconfirmed, and the support-request draft is unsent.

### Signed candidate evidence

- Local AAB: `/Users/code/.codex/artifacts/pocket-peek-rebrand-20261107/release-0.3.0-4/PocketPeek-0.3.0-4.aab`.
- SHA-256: `652cbef32cd6bb80c78f88ac6ce0ac6a2eade66fb179a16a3a19c94dec0d97e5`.
- AAB signing certificate SHA-256: `f2abec2d9d846d75cee69bafd05f8afe2ef5050247d2c6fca53ba8db5c7368bb`.
- Packaging/SDK and 16 KB ZIP-alignment checks passed; native libraries match the previously validated binaries, and English/Myanmar resources are included. Verification receipts and R8 mapping are retained beside the AAB.
- This is verified local signing/package evidence. The AAB is uploaded and accepted, including Play's Supports 16 KB readback. Internal publication is verified. Google Play App Signing readback, the final installed APK signer and Play-delivered device acceptance remain separate, unverified checks.

## Account type and financial features

Google's [account requirements](https://support.google.com/googleplay/android-developer/answer/10788890) require an organization account for financial products and services. Its [Financial Services policy](https://support.google.com/googleplay/android-developer/answer/9876821) defines these broadly to include management or investment of money and personalized advice. The reviewed official guidance does not explicitly exempt manual expense trackers or budgeting tools.

Pocket Peek Android stores income, expenses and monthly budgets, extracts draft entries from receipt images, and parses on-device recognized speech using a cloud service. It does not implement banking, custody of funds, payments, transfers, loans, trading, investments or cryptocurrency. The associated web application separately offers AI-generated spending suggestions using transaction summaries and budget goals. Do not claim that this web-only feature is implemented in Android.

Personal-account eligibility for this app is not confirmed. Obtain a Google Play Developer Support determination before certifying that eligibility or making a public Pocket Peek release submission. Do not silently switch account type, remove features, or declare no financial features to get past a form. The account itself can be used for other eligible personal apps.

All apps on closed/open/production tracks must complete the [Financial features declaration](https://support.google.com/googleplay/android-developer/answer/13849271), even when declaring no financial features. This app-level declaration is separate from developer-account eligibility. Check the relevant Other and, for any applicable web functionality, financial-advice choices with Google.

### Support request draft, not sent

Subject: Account-type guidance for a manual expense and budget tracking app

I am setting up a personal developer account under the name thurashark and would like to confirm the correct account type before submitting Pocket Peek (package app.pocketpeek).

The Android app lets users manually record income and expenses, maintain monthly budgets, and sync their records. Optional receipt scanning extracts draft entries for the user to review. Optional voice entry recognizes speech on the device and sends recognized text to a cloud service for parsing. The app does not connect to bank accounts, hold or move funds, offer loans, facilitate payments, or provide trading, investment or cryptocurrency services.

The linked website compares recorded current-month category totals with user-set budgets using fixed informational text. It does not generate personalized financial advice. This web comparison is separate from the Android interface.

Does this functionality require an Organization developer account under your Financial Services policy, or can the Android app be published from a Personal account? Which Financial features declaration choices should be used for the Android functionality described above?

## Foreground service declaration draft

Service type: `dataSync`.

Feature: user-initiated download of the optional Burmese speech-recognition model pack, approximately 1.1 GB. Once installed, Android performs recognition locally. The download uses WorkManager's foreground service with an ongoing progress notification. The user can cancel from the app and can continue using manual entry without the voice pack.

Why foreground execution: the requested pack is large and may take longer than an ordinary short background operation. Foreground execution keeps its progress visible while the user waits or leaves the screen. Delaying or interrupting the download delays voice availability; the app does not mark the pack ready until all required files pass size/hash checks. The download implementation supports partial-file recovery on a later attempt.

Demonstration checklist: start the download from the actual app, show the in-app progress, show the ongoing Android notification, return to the app and cancel. Use the exact release implementation and a supported device. Upload the resulting video to an owner-approved, reviewer-accessible location and paste its link in the Console. A locally recorded file is not yet a reviewer-accessible link. [Google FGS guidance](https://support.google.com/googleplay/android-developer/answer/13392821).

Historical BudgetWise demonstration URL: **https://budgetwis3.vercel.app/play/voice-model-download-demo.mp4**. Verify that it is publicly reachable before entering it in the Console. This is an unedited 22.15-second recording of the actual download/progress/ongoing-notification/Cancel flow on an API 35 emulator. It uses an optimized QA build with the same Android runtime source as legacy BudgetWise release 0.2.1, a local API override for synthetic sign-in, and debug signing. It is not footage of the exact Play-delivered binary. The speech-model download itself uses the real public model host. No credentials, financial records, microphone audio or AI-provider request appear in the recording. It predates Pocket Peek 0.3.0/code 4 and does not prove this candidate's behavior or branding. Capture updated candidate footage with an authorized test account when required.

## New-account verification

Earlier account onboarding showed Create app disabled. That observation is historical: the owner subsequently created Pocket Peek (`app.pocketpeek`) as a Draft on 7 November 2026. The coordinating session separately reports replacement Myanmar legal-profile review and trader/contact-phone work pending. Re-read the current account verification tasks before any final declaration; app creation does not prove account verification complete.

Trader status is a separate owner declaration about commercial activity. A personal account does not automatically mean non-trader. If declaring as a trader, Google requires additional self-certification and app terms-of-service links, and describes which contact information becomes public. Review the actual prompts and [Google's trader-status guidance](https://support.google.com/googleplay/android-developer/answer/17594752); this preparation does not make that declaration for the owner.

## App access and remaining declarations

- The dedicated empty reviewer account exists and actual-backend login passed. The owner must enter its details in Console App access; no credential value or private credential-file path belongs in these docs. Verify the saved entry and restricted flows before public submission. This remains separate from the accepted internal preflight.
- Use `data-safety-draft.md` for the source-based collection inventory. Confirm actual provider contracts, operational logs and backup retention before finalizing sharing/retention answers. No specific retention interval has been verified by this preparation.
- Pocket Peek's Android release includes no ads or advertising SDK. Do not generalize that answer to other apps planned for this developer account.
- The owner chose 18+ for Pocket Peek. Its audience form is still locked pending App access; this is not a saved audience declaration. Complete the app content rating independently when available.
- Use the deployed public privacy/deletion URLs after verifying that they are reachable without login. App deletion must require explicit password confirmation. Do not test by deleting an existing real account.
- Signed AAB 4 is accepted and R8 mapping is attached. Internal preflight has zero errors and one optional native debug-symbol warning. The internal publish action is verified as Active and Available to internal testers; retain the signed-bundle verification receipt. Public financial/account eligibility remains unconfirmed, and the support request above has not been sent.

## Form-answer facts

Source inspected on 7 November 2026, with coordinator-verified Console status below. Only explicitly marked saved entries have been saved; none establishes Google policy approval.

| Console topic | Source-backed answer or limit | Evidence |
|---|---|---|
| Ads | No ads has been saved in Console. No ads in the inspected Android implementation. No advertising, AdMob, billing, analytics, Crashlytics or Sentry integration is declared in its direct app/library dependencies. Re-check the merged release manifest/dependencies before the final answer. | `apps/android/app/build.gradle.kts`, `apps/android/gradle/libs.versions.toml`, app/asr manifests. |
| Target audience | Personal expense and budget tracking is implemented. No date-of-birth collection or age gate establishes an intended age range. The owner explicitly chose 18+, but the audience form is locked until App access is completed and this choice is not yet saved. | `apps/android/core/data/src/main/java/app/budgetwise/data/net/Dtos.kt`, Android navigation/screens, `apps/web/src/lib/mobile/schemas.ts`. |
| Government | No government-service functionality is implemented in the inspected feature set. The product identifies its publisher as thurashark. Government No has been saved in Console. | Android navigation and `apps/web/src/app/privacy/page.tsx`. |
| Health | No health, fitness, medical or human-research functionality or Health Connect dependency is present in the inspected feature set. A pharmacy purchase category does not implement health functionality. Health None has been saved in Console. | App dependencies, Android navigation, transaction DTOs. |
| Financial features | Finance category has been saved; that is separate from the financial-features declaration and account-eligibility question. Records income/expenses, budgets, monthly plans, receipt drafts and spending questions. No banking connection, custody, payment execution, loan, trading or crypto endpoints are implemented. Reading a KBZPay screenshot does not execute a payment. Consider the form's Other option for budgeting; the existing financial/account-type question remains unresolved. Web fixed budget observations are separate from Android; this bounded implementation does not determine account eligibility. | `apps/android/core/data/src/main/java/app/budgetwise/data/net/ApiService.kt`, `apps/web/src/lib/mobile/schemas.ts`, `apps/web/src/app/actions.ts` (`generateSuggestions`). |
| App access | Cloud auth, sync and AI features require a working account. A dedicated empty reviewer account and actual-backend login are verified; the owner's protected Console App access entry and full restricted-flow review remain pending. | `ApiService.kt`, `apps/web/src/lib/mobile/bearer.ts`. |
| Data collection | Yes for account-backed Android use: email/account ID/name, ledger/budgets; optional photo and recognized-text AI requests. Raw microphone audio stays in the inspected local ASR path. Do not answer no data collected. | [Inventory](data-safety-draft.md), scan/voice routes, Android DTOs and disclosure resources. |
| Data sharing | Source proves off-device provider processing, not contractual eligibility for Google's sharing exceptions. Confirm actual provider terms and any applicable consent exception before selecting an unconditional No sharing answer. | `apps/web/src/ai/generate.ts`, Android `privacy_disclosure.xml`, [Google guidance](https://support.google.com/googleplay/android-developer/answer/10787469). |
| Internal-track scope | Google currently exempts an app exclusively active on internal testing from completing Data safety. This does not mean no collection and does not settle other policies or later-track declarations. | [Google Data safety scope](https://support.google.com/googleplay/android-developer/answer/10787469). |

The ASR dependency includes an upstream ONNX telemetry initializer, explicitly removed by `apps/android/asr/src/main/AndroidManifest.xml`; native session setup also disables telemetry. Verify those protections in the final merged artifact before describing device-ID collection as absent. This source audit does not verify runtime provider behavior.
