# Draft 3 Android motion implementation

The requested animation pass extends the approved Draft 3 Android UI from master `db3313b`.
The [editable Paper reference](https://app.paper.design/file/01M637VVENYNCKYK0T90TRWSMT/p-4-0)
contains a separate Motion contract board beside the preserved screen designs. Its exact
source export is `paper-motion-contract.json`. Paper records intent and resting states;
native Compose checks establish animated behavior.

## Implementation map

| Audit area | Native implementation |
| --- | --- |
| Voice audio feedback | `VoiceWaveform` keeps bounded actual level changes; `VoiceScreen` draws and smooths twelve recent levels, clears silence, and retains visible status/timer. No sampling timer or fabricated speech. |
| Voice phases | Snapshot-based content keys connect listening, working, problems, review and answers. Review field changes keep the same editable identity; exiting actions are inactive. |
| List continuity | Main ledger, budget shares, plan shares and category transactions use stable keys with the shared insertion/placement specs. Swipe removal keeps fadeOut disabled, and Undo restores one row. |
| Conditional forms | Entry type/category sections, month-end fields/gap explanations, answer details and errors reveal locally while native keyboard/focus and pinned actions remain. |
| Budget progress | Bars interpolate toward actual fractions in draw. Amounts, labels and validation update immediately. No financial number counting or spring overshoot. |
| Scan handoff | Camera/preparing share identity; reading/problems retain the photo. Cards and review use distinct state snapshots. Back handlers remain owned by the live stage. |
| Plan steps | Total/Split content swaps retain each snapshot and allow reversal. Exiting controls cannot invoke callbacks for the current step. Live share/add sheets remain outside the step transition. |
| Receipt changes | Detail/editor swaps preserve form identity while typing. Changed/mismatch notes reveal; zero-count rows remain restorable; exiting destructive controls are guarded. |
| Download/sync states | Download bars follow reported progress and freeze for Wi-Fi waiting. Setup/status content transitions locally. Background sync rotation stops after five seconds with static Syncing retained. |
| Shared polish | Named navigation fades, tab/disclosure feedback, draw/layer-only spinner/switch updates and explicit sign-out work text. Existing native sheets, notices and gestures retain their behavior. |

The shared `BudgetMotion` contract is documented in root `MOTION.md`. Its new timings are
craft defaults (rung 2), pending PR review. The existing approved layout and brand are retained.
No dependencies, APIs, ViewModels, persistence, schema, model eligibility or financial
calculations are changed.

## Accessibility and interaction

Shared helpers show full resting state when animation duration is zero. Exiting content is
hidden from accessibility and blocked from pointer/focus input. Material controls and exact
text retain their semantics. Snapshot keys describe visual identity rather than every typed
value, so forms stay mounted during edits. The requested implementation does not require
reinstalling speech models or collecting user microphone input for a preview.

## Verification

All 912 tests pass (app 551, core/data 235, ASR 111, voice 3, ASR demo 10, NLU 2), with zero failures/errors/skips. App/ASR/core-data/voice lint passes with zero errors. Final app lint has 75 warnings, matching the original count after correcting a new private parameter-order warning. Optimized profile assembly passes. Focused transition tests cover outgoing/incoming payloads,
interrupted reversal, zero-duration final content/actions, draft restoration, inactive old
controls, exact totals and bounded background motion. The native voice preview and [independent review](REVIEW.md) are recorded below. Optimized API35 emulator verification passed: category detail, loaded direct share editor, share/split/total/back navigation, income/expense reveal reversal, animation-disabled final actions and font-scale-2 dark category layout. The existing synthetic account stayed signed in; no financial values were entered or saved. Emulator display/animation settings were restored. Real-microphone response and physical-device frame timing remain separate acceptance checks.

Better Design was attempted and remains unavailable with HTTP402 `AGENT_QUOTA_EXHAUSTED`.
The installed Compose motion guides, native tests and independent source review are used;
no automated Better Design approval is claimed.


## Native voice preview

[Short waveform preview](voice-synthetic-preview.mp4) is rendered from actual Compose with a scripted sequence of synthetic microphone levels. It demonstrates the presentation only, with no real audio, ASR run or physical-device timing claim. [Silence](voice-silence.png), [observed levels](voice-observed-levels.png) and [processing](voice-processing.png) show complete resting states. The temporary export fixture was removed from the source after capture. File hashes are in `native-preview-manifest.json`.


## Optimized emulator evidence

The profile APK was verified after installation by its full SHA256. The emulator is API35,720x1280,density320, with reported MemTotal2,532,240kB (about2.5GB). [Category details](emulator-category.png) and [animation-disabled, font-scale-2 dark mode](emulator-animation-off-font2-dark.png) use only existing synthetic QA entries. This low-memory device cannot run voice recognition; voice visuals are covered by synthetic native renders and tests, with real-microphone/physical timing acceptance still pending.
