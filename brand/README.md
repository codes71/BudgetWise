# Pocket Peek brand assets

Active direction: **01, Folded Pocket**, explicitly selected by the owner. `selection.json` records the approval and its limited scope: logo, icon assets and existing four colors. Keep the current app layout, typography, spacing, components and behavior. This asset integration does not change global fonts or theme tokens.

The SVGs reconstruct the approved generated direction with editable geometry. The logo's Nunito ExtraBold lettering is outlined. It requires no runtime font download and is not the product UI font.

## Canonical sources and exports

| Path | Purpose |
|---|---|
| `symbol-color.svg`, `symbol-forest.svg`, `symbol-mint.svg` | Color and one-color Folded Pocket symbols. |
| `logo-horizontal*.svg`, `logo-stacked.svg`, `wordmark.svg` | Outlined Pocket Peek logo lockups, including reverse mint. |
| `store-icon.svg`, `launcher-square.svg`, `launcher-round.svg` | Approved square store artwork and launcher artwork sources. |
| `icons/*.svg`, `icons.json`, `sprite.svg` | Twenty approved custom icons with a 24-unit grid, 2-unit rounded strokes. |
| `source/store-icon-512.png`, `source/favicon-{32,64}.png` | Approved handoff PNGs retained without modification. Web logo uses the exact 512px PNG. ICO export uses these favicon inputs. |
| `source/approved-original.png` | Original generated direction selected by the owner; provenance, not an app screenshot. |
| `fonts/` | Nunito source fonts, upstream/source hashes and full SIL Open Font License 1.1. Used only for outlined brand collateral. |
| `logo-mark.svg`, `logo-mark-mono.svg`, `logo-wordmark.svg` | Generated compatibility aliases of the current store icon, forest symbol and horizontal logo. |
| `export-manifest.json` | SHA-256 inventory of current web/component/store outputs. |
| `archive/budgetwise/`, `concepts/`, `previews/` | Inactive historical BudgetWise sources, explorations and previews. Never use them as the active brand. |

## Colors and use

| Role | Value |
|---|---|
| Forest | `#143429` |
| Sage | `#6CB299` |
| Mint | `#F3FCF8` |
| Coral | `#E76E50` |

Retain the original geometry, proportions and color assignments. Use forest symbols on light backgrounds and mint symbols on forest. Forest on mint has 12.94:1 contrast; forest on sage 5.45:1. The complete measured pairs are in `contrast.json`. Coral on mint is decorative, unsuitable for normal text or meaningful icons. Do not apply these four colors as new app theme values.

The React icon exports inherit `currentColor`, matching the existing call sites' tints in both themes. Caller dimensions, stroke overrides, forwarded refs and accessible labels are supported. Decorative icons remain hidden from assistive technology. Unsupported meanings keep their existing icons, including delete, visibility, theme switching and trend-down.

## Regeneration

Run from the repository root. The script declares pinned Python rendering dependencies for `uv`; it adds no frontend dependency. macOS needs the existing Homebrew Cairo library path:

```sh
DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib uv run --script brand/render.py --target all
```

Use `--target web` or `--target store` to export one set. `web` writes `apps/web/public/logo.png`, `favicon.ico`, `public/brand/` SVGs and `src/components/brand/pocket-peek-icons.tsx`; `store` writes `docs/play-store/graphics/` source SVG and PNGs. The feature graphic uses outlined licensed Nunito for “See where your money goes.” and the approved logo on mint. It contains no screenshot, rating or invented product result.

This renderer **never writes Android resources**. The approved launcher resources were integrated separately by the Android worker. Do not run the superseded renderer preserved as `archive/budgetwise/render.py.txt`.

Edit the canonical root SVGs or `icons/*.svg`, then regenerate. Changes to approved geometry or colors need the owner's review. Keep the approved input PNGs as provenance; a future re-approved logo should replace both its SVG and PNG inputs deliberately.

## Verification and limits

The web header continues using `/logo.png`; login/signup replace only their former generic logo glyph, at the existing size. The root metadata still points to `/favicon.ico`. Custom icons replace only matching existing meanings; no layout/class/interaction redesign is included.

The Play icon and feature graphic are local upload assets. Fresh native Pocket Peek screenshots, deployed web checks, Play upload and store review are separate checks. Historical design previews are not current screenshots. No deployment, approval or successful device migration is implied by this asset inventory.
