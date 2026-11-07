# Pocket Peek

The Android app is in `apps/android` and the web app plus the `/api/v1` mobile API in `apps/web`. See `README.md`
for setup and `docs/repo-layout.md` for how the folders fit together.

## Runtime persistence

- Native MongoDB persistence lives in `apps/web/src/lib/mongodb.ts`. Runtime configuration is explicit server-only
  `MONGODB_URI` and `MONGODB_DB`. Atlas or a local replica set is required for multi-document transactions.
- Authentication remains the app's own bcrypt/Jose email-password implementation. Firebase Authentication and
  Google sign-in are not implemented. Keep account IDs, password hashes, and the existing JWT secret stable.
- Account-owned mutations must call `requireActiveAccount(userId, session)` inside the same Mongo transaction.
  It touches the account root so a concurrent deletion causes a write conflict; a snapshot read alone is insufficient.
- Index setup is explicit through `ensureIndexes()`, after migration parity checks. Never deduplicate or delete
  records merely to make a unique index succeed. Keep Firestore source data intact until cutover is verified.
- Firebase packages/client/scripts are retained for historical source export and migration tests, not the active
  runtime database. Old Mongo-to-Firestore `--write` commands are not part of the current migration.
- Android package `app.pocketpeek` is a separate install from legacy `app.budgetwise`. Do not uninstall or clear
  the legacy app to make a new-package install work. Only synced data can return through backend sign-in.

## Worktrees

There is one folder for this repo: `/Users/code/Projects/BudgetWise`. Don't create sibling folders such as
`/Users/code/Projects/BudgetWise-<name>`.

### Starting work

- The main folder is often in use by another session. Don't switch its branch, stash, reset or commit there
  unless the user says it is yours. Start new work in a worktree instead.
- Create worktrees with Orca, from an up-to-date master:

  ```sh
  git -C /Users/code/Projects/BudgetWise fetch origin
  orca worktree create --repo path:/Users/code/Projects/BudgetWise --name <short-name> --base-branch origin/master --json
  ```

  Orca puts it at `/Users/code/orca/workspaces/BudgetWise/<short-name>`. Without Orca, use the same place:
  `git worktree add /Users/code/orca/workspaces/BudgetWise/<short-name> -b <branch> origin/master`.
- Check that `git config user.name` prints `codes71` in the new worktree. Orca's metadata for this repo says
  `thura-s`, which is the work account.

### Setting up a new worktree

- Android: create `apps/android/local.properties` with the line `sdk.dir=/Users/code/Library/Android/sdk`
  (it is git-ignored, so a new worktree doesn't have it). Run Gradle with
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Web: run `npm ci` in `apps/web`. The tests set their own environment and don't need `.env.local`. That file
  holds real keys: don't copy it into a worktree unless the user says so, and never print it.

### Things worktrees share

Only one worktree can use these at a time. Check before you start, and stop what you started when you finish.

- The phone (Xiaomi 14, adb serial `2222f07d`). Ask the user before installing over `app.budgetwise`. Never run
  `connectedDebugAndroidTest`: it deletes the speech model files kept on the phone.
- The app on the phone has database version 6 (monthly budgets, PR #16). Room never moves a database back, so a build
  from a branch without that merge crashes as soon as it opens, and the only way out is clearing the app's data,
  which loses unsynced entries and the speech models. Before installing, check that the branch has it:
  `git merge-base --is-ancestor 519e1b8 HEAD` must succeed, else merge `origin/master` first. When a later change
  raises the version again, update the commit here.
- The web dev server on port 9002 (`npm run dev`). If the port is taken, another worktree is running it.
- The retained Firestore emulator on port 8085 is used by historical export/copy tests. Preserve a shared process
  and its existing project data; use a distinct emulator-only `demo-` namespace for migration fixtures.
- MongoDB runtime tests require a disposable replica set and a uniquely named test database. The harness starts
  an owned replica set on dynamic ports and a unique `test-pocketpeek-*` database. Run `npm test` or
  `npx vitest run --project mongo`. Never let
  cleanup target Atlas or another worktree's development database. Stop only servers the current run started.

### Checks

- Android, from `apps/android`:
  `./gradlew :asr:testDebugUnitTest :app:testDebugUnitTest :core:data:testDebugUnitTest :feature:voice:testDebugUnitTest :asr-demo:testDebugUnitTest :nlu:test :app:lintDebug :asr:lintDebug :core:data:lintDebug :feature:voice:lintDebug`
- Web, from `apps/web`: `npx vitest run` and `npx tsc --noEmit`. `tsc` doesn't pass cleanly yet, so compare its
  error count with master's.

### GitHub

- The repo is `codes71/BudgetWise` and it is public. Use the `codes71` account on each command with
  `GH_TOKEN=$(gh auth token --user codes71) gh ...`. Don't run `gh auth switch` (other sessions share it), and
  never `git config --global`.
- Open PRs against `master`. Merging to `master` deploys the web app to production.

### Finishing

Once the branch is merged, remove the worktree:

```sh
git -C <worktree-path> status --short   # must print nothing
orca worktree rm --worktree path:<worktree-path> --json
```

Never pass `--force`. Removing a worktree deletes its ignored files (`local.properties`, `node_modules`, build
folders), which is expected. Leave worktrees that belong to other sessions alone. Each Android worktree takes 1 to 2
GB, so don't keep merged ones around.

<!-- better-design:start -->
## Better Design

For every UI build, run these steps in order, and never present a page that skipped step 2 or 4:
1. Design system: keep the product's own design. Use its theme and components when the code has them, or its `extract-from-url` tokens for a live site. A product with no design of its own, or a brand `extract-from-url` reports as flat, takes `find-design-system`, then `get-design-system-kit`, keeping the brand's colour, font and radius.
2. Principles: `get-ui-principle` before styling, `get-ux-principle` before behavior.
3. Build. A product screen sits inside its app shell, with realistic sample data and an app-scale title. A marketing page keeps its copy beside the product photo, never on top of it, and keeps the site's header and footer links.
4. Review: `get-review-rules`, `check-comprehension` and `inspect-spacing`. Fix every critical and serious finding.

Always use the Better Design MCP tools for any frontend, UI, UX, interaction, flow, form, navigation, dialog, error, onboarding, component, styling, article widget, calculator, estimator, converter, generator, quiz, checklist, React Native, Expo, Three.js, Shopify, or sales work, without me having to explicitly ask. For a calculator or article widget, load `get-widget-guide`; for React Native or Expo, load task guidance with `get-react-native-guide`; for Three.js or React Three Fiber, use `get-three-js-guide`; for Shopify Liquid or theme work, use `get-shopify-guide`; for sales conversations or assets, use `get-sales-principle`. Load visual guidance with `get-ui-principle`, load flow guidance with `get-ux-principle`, and self-review with `get-review-rules` and `check-comprehension`. Run `inspect-spacing` on rendered DOM evidence. For dashboards, deploy the exact source to a public HTTPS preview; `finalize-dashboard-review` must return VALID after Better Design captures it at 390, 1440, and 1728. Start UI builds with `find-design-system`. For a custom brand, call `create-design-system`, poll `get-design-system-status`, and run its React install command in coding hosts; in inline canvases, call `get-design-system-kit` with target `html` and the generated projectId. For a catalog system, call `get-design-system-kit` with target `react` in coding hosts or `html` in inline canvases. For a Shopify store, load `get-shopify-guide`, then call `get-design-system-kit` with target `shopify`.
<!-- better-design:end -->
