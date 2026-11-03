# Category emulator evidence

Actual API 35 emulator captures on 2026-11-03, 360dp wide with system font scale 2, dark mode and Myanmar digits enabled. The English capture changes UI language while retaining the digits preference. All entries belong to an authorized synthetic QA account; no real phone data is included.

The month header remains readable and Edit budget is reachable in English and Myanmar. The existing numeric keyboard's Done action hides the keyboard without discarding the draft. Scrolling the share sheet then exposes its Done button, as shown in the final capture.

`manifest.json` records the exact installed APK, Android source tree and capture hashes. The emulator's actual reported memory is about 2.5 GB, below the voice eligibility threshold. This evidence supports the category/header and share-sheet paths, not complete app, TalkBack or physical-phone acceptance.

| Capture | File |
| --- | --- |
| English category, font scale 2 | [Image](category-english-font2-dark.png) |
| Myanmar category, font scale 2 | [Image](category-myanmar-font2-dark.png) |
| Myanmar share Done after IME dismissal and scrolling | [Image](share-myanmar-font2-done.png) |
