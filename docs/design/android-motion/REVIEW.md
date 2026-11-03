# Motion implementation review

## Judgment, independent Assessment A

Source working diff against db3313b in android-motion. The assessment was completed before the isolated standards assessment. No standards, other reports, tests, builds or devices were read/run by the assessor.

BudgetWise motion Assessment A: approve the direction, hold implementation acceptance for an outgoing-control hazard.

| Area | Judgment |
| --- | --- |
| Actual voice waveform | Should exist. Twelve bounded bars driven by observed levels communicate microphone response without suggesting transcription. Silence rests and stopping clears history. This fits Draft 3's small sage visual. |
| Voice phases | Should exist. Crossfades make listening, working, review and answer changes legible. Payload keys avoid restarting a review when its fields change. Crossfading the entire listening backdrop for successive working steps is broader than necessary, but acceptable provisionally. |
| List continuity | Should exist. Placement changes help users track deletion, insertion and Undo without a staged entrance. Budget lists use restrained explicit specs. The main ledger still uses its pre-existing default placement animation, so the app-wide family is incomplete. |
| Conditional forms | Should exist. Local reveals explain why categories, recent suggestions and on-hand fields appear. They preserve the compact layout. Outgoing interactive content needs an immediate inactive state. |
| Budget progress | Should exist with the current restraint. Bars move toward actual values while monetary text updates immediately. No rolling amounts, celebratory overspending or fictitious progress was introduced. |
| Scan handoff | Should exist. Camera/preparing share identity, reading/problem retain the photo, and the card changes locally. These choices preserve the user's place better than replacing every intermediate screen. |
| Plan steps | A simple content transition is sufficient. Visible step labels and changed headers already establish order. The implementation uses fades in both directions, so it does not encode forward/back travel. I would not require sliding solely to add movement. |
| Receipt changes | Should exist. The first previous-value note and local mismatch/change notices help explain edits. Keeping amount/count fields mounted is appropriate. Obsolete destructive actions must disappear functionally before their visual exit finishes. |
| Download/sync states | Should exist. Determinate download interpolation supports progress; Wi-Fi waiting has meaningful resting copy. Stopping background sync rotation after five seconds while retaining Syncing is appropriate beside a ledger. |
| Shared navigation polish | Should exist at this scale. Existing short destination fades, brief selected-tab emphasis and switch feedback support orientation without competing with financial content. |

Interaction finding: immediately deactivate outgoing MotionReveal controls. MotionReveal retains its content through the shrink/fade but adds no pointer, focus, accessibility or action guard. MotionContent already attempts these guards, making the two helpers inconsistent.

Concrete reproduction from code: set all item counts to zero in Change items; Delete this receipt appears. Restore a count; allLeftOut becomes false, but Delete remains mounted during exit. Activating that outgoing action still calls the live delete callback. ReceiptViewModel.delete does not check allLeftOut. Verified locations: Motion.kt78, ReceiptScreen.kt301, ReceiptViewModel.kt182. This class warrants checking manual categories, receipt change/check-again and scan fallback actions. Visual exit must not extend functional validity.

Repeat-use and coherence judgment: assumptions are Transactions/entry daily and repeated, voice/scan repeated within a session, budgeting monthly or occasional. Under those assumptions the implementation spends motion in the right places. It avoids repeated number effects, ledger-wide entrances and decorative loops. Local changes suit the approved compact composition. Main Home row default specs versus BudgetMotion specs are a minor consistency follow-up, not proof either timing is wrong.

Animation-disabled meaning: Compose APIs should resolve to final states with zero duration scale. Meaning remains in exact figures, selected tabs, phase headings, progress text, Wi-Fi copy, errors and available controls. The waveform remains actual input feedback even if interpolation snaps. No essential financial meaning appears to depend on motion.

This is source-level confidence only. Optimized device observation is still needed for smoothness, interruption feel, keyboard/focus and disabled-animation acceptance. No other high-confidence state or lifecycle defect was established.

Resolution: shared MotionReveal now uses the same pointer/focus/accessibility guard as MotionContent. Live action guards prevent retained receipt, scan, manual and plan controls from acting on current state. Regression tests invoke obsolete Delete, Next, Save and scan fallback actions during exit and require zero callbacks. Home item specs now use BudgetMotion. The full 912-test suite passes after these corrections.


## Verdict and isolated standards assessment B

Approve with minor craft observations. Floor: 0. Obligation: 0. Taste: 3 groups remain (one pre-existing). Assessment A completed before B started. B did not read A, prior reports or tests. Neither source review makes a device performance claim.

| Rule | Observation | Resolution |
| --- | --- | --- |
| T-008 | In-place plan/scan/receipt BackHandler paths commit at gesture completion rather than previewing internal content from predictive progress. | Existing screen ownership and discard guards are retained. Full NavHost destinations retain native predictive behavior. In-place gesture preview remains a refinement; no change to committed data or cancellation behavior is implied. |
| T-011 | Ordered Total/Split steps use the same short cross-fade in both directions. | Deliberate restrained choice for this pass: visible step labels establish order and native keyboard/focus behavior remains. Independent design judgment accepted the fade; it introduces no directional back mismatch. |
| T-010 | Reveal collapse originally used the 220ms entrance token. | Corrected to the fast 150ms collapse token. |
| T-019 | Existing loading skeleton/spinner handoffs lack a delay/hold/crossfade presentation state. | Pre-existing behavior, unchanged by this motion pass. No arbitrary loading delay was introduced. |

Verified: stable keys, draw/layer state reads, duration-scale handling, static status carriers, bounded automatic background rotation and current-state action guards. Scan photo snapshots are not recycled on retake; the owner only deletes the disk cache. No new high-confidence lifecycle or destructive callback defect remains from the reviewed class.
