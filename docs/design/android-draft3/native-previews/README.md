# Native budget previews

Actual Compose screen-body captures rendered on 2026-11-03 with Robolectric
native graphics at 390×844. Sample state comes from the existing budget UI test
models. These captures omit the app navigation shell, phone status bar and real
keyboard. They contain no phone/account data.

| Screen | Light | Dark |
| --- | --- | --- |
| Overview | [Light](overview-light.png) | [Dark](overview-dark.png) |
| Total | [Light](total-light.png) | [Dark](total-dark.png) |
| Split | [Light](split-light.png) | [Dark](split-dark.png) |
| Month-end | [Light](month-end-light.png) | [Dark](month-end-dark.png) |

[Long balance at 1.3 font scale](overview-long-amount-font-1.3.png) checks the
12,344,222,401 Ks example. Category labels can ellipsize while retaining their
full spoken semantics. Very long values can scroll horizontally.

All nine render cases passed and were visually inspected. The temporary export
fixture was removed after capture. The final permanent app suite passed 471
tests. These previews are not device acceptance, TalkBack testing, Burmese
render verification, open-sheet IME verification or coverage above 1.3 font scale.
