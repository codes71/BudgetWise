# BudgetWise repository layout

Written 2026-10-29 on branch `chore/monorepo` (worktree `/Users/code/Projects/BudgetWise-mono`). Nothing here
has been pushed or deployed.

## The layout

```
.github/workflows/  one pipeline per deployment (see "CI" below)
apps/web/           Next.js web app and the /api/v1 mobile API, deployed on Vercel
apps/android/       Android Gradle project: app, core/data, nlu, feature/voice, asr, asr-demo
brand/              logo and icon sources; render.py writes into both apps
docs/               plans, specs and this file
README.md  .gitignore  .gitmodules
```

`brand/` and `docs/` stay at the root because they belong to both apps. Each app keeps its own `.gitignore`;
the root one only covers repo-wide files. Old notes that came with the web app (`GEMINI.md`, `Issues.md`,
`.modified`) moved with it into `apps/web/`. Dated plans under `docs/superpowers/` still name the old paths
(`android/...`, `src/...`); they are records of what was planned at the time and were not rewritten.

## What each branch held (survey, 2026-10-29)

Compared with `feat/m1-android` (head `fa88747`), the most complete line.

| Branch | Head | Not yet in feat/m1-android | Pushed |
|---|---|---|---|
| master | 3a7d799 | nothing (75 commits behind) | yes |
| origin/production | b57fa59 | 1 commit, Vercel fixes from 2025-08-02 | yes |
| feat/m1-api | cd6c5ee | nothing | yes |
| feat/ws3-raj-asr | 1fd9835 | nothing | yes |
| ws3-spike | ee0e814 | 2 commits, but both are already in as 8f51436 and 54f14a0 | yes |
| feat/claude-ai-android-voice-plan | aeb5952 | nothing | yes |
| feat/firestore | 165b047 | 4 commits, final | no upstream |
| feat/android-accounts | dc6c14c | 7 commits, final | no upstream |

`chore/monorepo` starts at `fa88747` and merges the two branches that were not fully in:

- `ws3-spike`: its only conflict was `android/asr/host/spike/RESULTS.md`; the feat/m1-android copy is the newer
  superset (it adds the Gate G2 results), so that side was kept. The tree did not change.
- `origin/production`: conflicts in `package.json`, `package-lock.json` and `tsconfig.json`, plus a second
  `webpack` key that the auto-merge put into `next.config.ts`. feat/m1-android already has each fix in a newer
  form (typescript is a runtime dependency, the `@` alias and `baseUrl` exist), so its side was kept everywhere.
  The Genkit build step and `src/ai/genkit-build.ts` were dropped: the AI flows now call Claude. The tree did not
  change.

After the move and path fixes, both feature branches were merged at their final heads:

- `feat/firestore` at 761dd38 (merge 02031a3), then its Java 21 fix 165b047 (merge 5600d1b, no conflicts): git
  carried every changed and new file into `apps/web`. The eleven Mongo-era files it deletes came up as
  rename/delete conflicts and were deleted. `firebase.json`, which it adds at the repo root, was moved to
  `apps/web/firebase.json` next to `.firebaserc`.
- `feat/android-accounts` at 6326b23 (merge 01ec203), then 2c2b0a7 (top bar, merge 57f06e0) and dc6c14c
  (profile build type, merge add6c85): files landed in `apps/android`, including the Room v3 schema
  `core/data/schemas/.../3.json` and the new tests. Its two deleted files (`ic_dns.xml`, `AuthInterceptor.kt`)
  were deleted. One new file in a new folder, `app/src/profile/res/xml/network_security_config.xml`, stayed at
  the old `android/` path and was moved by hand; the old-path check below is what caught it. The `profile`
  build type keeps `signingConfig = debug` next to this branch's env-based release signing.

In both merges every file from the branch is byte-identical under `apps/`, except files this branch had already
edited for the move, which the feature branches did not touch.

Verified on the merged tree (2026-10-29): in `apps/web`, `npm test` (Firestore emulator) passes 109 of 109 and
`npm run build` passes with no database settings; in `apps/android` (at dc6c14c), unit tests pass for :core:data
(166), :app (115) and :nlu (2), and `:app:assembleDebug`, `:app:assembleProfile` and lint (0 errors) pass. With the
release signing variables set to a throwaway keystore, the profile APK is still signed with the debug key and the
release APK with the throwaway key.

## Worktrees and other folders (survey, 2026-10-29)

| Folder | Branch | Uncommitted work | Local-only files to keep in mind |
|---|---|---|---|
| BudgetWise | feat/claude-ai-android-voice-plan | 96 file-mode-only changes from a folder copy (no content changes); untracked `firebaserc`, `gitignore` (dotless copies), `pnpm-lock.yaml` | `.env.local`, `.genkit/` |
| BudgetWise-api | feat/m1-api | none | `.env.m1-test` (local test account), `.superpowers/sdd/` ledger |
| BudgetWise-app | feat/m1-android | none; a dev server runs from it on port 9002 | `.superpowers/sdd/` ledgers and `m1-rulings-and-device-checklist.md` |
| BudgetWise-firestore | feat/firestore | none | `firestore-debug.log` |
| BudgetWise-android-accounts | feat/android-accounts | none | `android/local.properties` |
| BudgetWise-ws3 | feat/ws3-raj-asr | none | `.superpowers/sdd/` ledger, initialized llama.cpp submodule, native build caches |
| BudgetWise-ws3-spike | ws3-spike | only `__pycache__/` | none |
| BudgetWise-mono | chore/monorepo | none | `apps/android/local.properties`, `apps/web/node_modules` |

`/Users/code/Projects/NeuralBudget` is not part of BudgetWise: it is a separate Django project with its own
history and remote (`Anmolkumaarsiing/NeuralBudget`), and it has its own uncommitted changes. It was left alone.

## Path fixes after the move

The move commit (`f4617ae`) is renames only, plus the submodule path that `git mv` updated in `.gitmodules` (the
submodule name stays `android/asr/third_party/llama.cpp` so existing checkouts keep their module folder). The
following commits fix what the move broke:

- web: the stale `android` exclude in `apps/web/tsconfig.json`; a root `.gitignore`. Next, Vitest, Tailwind,
  PostCSS, `components.json`, `vercel.json` and the scripts use paths relative to their own folder and needed
  no change.
- brand: `render.py` writes to `apps/web/public` and `apps/android/{app,asr-demo}`.
- android: submodule hint and repo-root commands in the ASR docs and scripts. Gradle and CMake paths are relative
  and did not change.
- android build: `settings.gradle.kts` leaves out `:asr` and `:asr-demo` (with a printed hint) when the llama.cpp
  submodule is not checked out, so CI and fresh clones can build. The release build signs with a keystore from
  the environment, and versionCode/versionName can be passed as Gradle properties.

## CI

| Workflow | Runs on | Does |
|---|---|---|
| web-ci.yml | PRs and pushes to master touching `apps/web/**` | Node LTS, `npm ci`, lint once an ESLint config exists, `vitest run`, `next build` |
| web-deploy.yml | push to master (production), PRs (preview), manual | Vercel CLI `pull`, `build`, `deploy --prebuilt` from the repo root |
| android-ci.yml | PRs and pushes to master touching `apps/android/**` | JDK 17, Gradle cache, unit tests for :app, :core:data, :nlu, :feature:voice, `:app:assembleDebug`, lint |
| android-release.yml | manual, or a tag `android-v*` | signed release AAB, uploaded to the Play internal track |

Notes:

- Pushes to other branches do not run CI; open a PR for that.
- web-ci sets up Java 21, not 17: `npm test` starts the Firestore emulator from the firebase-tools 15
  devDependency, and that version refuses anything older (`MIN_SUPPORTED_JAVA_MAJOR_VERSION = 21`).
- The web build needs no secrets or database URL; `next build` never opens Firestore.
- `next lint` has no ESLint config or dependency yet, so the lint step stays off until one is added.
- android-ci builds no native code and runs no instrumented tests. The ASR modules need the NDK, CMake and the
  llama.cpp submodule; checkout leaves the submodule out, so Gradle skips them.
- Each workflow skips with a notice when its secrets are missing (also true for PRs from forks).
- android-release keeps the signed AAB and `mapping.txt` as a workflow artifact. It uploads only when the Play
  secret and the API URL variable are both set, and as a `draft` by default.
- Actions are pinned to release tags; all four files pass actionlint 1.7.12 with shellcheck 0.11.0.

## Secrets and settings the owner must add

GitHub repository secrets (names only):

- `VERCEL_TOKEN`, `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID`
- `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
- `PLAY_SERVICE_ACCOUNT_JSON`

GitHub repository variable:

- `BUDGETWISE_RELEASE_API_BASE_URL`: the live `https://.../api/v1/` URL the release app calls.

One-time settings:

- Vercel: set the project's Root Directory to `apps/web` before this branch reaches master; otherwise
  Vercel's Git build runs at the repo root and fails.
- Vercel: keep one deployer. Either turn off Git deployments (disconnect the repo under Settings, Git, or add
  `"git": {"deploymentEnabled": false}` to `apps/web/vercel.json`) and let web-deploy.yml deploy, or keep Vercel's
  Git deploys and delete web-deploy.yml. Both on means two deploys per push.
- Vercel: the app's environment variables stay in the Vercel project; `vercel pull` fetches them.
- Play Console: create the app `app.budgetwise`, turn on Play App Signing (the keystore in the secrets is the
  upload key), and upload the first AAB by hand (take it from the workflow artifact). Give the service account
  release rights for the app. Switch the upload status to `completed` once the first release has been reviewed.
- Firebase App Hosting, if it is still used: set the backend's root directory to `apps/web`.

## Open questions

- Should the voice spike (`apps/android/asr/host/spike`, golden transcripts, Python harness) stay in the repo?
  Kept for now.
- Is Firebase App Hosting (`apps/web/apphosting.yaml`) still used next to Vercel? If not, it can go.
- The Genkit leftovers (`src/ai/genkit.ts`, `src/ai/dev.ts`, the genkit packages and README text) are unused
  since the switch to Claude. Remove them?
- Keep `GEMINI.md` and `Issues.md` in `apps/web/`, or move them to `docs/`?
- CI uses `lts/*` for Node; pin a major version instead if builds should not move when a new LTS lands.

## Finish later

### a. Merge feat/firestore and feat/android-accounts

Done on 2026-10-29 (see "What each branch held"). For any later work on those branches, or any other branch
cut from the old layout, merge the same way:

```sh
cd /Users/code/Projects/BudgetWise-mono
git -c merge.directoryRenames=true merge --no-ff <branch>
git status --short | awk '$1=="UD"{print $2}' | xargs git rm   # only if git reports rename/delete
git ls-files | grep -E '^(src|tests|scripts|public|android)/'   # must print nothing; git mv anything it lists
git commit --no-edit
```

Also list new files outside `apps/`, `docs/`, `brand/` and
`.github/` and move the ones that belong to an app:

```sh
git diff --name-only --diff-filter=A HEAD^1 HEAD | grep -vE '^(apps|docs|brand|\.github)/'
```

### b. Make /Users/code/Projects/BudgetWise the one folder

After chore/monorepo is merged to master (needs the owner's go-ahead to push):

1. In `BudgetWise`, confirm the 96 changes are mode-only (`git -c core.fileMode=false diff --stat` prints
   nothing), then restore them with `git restore .`.
2. Compare the untracked `firebaserc` and `gitignore` with the tracked dotfiles and delete them; delete
   `pnpm-lock.yaml` (the app uses npm).
3. `git switch master && git pull`.
4. Move `.env.local` to `apps/web/.env.local` (it now needs the Firestore variables from the README, not
   `MONGODB_URI`) and copy `android/local.properties` from another worktree to
   `apps/android/local.properties`.
5. Delete the old root build folders (`node_modules`, `.next`, `.genkit`, `tsconfig.tsbuildinfo`) and run
   `npm ci` in `apps/web`.
6. Only if voice work resumes: `git submodule update --init apps/android/asr/third_party/llama.cpp`.

### c. Worktrees that can then go

Use `git worktree remove <path>` (never `--force`); it refuses when there is uncommitted work. It deletes
ignored files, so copy any `.superpowers/` ledger you want first.

- BudgetWise-api: merged and pushed; copy `.env.m1-test` and its ledger if wanted, then remove.
- BudgetWise-app: merged and pushed; stop the port 9002 dev server, copy the ledgers and the device checklist, then remove.
- BudgetWise-ws3: merged and pushed; copy its ledger if wanted, then remove.
- BudgetWise-ws3-spike: merged and pushed, nothing local; remove.
- BudgetWise-firestore: merged at 165b047, never pushed on its own; remove once chore/monorepo is pushed.
- BudgetWise-android-accounts: merged at dc6c14c, never pushed on its own; remove once chore/monorepo is pushed.
- BudgetWise-mono: remove after chore/monorepo is on master and `BudgetWise` has switched to it.
