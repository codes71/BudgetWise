# Draft 3 native UI implementation

This change integrates master `0fce707`, including the completed monthly-budget feature, and the approved [Paper snapshot](README.md). The native implementation uses real state and existing callbacks. The redesign branch remains presentation-only relative to current master.

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
| Monthly budgets | `ui/budgets/BudgetsScreen.kt`, `PlanEditorScreen.kt`, `MonthEndScreen.kt`, `BudgetParts.kt`: compact remaining/split/leftover surfaces, Edit plan in the overview card, underlined amount fields, 48dp controls and scrolling sheets. Existing calculations, carry-over, category allocation, month-end checks, notifications and save/error behavior are preserved. |
| Voice change-entry form | `ui/voice/VoiceResults.kt`: place the full-width type switch below the header so the title remains visible; preserve all form actions. |

## Shared UI contract

- Header titles use24sp with a compact minimum56dp app bar or64dp pushed header; system insets remain native.
- Primary actions use18dp corners and minimum56dp height; secondary actions16dp corners, text actions at least48dp. Heights may grow for large text.
- `KitFieldStyle.Underlined` is an optional field treatment. The default remains `Filled`, preserving other callers. Budget-specific amount fields use the same underline language.
- Existing light/dark colors and Schibsted Grotesk/Padauk typography remain authoritative. The two static welcome shader images are generated design output, not a React/WebGL dependency.

## Deliberate differences from static mockups

The system provides the numeric keyboard and camera preview. Mockup digits, receipt photos and sample entries are not substituted for live app state. Current master's camera torch, capture permissions/errors and all receipt safeguards are preserved. Unsupported mockup-only actions are not invented. The complete category list can include account-specific values beyond the built-in choices.

## Verification

On 2026-11-03, using the project's Android Studio JBR and local SDK:

```sh
./gradlew --continue :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Passed: 471 tests, 0 failures/errors/skips; lint 0 errors (75 warnings, no clean-baseline comparison claimed); debug APK produced. Checks include budget setup/edit/split/carry/month-end states, English/Myanmar manual-form fit, navigation, offline sign-in, receipt safeguards and the More-to-custom-category interaction. The integration initially exposed a zero-width voice-editor title; its type switch was moved below the header, and the original test plus the full suite passed.

Nine actual Compose screen-body captures were rendered with Robolectric native graphics: overview, total, split and month-end in both themes, plus a long amount at 1.3 font scale. See [native previews](native-previews/README.md). These use sample state and omit the navigation shell. They do not establish physical-device, open-keyboard, Burmese, TalkBack or larger-font acceptance.

Source review against current master confirms no ViewModel, repository, API, database, migration or web changes. Independent review of the four budget presentation files found no high-confidence regressions. The branch inherits database version 6 from master, compatible with the installed budget build's schema; the old pre-integration Draft 3 APK must not be installed on that phone.

Better Design automated comprehension/spacing review remains unavailable due to service quota. No physical-device install, production deployment, merge or complete native accessibility acceptance is claimed. PR #15 stays open and draft for review. New features belong to the separate agent's workstream.
