# Google Play release preparation

BudgetWise uses the standard Android `release` variant for Google Play. It has direct APK updates disabled and excludes `REQUEST_INSTALL_PACKAGES` and the friends update FileProvider. Google Play distributes future native updates.

## Account setup

The owner selected a **new personal** account, developer display name **thurashark**, public support address **thurashark@gmail.com**, and portfolio **https://thuracodes.vercel.app/**. Register at [Play Console](https://play.google.com/console/signup), pay Google's US$25 one-time fee, and complete its legal identity and Android-device verification. Do not send identity documents, payment details, passwords or service credentials through chat or commit them here.

Start with **internal testing** for friends. Public production access for a new personal account requires a closed test with at least 12 testers opted in continuously for at least 14 days, followed by an application for production access. Internal testing alone does not count toward that requirement. Recruit real testers and record their feedback; neither paying the fee nor waiting 14 days alone guarantees approval.

Before submitting BudgetWise, resolve its financial-feature/account-type classification with Google. The official financial-services definition includes money management and personalized advice; no explicit manual-budgeting exemption was found. See [Console submission materials](console-submission.md) for the factual support-request draft, foreground-service declaration text and remaining form evidence. Personal-account registration does not establish this app's eligibility.

Sources: [registration](https://support.google.com/googleplay/android-developer/answer/6112435), [new personal-account testing](https://support.google.com/googleplay/android-developer/answer/14151465).

## Preserve existing installs when enrolling in Play App Signing

Keep package name `app.budgetwise` and import the **existing production app-signing key** into Play App Signing before distributing test installs to current users. Follow the actual Console's **Change key / provide your app signing key** instructions and its PEPK encryption/export process. The Console supplies the encryption material; this repository must never contain the private key or an unencrypted export.

The existing production certificate SHA-256 is:

```text
f2abec2d9d846d75cee69bafd05f8afe2ef5050247d2c6fca53ba8db5c7368bb
```

Check the **app signing** certificate displayed in Play Console against that fingerprint. Signing the uploaded AAB with this certificate does not by itself configure the final APK signer held by Google. Accepting an unrelated Google-generated signing key can prevent an ordinary update over existing friends installs. The user's Xiaomi has already been migrated in place to the production signer.

Use a separate upload key after enrollment when practical. This preparation does not create or upload a new signing identity. Keep a secure encrypted backup of the private signing directory outside Git. [Google signing guidance](https://support.google.com/googleplay/android-developer/answer/9842756).

## Build the bundle

Use an isolated worktree and the SDK/JDK setup in `CLAUDE.md`. Supply the established signing environment securely. Choose a version code higher than **every** distributed friends and Play build. The prepared candidate is `0.2.1`, code `3`, following public friends code `2`.

```sh
cd apps/android
./gradlew :app:bundleRelease :app:assembleRelease :app:lintRelease \
  -Pbudgetwise.versionCode=3 \
  -Pbudgetwise.versionName=0.2.1 \
  -Pbudgetwise.releaseApiBaseUrl=https://budgetwis3.vercel.app/api/v1/
```

Upload the `.aab`, not the friends APK. Retain the R8 mapping for crash retracing. Use the explicit version and real HTTPS API URL when dispatching the existing `Android release` workflow. Its Play service-account secret is optional and is not configured by this preparation; a local bundle does not establish an upload or a reviewed release.

The project targets API 36. Confirm the final bundle's identity, signer, production API URL, language resources, manifest permissions, native library page alignment and bundle configuration before uploading. [Target API policy](https://support.google.com/googleplay/android-developer/answer/11926878), [16 KB support](https://developer.android.com/guide/practices/page-sizes).

## Store setup

- Category: Finance. Confirm the app-content financial-features questionnaire against the actual budgeting features; this app does not implement lending, investments, bank login or payments.
- Metadata drafts are in `en-US/`. They describe actual expense/budget, receipt-review and Burmese-voice features without fabricated ratings or security guarantees. Review translated copy with a Burmese reader before publishing a localized listing.
- Support: `thurashark@gmail.com`.
- Privacy URL: `https://budgetwis3.vercel.app/privacy`.
- Account deletion URL: `https://budgetwis3.vercel.app/delete-account`.
- The public pages and deletion API must be deployed and independently verified before submitting this app. A local page or prepared bundle is not a live policy URL.
- Supply a 512 x 512 store icon, a 1024 x 500 feature graphic, and accurate phone screenshots using synthetic data. Never use the owner's real ledger or account information as promotional material.
- Complete ads, target-audience, IARC content-rating, Data safety, app access, and foreground-service declarations. The voice model download uses a user-started `dataSync` foreground service; prepare the required explanation and demonstration showing progress and cancellation.
- Give Google a dedicated working reviewer account through the Console's App access form. Do not place reviewer passwords in source, metadata, this guide, or public release notes. The reviewer must be able to exercise restricted cloud features.

## Data safety and deletion

Use `data-safety-draft.md` as an evidence inventory, not a submitted declaration. Account and financial data are synced off the phone. Optional scans send images and category context to the service and AI providers; voice recognition is local but recognized text can be sent for cloud parsing. Provider/service sharing exceptions require the actual provider relationship to satisfy Google's criteria. Do not answer "no data collected" or assume all processing is ephemeral.

The release preparation adds password-confirmed account deletion in Android Settings and a public web flow. Successful deletion removes cloud account descendants, owned email indexes and account-specific counters. Missing/deleting accounts cannot write financial records using stale tokens or cookies. Local Android cleanup happens after server confirmation and targets only that account. Other saved accounts and shared voice models remain. Offline copies on other devices may remain until their saved account or app storage is removed.

Verify actual provider/hosting log retention, backup deletion, support handling and review-account access before finalizing declarations. Do not invent fixed retention periods. The source does not configure automatic expiry deletion for shared network rate-limit records.

## Local verification isolation

The main dev app can use the Firestore emulator on port 8085. Run release checks under a separate emulator-only project so their cleanup cannot erase that app's database:

```sh
cd apps/web
BUDGETWISE_TEST_PROJECT_ID=demo-budgetwise-play-release npm test
npx tsc --noEmit
npm run build
```

The default test project remains `demo-budgetwise`; the override accepts only `demo-` project IDs. Never run two test processes against the same project namespace. No production database is needed for these checks.

## First release order

1. Finish and verify account registration; create the app and configure signing continuity.
2. Review and deploy the backend/privacy/deletion changes; verify public policy pages and deletion using only an explicitly authorized disposable account.
3. Upload the verified signed AAB to internal testing, complete Console declarations and App access, and review the Play pre-launch report.
4. Share the Play test opt-in link with friends. Once ready, start the required closed test and record feedback.
5. Apply for production access after the required closed testing, address review feedback and choose a production rollout.

No Console payment, app creation, credential upload, reviewer-account creation, Play submission or production rollout is implied by preparing these files.
