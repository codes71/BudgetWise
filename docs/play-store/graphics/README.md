# Pocket Peek local store graphics

- `store-icon-512.png`: 512 x 512 opaque RGB PNG, from the approved Folded Pocket icon. Only PNG encoding changes from the approved handoff source.
- `feature-graphic-1024x500.svg`: editable deterministic composition of the approved logo and truthful copy, “See where your money goes.” Text is outlined Nunito ExtraBold, licensed under `brand/fonts/OFL.txt`.
- `feature-graphic-1024x500.png`: 1024 x 500 opaque RGB PNG exported from that SVG.

Regenerate with `DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib uv run --script brand/render.py --target store` from the repository root. The generator uses canonical approved artwork in `brand/` and does not write Android resources.

These are local candidate assets, not uploaded or approved Play graphics. No screenshots are fabricated here. Capture fresh, verified native Pocket Peek screens with synthetic data for the phone screenshot slots. Old BudgetWise or design-draft previews are historical evidence only.
