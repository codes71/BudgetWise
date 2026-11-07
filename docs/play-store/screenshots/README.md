# Pocket Peek phone screenshots

The four upload files in `en-US/` are actual screenshots of the current native app rendered at **1080 × 1920 pixels (9:16)**. They use the existing UI, approved Folded Pocket assets and synthetic financial fixtures. No screen was resized, cropped, retouched, placed inside a device frame or given a marketing overlay.

## English upload set

1. [Transactions, light](en-US/01-transactions-light.png): balance, monthly spending and the transaction list.
2. [Budgets, light](en-US/02-budgets-light.png): monthly plan and category allocations.
3. [Transactions, dark](en-US/03-transactions-dark.png): income, expenses and a grouped receipt in the actual scrolled ledger.
4. [Budgets, dark](en-US/04-budgets-dark.png): the same monthly plan using the app's existing dark theme.

These four English screenshots are sufficient for the requested pack. The two files in `my/` are optional Myanmar dark-mode captures, not a request to create an additional Play Console localization. The optional Settings capture faithfully includes the existing voice-unavailable guard on this 2 GB emulator.

## Original captures and technical export

Each locale's `originals/` folder contains the untouched `adb exec-out screencap -p` PNG and its corresponding UI hierarchy XML. The top-level PNGs in each locale are the upload exports.

Every export is an opaque 24-bit RGB PNG. The original alpha channel was verified fully opaque, then removed without changing any RGB pixel value. Width, height, PNG header, no-alpha mode, file size, source hash and export hash were checked. Full details are in [provenance.json](provenance.json).

The 1080 × 1920 viewport was set on the owned emulator before capture. The device's original physical size was 1080 × 2400. The source PNG itself is 1080 × 1920, so the exports do not use cropping or scaling to change aspect ratio.

Google's current [preview asset guidance](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en) requires opaque PNG or JPEG screenshots with bounded dimensions and an aspect ratio no longer than 2:1. It recommends at least four 1080 × 1920 portrait app screenshots for recommendation formats. This pack meets those technical geometry and file-format checks. Play Console upload and listing review remain separate.

## Capture provenance

- App: `app.pocketpeek`, version `0.3.0` (4), optimized `profile` build signed with the Android Debug certificate. These are QA screenshots, not proof of installing the exact Play binary.
- Source: dirty `thura/pocket-peek-rebrand` worktree based on `9d88ab0b30624da4d4e9015799b3bb01f2e927ff`. The native source hashes remained unchanged during capture.
- APK SHA-256: `a235b455f177792ef8dc9759fb52531b94e47064b377f7360f7f1bd4e62ab3ab`. The installed APK hash independently matched the original QA artifact.
- AVD: owned `PocketPeek_QA_20261107`, API 35 Google APIs ARM64, density 420 dpi, font scale 100%. The AVD was resumed without wiping data.
- Status bar: Android SystemUI demo mode, clock 09:41, full battery, notification icons hidden and demo network indicators. This changed system chrome only.
- Backend: isolated loopback Next server and temporary local Mongo replica set, with a synthetic JWT key. No production API, Atlas, production environment file or physical phone was used.
- Fixtures: identical financial values to the original native QA run. Eight transactions total 650,000 Ks income and 239,200 Ks expenses. The native balance is 410,800 Ks. The 700,000 Ks monthly budget has 460,800 Ks remaining. All entries are synthetic.
- No app layout, typography, spacing, component, behavior, theme, source code or dependency was changed for these captures.

The original native QA report and source-hash manifest are linked by absolute artifact paths in `provenance.json`. Capture helpers, request logs, isolated resource metadata and shutdown evidence remain under `/Users/code/.codex/artifacts/pocket-peek-rebrand-20261107/native/store-capture/`.

## Shutdown and build cache

Both owned process sessions exited successfully after capture. The temporary local Mongo server stopped normally, ports 9017 and 58944 were free, and no Android devices or owned emulator/server processes remained.

The intended artifact-folder Next cache override did not take effect because the installed Next15 custom-server preparation path reloads project config. It used `apps/web/.next` and replaced local production build output. The parent was notified to rebuild after the QA server stopped. No broad deletion or source cleanup was performed. This affects local build output, not app source, screenshot pixels or production data.
