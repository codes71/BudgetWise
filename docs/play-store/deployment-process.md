# Pocket Peek internal release workflow

Implemented and locally verified on 7 November 2026. The workflow is supplied for PR review. Committing, pushing and opening the PR do not dispatch it. No workflow dispatch, live Play API call, upload or release was performed while implementing or preparing this change for review.

## Approval and trigger

Either the owner or the agent can approve an internal release after reviewing the candidate and passing checks. Approval is the deliberate dispatch of **Pocket Peek Android release**, with the exact source SHA, version and release notes, approved_by set to owner or agent, and approve_internal set to true. No second human confirmation is required. The choice records approval origin; GitHub's normal repository write permissions control who may dispatch, and receipts record the actual actor and rerun actor.

Ordinary pushes, merges and tags do not trigger this workflow. The selected ref must resolve at dispatch to the approved full 40-character SHA. Both jobs checkout that captured SHA and reject a mismatch. A branch moving later cannot change this run's source.

The owner has authorized agent approval for this release process. For an agent-approved release, first inspect the exact candidate and its available checks, then deliberately dispatch it. The workflow reruns its required tests and will not publish on failure. This standing process does not override a task-specific instruction such as "do not publish yet".

## What happens after approval

1. A job with no signing/Play credentials validates approval, runs release-tool regression tests, then Android unit tests, a debug build and debug/friends/release lint. Failed, cancelled or skipped verification prevents the publishing job.
2. The publishing job rechecks the same SHA and fails on missing signing, Play or canonical API configuration. It queries existing Play bundle/APK/track versions and refuses a reused or lower code, or an unfinished internal release.
3. It builds the standard signed release AAB once. Checksum-pinned official bundletool, jarsigner and keytool verify package/version, expected upload signer, SDK contract, non-debuggable manifest, absence of the friends installer/provider, canonical API compiled in DEX, disabled language splitting and native 16 KB alignment.
4. The exact AAB, R8 mapping, approval and verification receipt are archived before upload. Version inventory is checked again immediately before the publisher runs.
5. The existing upload action publishes only app.pocketpeek to internal with status completed and the supplied English release notes. There is no track/status input and no production-promotion path.
6. A separate official Google client reads back the completed internal version and matches its uploaded SHA-256 to the verified local AAB. It polls at most six times. This verifies API state, not installation or tester availability. Verify real Play delivery separately.

No fallback silently changes a failed completed publication to a draft or another track. Google may block API publication until Console setup/review prerequisites are satisfied. Such a failure requires inspecting the actual Console response, not broadening permissions or publishing elsewhere.

## Required configuration

Existing repository secrets, values kept outside source/logs:

- ANDROID_KEYSTORE_BASE64
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD
- PLAY_SERVICE_ACCOUNT_JSON

Repository variables:

- BUDGETWISE_RELEASE_API_BASE_URL must exactly equal https://budgetwis3.vercel.app/api/v1/.
- BUDGETWISE_PLAY_UPLOAD_CERT_SHA256 is the public SHA-256 of the approved AAB upload certificate. The current signed 0.3.0 (4) receipt records f2abec2d9d846d75cee69bafd05f8afe2ef5050247d2c6fca53ba8db5c7368bb. Verify it against Play's upload-certificate configuration before setting it. The Play app-signing certificate used on devices is a separate identity.

The Play service account should be restricted to this app and testing releases, without production publishing permission. Its existing API setup and app access must be verified before the first real run. This implementation did not inspect or change live secrets, permissions or variables.

The API helper uses Google's official google-auth client, fixed app/scope/endpoints, and temporary inspection edits that are discarded without committing. It cannot upload a bundle, update a track or commit an edit. It creates a sanitized success/failure receipt and never logs credential contents or provider response bodies.

## Operating a release

Choose an unused increasing version code after inspecting Play. Never infer it from a workflow run number. The current code 4 is already published internally, so it is not a new-upload candidate. A new version must pass the same workflow checks.

Select the release ref and its exact commit in GitHub Actions. Supply the six required inputs. Dispatch only when the owner or agent has approved that release. No tag push is needed. GitHub requires the workflow file to exist on the default branch for workflow_dispatch availability. Publishing the source branch or PR does not invoke the release trigger.

Read the job steps and retained receipts. A green check means verification plus the committed internal version/hash passed; it still does not establish a tested device installation. If the publisher fails, inspect Console before retrying because the upload might already have committed. A rerun after a successful or partial earlier upload refuses the reused code. Do not automatically allocate another version or blindly replay publication.

Concurrency prevents overlapping runs of this workflow. GitHub retains one pending run by default and can replace it when a newer one arrives. It is not a FIFO release queue. Avoid parallel Console edits or other publishers during a release.

The candidate and outcome artifacts include run ID and attempt and remain for 90 days. Preserve important release evidence in owner-approved long-term storage before expiry. The temporary keystore is removed even on failure.

After internal publication, test a Play-delivered installation and update. Promote the same tested bundle through closed testing and production only with separate approval. Initial public-launch testing/account requirements and the legacy-package data boundary remain in [the release guide](README.md). Backend deployment and database rollback are separate operations.

## Local verification without publication

Create an isolated Python environment outside the source tree, then install test dependencies and run:

```sh
python3 -m pip install -r tools/android-release/requirements-test.txt
python3 -m unittest discover -s tools/android-release -p 'test_*.py' -v
```

The tests use synthetic inputs, local ZIP/ELF fixtures and fake Play transports. They do not need signing credentials, a service account, a device, a server or a live Play API. The workflow also installs pinned Google authentication dependencies when running its inspection steps. Validate both edited workflows with actionlint. Android checks use Java 21 and the SDK setup in CLAUDE.md.

Do not dispatch the release workflow as a test: dispatch with approval publishes after its checks succeed.

## Verified implementation results (7 November 2026)

- 53 local release-tool tests passed, including synthetic approval, configuration, signature, artifact, workflow-wiring, wrapped Base64 keystore compatibility and mocked Play inventory/readback failures.
- The workflow's local Android command passed: 975 tests across 101 suites, zero failures/errors/skips, debug build, and selected debug/friends/release lint. Gradle reported BUILD SUCCESSFUL. Lint printed nonfatal fix-generation diagnostics; no lint gate failed.
- actionlint 1.7.12 and bash syntax checks passed for all three affected workflows. Shellcheck was unavailable, so it was not run.
- The new verifier passed on the retained signed 0.3.0 (4) AAB without rebuilding it, matching its recorded hash, upload certificate, canonical API and all seven native libraries. This is packaging evidence, not a new Play-delivery test.
- An independent source review found no blocking issue. No live GitHub execution, Play API request, upload or publication was used for testing. Live configuration and Google processing remain checks for a later authorized release.
