# Monthly budgets: one total per month, split into categories

Status: approved screens (Paper file "Budget Wise", page "Budgets: set and change on Android"), 2 November 2026.

## Why

Budgets could only be set on the website, one category at a time. People think in one amount for the month
("20 lakh"), then decide how much of it goes where. Each month is planned on its own. When a month ends, the app asks
whether the money that should be left is really on hand, and lets that checked amount be added to next month.

## Words used here

- **Plan**: one month's budget. `newMoney` (typed for that month) + `carriedOver` (checked leftover added from the
  previous month) = **total**.
- **Share**: one category's part of the total (the old per-category budget: category + limit).
- **Split**: the sum of the shares. **Left to split** = total - split. It is shown as **Everything else**: spending
  in any category without a share counts against it.
- **End check**: after a month ends, the amount the user says is really on hand (`onHand`).

Amounts are whole kyat everywhere. 1 lakh = 100,000 Ks.

## Rules

1. A plan belongs to one account and one calendar month (`YYYY-MM`, Asia/Yangon on the server, device zone on the
   phone).
2. total > 0. newMoney >= 0, carriedOver >= 0. Each share limit > 0. A category appears at most once.
3. split <= total. A save with split > total is refused (422 `split_over_total`). The phone blocks it before sending
   and offers "Raise the month to X lakh" (sets newMoney so that total == split).
4. Share categories must be names from `GET /categories` for that user (422 `unknown_category`).
5. Spent per share = that month's EXPENSE entries in that category. Everything else spent = that month's EXPENSE
   entries in categories without a share. Income never counts.
6. Month leftover = total - all EXPENSE in the month (can be negative; carry is never negative).
7. The end check can be saved for a month that has a plan, only once that month is over (422 `month_not_over`).
   Saving it again replaces it.
8. carriedOver is chosen by the user on the next month's plan (default: the end-check `onHand` when there is one,
   ticked; otherwise the computed leftover, unticked). The server does not compute or enforce it beyond rule 2.

## Server (apps/web)

### Storage

`users/{uid}/budgetMonths/{YYYY-MM}`:

```
{ month: "2026-11", newMoney: 2000000, carriedOver: 280000,
  shares: [{ category: "Housing", limit: 500000 }, ...],   // order kept as sent
  endCheck: { onHand: 280000, checkedAt: Timestamp } | absent,
  createdAt: Timestamp, updatedAt: Timestamp }
```

### Wrapping the old budgets (kept, not removed)

The web Budgets page, the dashboard, AI suggestions and `GET /api/v1/budgets` (older phone builds) all read budgets
through `listBudgets(userId)`. It prefers the **current month's** plan:

- When a plan exists for the current Yangon month, `listBudgets` returns its shares in plan order as
  `{ _id: sha256(category), userId, category, limit }`. Otherwise it returns `users/{uid}/budgets` (category + limit)
  as before, oldest first. A plan saved ahead for next month takes effect on its own when that month starts.
- Saving a plan (any month) does not touch `users/{uid}/budgets`: no updates, creates or deletes. Old per-category
  rows are never deleted.
- The web `setBudget` (one category) still writes the legacy budget, so the fallback stays sensible. When a plan exists
  for the current month it also sets that share in the plan (adds it if new). If that would make split > total, it
  refuses with a message that the split would go over this month's budget of X Ks and writes nothing. Same
  transaction.
- The web Budgets page shows a line above the list when a current-month plan exists:
  "November budget: 2,280,000 Ks (22.8 lakh). 680,000 Ks not split." Nothing else on the web changes.

### API (bearer auth like the other `/api/v1` routes, `handle()` errors, zod schemas in `lib/mobile/schemas.ts`)

`GET /api/v1/budget-months?from=YYYY-MM&to=YYYY-MM` (both included, from <= to, at most 24 months)

```
200 { "months": [ MonthPlanDto, ... ] }   // only months that have a plan, oldest first
MonthPlanDto = { "month": "2026-11", "newMoney": 2000000, "carriedOver": 280000,
                 "shares": [{ "category": "Housing", "limit": 500000 }],
                 "endCheck": { "onHand": 280000, "checkedAt": "2026-12-01T02:30:00.000Z" } | null,
                 "updatedAt": "..." }
```

`PUT /api/v1/budget-months/{month}` body `{ "newMoney": int, "carriedOver": int, "shares": [{ "category", "limit" }] }`
(at most 50 shares). Creates or replaces the plan, keeps an existing endCheck and createdAt. `200 { "month": MonthPlanDto }`.
Errors: 400 `invalid_month`, 422 `validation_failed` / `split_over_total` / `unknown_category`.

`PUT /api/v1/budget-months/{month}/end-check` body `{ "onHand": int >= 0 }`. `200 { "month": MonthPlanDto }`.
404 `not_found` when the month has no plan; 422 `month_not_over`.

Tests (vitest, `tests/api/budget-months.test.ts`, `tests/web/set-budget.test.ts`): auth, validation, split over total,
unknown category, replace keeps createdAt/endCheck, listBudgets prefers the current month's plan (other months and
plan saves leave the legacy rows untouched), end check rules, web setBudget with and without a plan (adds share,
refuses over total).

## Phone (apps/android)

### :core:data

- Model `MonthPlan(month: YearMonth, newMoneyKyat: Long, carriedOverKyat: Long, shares: List<Budget>,
  endCheck: EndCheck?)` with `totalKyat`, `splitKyat`, `unsplitKyat`; `EndCheck(onHandKyat: Long, checkedAt: Instant)`.
- DTOs + `ApiService`: `monthPlans(auth, from, to)`, `saveMonthPlan(auth, month, body)`, `saveEndCheck(auth, month, body)`.
  Amounts read as Double and rounded like `BudgetRepository.toKyat` (drop a plan with a bad total; drop bad shares).
- Room v5: table `budget_month` (ownerId, month TEXT `YYYY-MM`, newMoneyKyat, carriedOverKyat, shares JSON, endCheckOnHandKyat
  nullable, endCheckAt nullable; primary key (ownerId, month)). `MIGRATION_4_5`, exported schema 5.json, MigrationTest.
  Signing out keeps rows like the other per-account tables; `claimUnowned` is not needed (ownerId is never null).
- `MonthPlanRepository` (per active account, like `BudgetRepository`):
  `observe(month): Flow<MonthPlan?>`, `observeRange(from, to): Flow<List<MonthPlan>>`,
  `refresh(from, to): ApiResult<Unit>` (replaces the cached months in that range with the server's answer),
  `save(plan): ApiResult<MonthPlan>` and `saveEndCheck(month, onHandKyat): ApiResult<MonthPlan>` (online only; on Ok the
  cache is written; saving the current month also replaces the legacy `budget` cache with the shares so voice budget
  answers agree). 401 handled like `BudgetRepository`.
- Unit tests with the existing fake server / harness.

### :app

`MonthPlanSource` interface in `AppServices.kt` (fakeable, `DefaultMonthPlanSource` delegating, bound in `di/AppModule`),
writes in `NonCancellable` like `DefaultTransactionStore`.

Screens (match the Paper drafts; existing theme, components and copy style; every string in `values` and `values-my`):

- **Budgets tab** (S1, S0). Current month plan: header "Edit plan" pill (opens the plan editor for this month); hero
  "{Month} budget · X lakh", big amount left ("Ks left", coral and "over" when negative), "spent of total", bar; one
  row per share (spent of share, left/over, percent; existing row style), then the Everything else row (dashed wallet
  badge, spent outside shares of the unsplit amount; hidden when unsplit is 0 and nothing was spent outside shares);
  over first, then most spent. No plan for this month: S0 (headline, explainer, "Set {Month}'s budget", "You spent X Ks
  in {previous month}"). If the previous month had a plan without an end check, S0 and S1 show a small card
  "{Month} ended with X Ks left. Check it" that opens the end check. Pull to refresh refreshes plans (previous and
  current month) and transactions as today.
- **Plan editor**, one route `budget_plan/{YYYY-MM}`, two steps in one ViewModel, system back goes from step 2 to 1.
  - Step 1 (S2, M4): "New money for {Month}" amount (numeric keyboard, grouped digits, lakh line under it), chips
    10/15/20/25 lakh, "What you spent before" (last 3 months' expense totals) when there is no carry card; carry card
    when the previous month has a plan (amount from its end check, else computed leftover marked "not checked yet";
    ticked only when checked); "{Month} budget" total line; "Next: split X lakh" (disabled while total is 0).
  - Step 2 (S3–S6): left to split (sage; coral "over by" when split > total), stacked split bar in category colours,
    share rows with an amount field (tap opens the share sheet), "Add a category" (sheet listing categories without a
    share, with last month's spending, "+" adds it with an empty amount and opens the share sheet), Everything else row,
    Save "{Month} budget". Share sheet (S4): big amount, "Leaves X to split", chips: last month's spending in that
    category, 3-month average, "All that's left"; "Take {category} out of the split"; Done. Over total (S6): Save
    disabled, "Raise the month to X lakh" link.
  - Starting values: an existing plan for that month; else newMoney and shares from the previous month's plan; else
    shares from the legacy budget cache (newMoney empty).
  - Save calls `MonthPlanSource.save`; on Ok go back to the Budgets tab; on failure stay and show an error line
    ("Couldn't save. Check your connection and try again.").
- **End check** (M2, M3), route `month_end/{YYYY-MM}`: "End of {Month}", left in that month's budget, "Do you have about
  this much on hand right now?", Yes / No choices, "I have" amount field for No. When onHand < leftover: "X Ks went
  somewhere that wasn't recorded." + ticked checkbox "Add it to {Month} as "Not recorded"" which, on Next, saves an
  EXPENSE entry (category "Other", description "Not recorded", dated the month's last day, source MANUAL) through
  `TransactionStore.addAll`. Next saves the end check, then opens the plan editor for the following month with the carry
  ticked at onHand.
- **Month-end notification** (M1). Channel "Budget reminders". A daily periodic WorkManager job (around 9:00 local)
  posts once per month, for the active account, on or after the 1st, when the previous month has a plan and no end
  check: title "{Month} is over: X Ks left", text "Do you really have this much on hand? Check it and add it to {Next}.",
  actions "Check it" (opens the end check) and "Remind me tonight" (one more post at 20:00 that day). At most two posts
  per month; remember what was posted per account and month. Tapping opens `MainActivity` with the month; the app
  navigates to the end check when signed in as that account, else to Budgets. Ask for POST_NOTIFICATIONS (API 33+)
  after the first successful plan save, with a one-line reason; never ask again if refused.
- Lakh text: "20 lakh", "22.8 lakh" (one decimal, no ".0"); Burmese "သိန်း ၂၀" style using the app's digit setting.
  One helper with unit tests.

Tests: ViewModel tests (Budgets tab states and sums, plan editor maths, starting values, over-total, save errors, end
check gap entry), screen tests for the main states (Robolectric, like `BudgetsScreenTest`), notification decision as a
pure function with tests, lakh helper, repository and migration tests in :core:data.

### Category transactions (implementation update, 3 November 2026)

Tapping a category share or Everything else opens `budget_entries/{budgetMonth}?budgetCategory=...`. The detail shows
the selected month's contributing expenses, grouped by date, with the category's limit, spending and remaining amount.
Income and expenses outside that month never count. Everything else follows the current plan's categories and unsplit limit.

Each entry opens the same editor as Home. Receipt entries stay individual here so an edit or deletion affects only the
selected expense, even when its receipt includes other categories. Swipe Delete and the editor's Delete entry return to
Undo on this detail screen. Leaving commits a pending deletion; rotation retains its Undo window. Failed local deletes
restore the entry and its swipe state. All writes use the existing offline-first Room/outbox path. Both budget screens
recalculate from the shared ledger after additions, edits, category/date/type changes and deletions; plan limits stay fixed.

Receipt expenses keep one shared purchase date. The editor labels it "Receipt date" and explains that changing it moves
the whole receipt. A guarded atomic replacement moves all its category entries together, including when a missing entry
is saved as new into a surviving receipt; a concurrent receipt change writes nothing and keeps the editor open to retry.
Changing an entry to income saves it separately from the expense receipt, preserving its source, merchant and items.
The category screen refreshes Today/Yesterday labels on resume without changing the selected budget month.

Focused coverage lives in `BudgetTransactionsViewModelTest`, `BudgetTransactionsScreenTest` and
`BudgetTransactionsNavTest`. Navigation tests exercise the real editor, Room and outbox, including receipt isolation and Undo.
The screens reuse BudgetWise's theme, English/Burmese resources and Home's accessible transaction rows. Better Design's
required tool checks were attempted but unavailable due HTTP 402 `AGENT_QUOTA_EXHAUSTED`; those checks remain unverified.

## Out of scope

Offline saving of plans (needs a network; the error says so). Editing past months' shares from the Budgets tab (only
the plan editor route can, and only months the tab links to). Web screens for plans beyond the one summary line.
Budgets per week or per pay period.

## Release order

The server PR goes first (merging to master deploys the web). Phone builds that call the new routes come after it is live.
