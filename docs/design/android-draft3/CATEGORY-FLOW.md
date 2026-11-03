# Category detail flow

This UI addition was tested on the combined PR #15 (`3161420`) and PR #18 (`eb3998f`) baseline. The user approved both merges, and PR #18 merged first into master as `3fe9dca438373d02bf61cd3cb06b90c7b6566052`. The category feature remains a separate, traceable dependency; this addition supplies its Draft 3 presentation and direct budget-editing entry.

The selected category and month stay in the route. The existing PR #18 ledger
projection supplies only that month's matching expenses, including just the
matching part of a receipt. Exact category keys, custom names and Everything
else membership remain governed by the existing feature implementation.

1. Tap a budget category to open its detail screen.
2. Tap a transaction to use the existing editor. Delete and Undo belong to the
   category list. PR #18's shared receipt-date and standalone-income rules apply.
3. Use **Edit budget** to open the existing plan split with that category's
   share sheet selected. **Done** returns to the split review; **Save** returns
   to the same detail screen. Transaction spending is not changed by this action.
4. **Edit split** on Everything else opens the split without inventing an
   individual share. A removed share is never silently recreated.

The Paper references use the existing Draft 3 light/dark tokens, 24px headers,
44px normal amounts, 20px inset summary corners and at least 48px tap targets.
Native amounts retain PR #18's full-value autosizing at 320dp and font scale 2.
The existing localized count/action hint can wrap below the Transactions heading. At narrow effective widths, the native month label stacks above its edit action so large text retains complete words; the overview uses the same adaptive header.

| State | Light | Dark |
| --- | --- | --- |
| Category transactions | [Light](previews/category-detail-light.webp) | [Dark](previews/category-detail-dark.webp) |
| No matching expenses | [Light](previews/category-empty-light.webp) | [Dark](previews/category-empty-dark.webp) |
| Everything else | [Light](previews/category-else-light.webp) | [Dark](previews/category-else-dark.webp) |
| Category budget draft | [Light](previews/category-edit-budget-light.webp) | [Dark](previews/category-edit-budget-dark.webp) |

`category-detail-manifest.json` records source and preview hashes. The existing
split review and transaction editor designs are reused. New UI code changes no
API, database schema or financial calculation. Physical-phone testing has stopped
at the user's instruction; the other chat owns emulator operation and synthetic
account acceptance. The combined local source passes 894 tests, app lint with zero errors (75 warnings), and profile assembly. The final responsive build also passed English/Myanmar font-scale-2 and keyboard checks on the API 35 emulator. [Synthetic captures](category-emulator-previews/README.md) record its exact APK/source identity. See [implementation verification](IMPLEMENTATION.md). Better Design automated checks remain quota-blocked.
