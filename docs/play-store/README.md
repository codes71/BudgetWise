# Google Play release preparation

Pocket Peek uses the standard Android `release` variant for Google Play. It has direct APK updates disabled and excludes `REQUEST_INSTALL_PACKAGES` and the friends update FileProvider. Google Play distributes future native updates.

## Verified preparation status (7 November 2026)

- Approved Folded Pocket logo and all 20 custom icons are integrated, preserving the existing UI. Local [store graphics](graphics/README.md) and four actual 1080 x 1920 English [phone screenshots](screenshots/README.md) are ready. Optional Myanmar captures are documented separately.
- Signed AAB `PocketPeek-0.3.0-4.aab` is prepared for `app.pocketpeek`, version `0.3.0` (4). Package/SDK checks, 16 KB ZIP alignment, native-library continuity and English/Myanmar resources passed. See [candidate evidence](console-submission.md#signed-candidate-evidence) for its hash and certificate.
- Native signup, login, persistence, light/dark themes and English/Myanmar QA passed on a disposable emulator with synthetic data. These were optimized QA-build checks, not proof of a Play-delivered install or a physical-phone migration.
- Web production build passed, and the final full suite passed after the own-key prototype-mapping fix: 29 files, 394 tests. MongoDB/custom-auth source is ready locally; production remains on Firestore. A read-only encrypted preflight covering 241 documents matched the dry-run plan; no import or cutover occurred.
- Play internal release draft 1 exists; release name and notes were saved and verified live: “Changes saved. You can now preview your release before publishing it.” The AAB has not been uploaded and no rollout occurred. The connected Brave extension has Allow access to file URLs disabled, blocking upload. A dedicated empty reviewer account exists and actual-backend login passed; the owner still needs to enter its private details in Console App access.
- Owner chose an 18+ audience, but the form remains locked until App access is completed. No ads, Government No, Health None, Finance category and contact details are saved. Financial-policy eligibility and final public declarations remain unresolved. The deployed Vercel project is personal `codes-t/budget-wise` at `budgetwis3.vercel.app`. Its cutover remains pending CLI human/WAF approval using an isolated personal CLI session. The company-default account `thura-s` is unrelated, remains untouched and must never be used or changed for this project.

Default store-listing copy is also draft-saved and verified live (“Your changes have been saved”): Pocket Peek, 78-character short description and 1510-character full description. Images are still missing because extension file access blocks upload.

This is the coordinating session's verified snapshot, not a claim of Play upload, production deployment or policy approval.

## Account setup

The owner selected a **new personal** account, developer display name **thurashark**, public support address **thurashark@gmail.com**, and portfolio **https://thuracodes.vercel.app/**. The owner created the Pocket Peek app record (`app.pocketpeek`, Draft) on 7 November 2026. Account identity/contact/trader verification remains separate from that app record; re-check the current Console state before submission. Do not send identity documents, payment details, passwords or service credentials through chat or commit them here.

Start with **internal testing** for friends. Public production access for a new personal account requires a closed test with at least 12 testers opted in continuously for at least 14 days, followed by an application for production access. Internal testing alone does not count toward that requirement. Recruit real testers and record their feedback; neither paying the fee nor waiting 14 days alone guarantees approval.

Before submitting Pocket Peek, resolve its financial-feature/account-type classification with Google. The official financial-services definition includes money management and personalized advice; no explicit manual-budgeting exemption was found. See [Console submission materials](console-submission.md) for the factual support-request draft, foreground-service declaration text and remaining form evidence. Personal-account registration does not establish this app's eligibility.

Sources: [registration](https://support.google.com/googleplay/android-developer/answer/6112435), [new personal-account testing](https://support.google.com/googleplay/android-developer/answer/14151465).

## Package transition and Play App Signing

Pocket Peek uses `app.pocketpeek`. It installs separately from legacy BudgetWise (`app.budgetwise`), even if both are signed with the same key. Keep the existing BudgetWise app and its data intact. Do not uninstall it or clear its storage. Signing in to the same cloud account can retrieve already-synced records; it does not copy the old app's local-only records, pending changes, saved sessions or speech-model files. Verify that transition with authorized device checks before claiming migration complete.

Use the established release-signing process and verify the final **app signing** certificate in Play Console. Signing an uploaded AAB does not prove which certificate Google will use for installed APKs. Configure the new package's Play App Signing deliberately; no enrollment or key import is established by this documentation. Keep private keys and exports outside Git. [Google signing guidance](https://support.google.com/googleplay/android-developer/answer/9842756).

Historical continuity evidence: the earlier BudgetWise production certificate SHA-256 was recorded as `f2abec2d9d846d75cee69bafd05f8afe2ef5050247d2c6fca53ba8db5c7368bb`, and the Xiaomi was previously migrated in place to that signer. The signed Pocket Peek AAB now independently reports the same certificate, as recorded below. The earlier Xiaomi observation remains historical; neither it nor the AAB certificate proves the final Google Play APK signer or a Pocket Peek phone migration.

## Build the bundle

Use an isolated worktree and the SDK/JDK setup in `CLAUDE.md`. Supply the established signing environment securely. Choose a version code higher than **every** distributed friends and Play build. The Pocket Peek candidate is `0.3.0`, code `4`; prior BudgetWise preparation used `0.2.1`, code `3`, following public friends code `2`. The signed candidate and bounded QA evidence are recorded above. A Play upload and Play-delivered install remain unverified.

```sh
cd apps/android
./gradlew :app:bundleRelease :app:assembleRelease :app:lintRelease \
  -Pbudgetwise.versionCode=4 \
  -Pbudgetwise.versionName=0.3.0 \
  -Pbudgetwise.releaseApiBaseUrl=https://budgetwis3.vercel.app/api/v1/
```

Upload the `.aab`, not the friends APK. Retain the R8 mapping for crash retracing. Use the explicit version and real HTTPS API URL when dispatching the existing `Pocket Peek Android release` workflow. Its Play service-account secret is optional and is not configured by this preparation; a local bundle does not establish an upload or a reviewed release.

The project targets API 36. Confirm the final bundle's identity, signer, production API URL, language resources, manifest permissions, native library page alignment and bundle configuration before uploading. [Target API policy](https://support.google.com/googleplay/android-developer/answer/11926878), [16 KB support](https://developer.android.com/guide/practices/page-sizes).

## Store setup

- Category: Finance. Confirm the app-content financial-features questionnaire against the actual budgeting features; this app does not implement lending, investments, bank login or payments.
- Metadata drafts are in `en-US/`. They describe actual expense/budget, receipt-review and Burmese-voice features without fabricated ratings or security guarantees. Review translated copy with a Burmese reader before publishing a localized listing.
- Support: `thurashark@gmail.com`.
- Privacy URL: `https://budgetwis3.vercel.app/privacy`.
- Account deletion URL: `https://budgetwis3.vercel.app/delete-account`.
- The public pages and deletion API must be deployed and independently verified before submitting this app. A local page or prepared bundle is not a live policy URL.
- Local approved [graphics](graphics/README.md) include the 512 x 512 store icon and 1024 x 500 feature graphic. Four actual 1080 x 1920 English [phone screenshots](screenshots/README.md) use synthetic data, with optional Myanmar captures documented. These are prepared assets, not proof of Console upload or approval.
- Complete the declarations required by the chosen track, including ads, target audience, IARC content rating, app access and foreground services. Prepare Data safety for later tracks; internal-only scope is explained below. The voice model download uses a user-started `dataSync` foreground service; prepare the required explanation and demonstration showing progress and cancellation.
- The dedicated empty reviewer account has been created and actual-backend login verified. The owner must enter its details in Console App access; that saved form is still pending. Keep credentials outside source, metadata and public notes. Login verification alone does not establish every restricted cloud feature has been reviewed.

## Data safety and deletion

Use `data-safety-draft.md` as an evidence inventory, not a submitted declaration. Google currently exempts apps active exclusively on internal testing from completing the Data safety form; prepare accurate answers for any later closed/open/production track. See the [current Data safety scope](https://support.google.com/googleplay/android-developer/answer/10787469). Account and financial data are synced off the phone. Optional scans send images and category context to the service and AI providers; voice recognition is local but recognized text can be sent for cloud parsing. Provider/service sharing exceptions require the actual provider relationship to satisfy Google's criteria. Do not answer "no data collected" or assume all processing is ephemeral.

The release preparation adds password-confirmed account deletion in Android Settings and a public web flow. Successful deletion removes cloud account descendants, owned email indexes and account-specific counters. Missing/deleting accounts cannot write financial records using stale tokens or cookies. Local Android cleanup happens after server confirmation and targets only that account. Other saved accounts and shared voice models remain. Offline copies on other devices may remain until their saved account or app storage is removed.

Verify actual provider/hosting log retention, backup deletion, support handling and the completed Console App access entry before finalizing declarations. Do not invent fixed retention periods. The source does not configure automatic expiry deletion for shared network rate-limit records.

## Local verification isolation

Run release checks with the supported isolated MongoDB replica-set harness:

```sh
cd apps/web
npm test
npx tsc --noEmit
npm run build
```

`npx vitest run --project mongo` selects native runtime tests. The complete suite also verifies the historical copier and actual migration roundtrip under its own Firestore emulator project. Mongo fixtures use dynamically assigned replica-set ports and unique `test-pocketpeek-*` databases. Never use Atlas for test cleanup. No production credentials are needed. TypeScript still has a pre-existing diagnostic baseline, so compare diagnostics rather than assuming a clean exit.

## First release order

1. Complete the private Console App access entry, confirm the owner-selected 18+ audience when unlocked, and re-check remaining account/policy tasks and new-package Play App Signing.
2. Keep the MongoDB import/cutover and personal `codes-t/budget-wise` Vercel deployment as separate pending steps, using only the isolated personal CLI; never use or change company `thura-s`. Verify any deployed policy/deletion behavior using only an explicitly authorized disposable account.
3. Resolve the extension file-access blocker, upload the verified signed AAB to internal draft 1, retain the verified saved release name/notes, upload missing listing images, complete required declarations and inspect the Play pre-launch report. No rollout has occurred.
4. Share the Play test opt-in link with friends. Once ready, start the required closed test and record feedback.
5. Apply for production access after the required closed testing, address review feedback and choose a production rollout.

The signed candidate, bounded QA results, local assets, reviewer-account creation/login and saved Console facts are recorded above. Final post-fix tests passed (29 files, 394 tests), and internal release name/notes plus store-listing copy are verified saved drafts. Protected App access entry, AAB/image uploads, Google Play signing readback, policy eligibility, public declarations, MongoDB import/cutover and personal `codes-t/budget-wise` Vercel publication remain pending. Company `thura-s` remains untouched and must not be used or changed. Pocket Peek branding/assets have not been verified on the deployed website; no Play rollout or production cutover is established.
