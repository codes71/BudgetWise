# Console submission materials

Prepared for developer `thurashark`, support `thurashark@gmail.com`, portfolio `https://thuracodes.vercel.app/`. These are drafts for the owner to check against the actual Console forms. Nothing in this file establishes an app submission or approval.

## Account type and financial features

Google's [account requirements](https://support.google.com/googleplay/android-developer/answer/10788890) require an organization account for financial products and services. Its [Financial Services policy](https://support.google.com/googleplay/android-developer/answer/9876821) defines these broadly to include management or investment of money and personalized advice. The reviewed official guidance does not explicitly exempt manual expense trackers or budgeting tools.

BudgetWise Android stores income, expenses and monthly budgets, extracts draft entries from receipt images, and parses on-device recognized speech using a cloud service. It does not implement banking, custody of funds, payments, transfers, loans, trading, investments or cryptocurrency. The associated web application separately offers AI-generated spending suggestions using transaction summaries and budget goals. Do not claim that this web-only feature is implemented in Android.

Personal-account eligibility for this app is not confirmed. Obtain a Google Play Developer Support determination before certifying that eligibility or submitting BudgetWise. Do not silently switch account type, remove features, or declare no financial features to get past a form. The account itself can be used for other eligible personal apps.

All apps on closed/open/production tracks must complete the [Financial features declaration](https://support.google.com/googleplay/android-developer/answer/13849271), even when declaring no financial features. This app-level declaration is separate from developer-account eligibility. Check the relevant Other and, for any applicable web functionality, financial-advice choices with Google.

### Support request draft, not sent

Subject: Account-type guidance for a manual expense and budget tracking app

I am setting up a personal developer account under the name thurashark and would like to confirm the correct account type before submitting BudgetWise (package app.budgetwise).

The Android app lets users manually record income and expenses, maintain monthly budgets, and sync their records. Optional receipt scanning extracts draft entries for the user to review. Optional voice entry recognizes speech on the device and sends recognized text to a cloud service for parsing. The app does not connect to bank accounts, hold or move funds, offer loans, facilitate payments, or provide trading, investment or cryptocurrency services.

There is also a related web application that can generate personalized spending suggestions from a user's transaction summaries and budget goals. This suggestions feature is not part of the Android interface.

Does this functionality require an Organization developer account under your Financial Services policy, or can the Android app be published from a Personal account? Which Financial features declaration choices should be used for the Android functionality described above?

## Foreground service declaration draft

Service type: `dataSync`.

Feature: user-initiated download of the optional Burmese speech-recognition model pack, approximately 1.1 GB. Once installed, Android performs recognition locally. The download uses WorkManager's foreground service with an ongoing progress notification. The user can cancel from the app and can continue using manual entry without the voice pack.

Why foreground execution: the requested pack is large and may take longer than an ordinary short background operation. Foreground execution keeps its progress visible while the user waits or leaves the screen. Delaying or interrupting the download delays voice availability; the app does not mark the pack ready until all required files pass size/hash checks. The download implementation supports partial-file recovery on a later attempt.

Demonstration checklist: start the download from the actual app, show the in-app progress, show the ongoing Android notification, return to the app and cancel. Use the exact release implementation and a supported device. Upload the resulting video to an owner-approved, reviewer-accessible location and paste its link in the Console. A locally recorded file is not yet a reviewer-accessible link. [Google FGS guidance](https://support.google.com/googleplay/android-developer/answer/13392821).

## App access and remaining declarations

- Provide Google with a dedicated working reviewer account through the Console's App access form. Do not use the owner's account or place credentials in source or public artifacts. Check login and account-restricted flows before submission.
- Use `data-safety-draft.md` for the source-based collection inventory. Confirm actual provider contracts, operational logs and backup retention before finalizing sharing/retention answers. No specific retention interval has been verified by this preparation.
- BudgetWise's Android release includes no ads or advertising SDK. Do not generalize that answer to other apps planned for this developer account.
- Choose this app's audience and content rating based on its actual intended users. The developer-account question about future children's/news apps does not decide BudgetWise's own audience.
- Use the deployed public privacy/deletion URLs after verifying that they are reachable without login. App deletion must require explicit password confirmation. Do not test by deleting an existing real account.
- Upload the signed AAB to a test track only after signing continuity, backend availability, account eligibility and required declarations are resolved. Keep the R8 mapping and signed-bundle verification receipt.
