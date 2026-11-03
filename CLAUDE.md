# BudgetWise

The Android app is in `apps/android` and the web app plus the `/api/v1` mobile API in `apps/web`. See `README.md`
for setup and `docs/repo-layout.md` for how the folders fit together.

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
- The Firestore emulator on port 8085. `npm test` reuses a running emulator and clears it between tests, so web
  tests running in two worktrees at once wipe each other's data. The run that started the emulator also stops it.

### Checks

- Android, from `apps/android`:
  `./gradlew :asr:testDebugUnitTest :app:testDebugUnitTest :core:data:testDebugUnitTest :feature:voice:testDebugUnitTest :asr-demo:testDebugUnitTest :nlu:test :app:lintDebug :asr:lintDebug :core:data:lintDebug :feature:voice:lintDebug`
- Web, from `apps/web`: `npx vitest run` and `npx tsc --noEmit`. `tsc` doesn't pass cleanly yet, so compare its
  error count with master's.

### GitHub

- The repo is `codes71/BudgetWise` and it is public. Use the `codes71` account on each command with
  `GH_TOKEN=$(gh auth token --user codes71) gh ...`. Don't run `gh auth switch` (other sessions share it), and
  never `git config --global`.
- Open PRs against `master`. Merging to `master` deploys the web app to production, so only the user merges.

### Finishing

Once the branch is merged, remove the worktree:

```sh
git -C <worktree-path> status --short   # must print nothing
orca worktree rm --worktree path:<worktree-path> --json
```

Never pass `--force`. Removing a worktree deletes its ignored files (`local.properties`, `node_modules`, build
folders), which is expected. Leave worktrees that belong to other sessions alone. Each Android worktree takes 1 to 2
GB, so don't keep merged ones around.
