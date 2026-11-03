# BudgetWise Android motion

Implementation contract for the requested Draft 3 animation pass, pending PR review.
The approved Paper layout, colors, typography and financial behavior remain the design source.
Reference: the Motion contract board on the Redesign : Codex page of the existing Paper file.

## Purpose and frequency

Routine Home, entry and voice interactions use fast, local feedback. Budget steps use a short
content transition. No staggered ledger entrances, celebratory financial figures or decorative
loops are used. Real amounts update immediately; animation never represents intermediate money.

## Tokens and fallback

`BudgetMotion` centralizes effects (150ms), content/layout changes (220ms), microphone response
(80ms) and linear work rotation (900ms). These are craft defaults (rung 2), preserving the
existing restrained navigation timing rather than introducing Expressive bounce. The token
object is the single place to tune them. Existing native sheets, gestures and notices retain
their platform behavior.

## Behavior

- Voice bars show a rolling history of actual input levels, with no invented speech or live transcript.
- Voice and scan phases transition only on true state changes. Cancel and retry retain their behavior.
- Stable keyed rows move on insertion, removal, reorder and Undo without replaying an entrance for the whole list.
- Conditional fields and notes reveal locally; keyboard insets and pinned actions retain native behavior.
- Progress interpolates only toward actual values. Wi-Fi waiting rests; financial text stays exact.
- Automatic background sync rotation stops after five seconds while its static Syncing label remains visible.
- Top-level destinations use fades. Plan steps are ordered; back reverses any directional travel.

## Accessibility and performance

Use Compose animation APIs so duration-scale zero snaps to complete final states. Labels, selected
states, exact values and controls communicate without movement. No custom frame-clock loops or new
shader runtime are introduced. Read frame-rate values in draw, layer or placement lambdas. Do not
animate padding or size when a visual transform suffices. Preserve English/Myanmar digits, font
scale 2, TalkBack semantics and touch targets. Optimized physical-device verification is required
before any smoothness claim.
