# Draft 3 native UI implementation

This change is based on master `10ec22f` and the approved [Paper snapshot](README.md). The native implementation uses real state and existing callbacks; the screenshots remain design references.

## Scope and mapping

| Paper family | Native presentation |
| --- | --- |
| Transactions / remaining balance | `ui/transactions/TransactionsScreen.kt`, compact20dp-radius balance surface,44sp primary amount with width-aware smaller roles and full-value overflow scrolling; existing balance math and spoken semantics retained |
| Spending total/category | `ui/transactions/SpendingCard.kt`, title/amount hierarchy,48dp underlined view tabs, current chart math and category expansion retained |
| App shell | `nav/AppNav.kt` bottom-bar presentation only; same route graph and callbacks, Transactions/Budgets/anchored Add order |
| Add / change entry | `ui/manual/AddManualScreen.kt`, flat amount/description,48dp choices, More sheet for the complete account category list, anchored save/delete; real IME |
| Add sheet | `ui/manual/AddSheet.kt`, compact title/close, dominant scan action, voice eligibility and type/income choices |
| Auth / language / first income / voice setup | `ui/onboarding/*Screen.kt`, `AuthPage.kt`, `ui/login/LoginScreen.kt`; existing validation, eligibility, keyboard and navigation states retained |
| Settings | `ui/settings/SettingsScreen.kt`, compact account/sync/voice/display groups; all sync/download/cloud-text/language/digits actions retained |
| Voice | `ui/voice/VoiceScreen.kt`, level-driven waveform, labeled example and real Done/Discard/Type controls; post-recording transcription flow retained |
| Camera / receipt / KPay review | `ui/scan/ScanScreen.kt`, `ScanReview.kt`, `ItemEditor.kt`; real camera/photo, editable receipt counts/prices and48dp controls; actual payment/history selection behavior retained |
| Saved receipt / change items | `ui/receipt/ReceiptScreen.kt`, aligned category/item totals and primary actions; category move only where the existing callback permits it; merged TalkBack entry target retained |
| Budgets | Reference snapshots included. Budget screen/feature implementation belongs to the separate `feat/android-budgets` workstream and is untouched here. |

## Shared UI contract

- Header titles use24sp with a compact minimum56dp app bar or64dp pushed header; system insets remain native.
- Primary actions use18dp corners and minimum56dp height; secondary actions16dp corners, text actions at least48dp. Heights may grow for large text.
- `KitFieldStyle.Underlined` is an optional field treatment. The default remains `Filled`, preserving existing and upcoming budget-form callers.
- Existing light/dark colors and Schibsted Grotesk/Padauk typography remain authoritative. The two static welcome shader images are generated design output, not a React/WebGL dependency.

## Deliberate differences from static mockups

The system provides the numeric keyboard and camera preview. Mockup digits, receipt photos and sample entries are not substituted for live app state. Master has no camera flash/Type callback or KPay category-move action, so those unsupported mockup affordances are omitted. Receipt category moves and all existing actions remain available. The complete category list can include account-specific values beyond the built-in choices.

## Verification

On2026-11-02, using the project's Android Studio JBR and local SDK:

```sh
./gradlew --continue :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Passed:263 tests,0 failures/errors/skips; lint0 errors (75 warnings, no clean-baseline comparison claimed); debug APK produced. Checks include English/Myanmar manual-form fit, existing navigation and offline sign-in behavior, receipt grouping/actions and the new More-to-custom-category interaction. The debug APK is a build artifact, not a device acceptance result.

Source review confirms no ViewModel, repository, API, database, migration, web or budget-feature file changes. Independent reviews checked the preserved UI state/action paths. Better Design automated comprehension/spacing review remains unavailable due to service quota. No physical-device install, production deployment, merge or complete native visual/accessibility acceptance is claimed. The PR stays draft for device visual review and eventual budget integration.
