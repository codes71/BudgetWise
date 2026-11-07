# Pocket Peek Android friends updates

Pocket Peek uses `app.pocketpeek`. It is a separate Android install from legacy BudgetWise (`app.budgetwise`), not an in-place rebrand of that package. Leave the existing BudgetWise install and its private data intact. Pocket Peek can sync the same cloud account after sign-in, but changing the application ID does not transfer local preferences, downloaded models, or unsynced records between app sandboxes. Never uninstall or clear the old app as a migration shortcut.

The `friends` APK checks a small public release descriptor when the app opens. Settings also has a manual update check. Downloads start only when the user asks; installation uses Android's confirmation flow. Updating replaces the installed app package in place. It does not uninstall the app or delete its private data.

The standard `release` build remains the Play distribution build and has direct updates disabled. Publish native updates for that build through Google Play. The friends install permission is declared only in the friends and debug manifests, never the Play release manifest.

## Cloud files and trust

- Pocket Peek's descriptor is `apps/web/public/updates/pocket-peek-android-friends.json`, served after deployment at `https://budgetwis3.vercel.app/updates/pocket-peek-android-friends.json`. It requires no new server, database, or credential. Do not overwrite the legacy BudgetWise `friends.json` feed or its existing release assets.
- The initial descriptor has `schemaVersion: 1`, `channel: "friends"`, `applicationId: "app.pocketpeek"`, and `release: null`. Deploying the code before publishing a binary therefore offers no update. A source file is not proof that the new endpoint is deployed.
- A published descriptor is flat and omits `release`. It contains the same schema/channel/application ID plus `versionCode`, `versionName`, `minSdk`, `apkUrl`, `sha256`, `sizeBytes`, and `notes`.
- Binaries use a version-specific GitHub release asset, for example `https://github.com/codes71/BudgetWise/releases/download/pocket-peek-android-friends-v0.3.0-4/PocketPeek-0.3.0-4.apk`. The repository keeps its existing name. Pocket Peek tags and filenames are distinct from legacy releases. Do not use the mutable `/releases/latest/download/` alias.
- The updater checks download bounds, HTTPS origin, hash, size, package identity, installed signing certificate, newer version, and device SDK compatibility before installation. A cloud descriptor cannot authorize a different signing key.
- The release tools allow at most 150,000,000 APK bytes and 32,768 descriptor bytes. Notes are limited to 4000 characters. No account token, app signing password, private key, or service credential belongs in the public descriptor or APK download directory.

Versioned asset names alone do not make GitHub releases immutable. Enable repository release immutability before the first published friends release. GitHub then locks the tag and assets when a draft is published. See [GitHub's immutable release guide](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases).

## Build and verify a release

For later updates to Pocket Peek, keep the same app signing key used for its first friends APK. Back it up in encrypted storage. Losing or replacing this key prevents ordinary in-place updates of that package. Keeping BudgetWise's key alone does not make a new application ID an update to BudgetWise. A debug-signed install cannot accept the friends release certificate without a separate data migration; never uninstall a populated test installation as a shortcut.

Use an isolated checkout of the reviewed source. Configure its Android SDK path as described in `CLAUDE.md`. Supply the existing signing environment variables securely: `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Do not commit or print their values. Then build, using a version code higher than every published friends build:

```sh
cd apps/android
./gradlew :app:assembleFriends \
  -Pbudgetwise.versionCode=4 \
  -Pbudgetwise.versionName=0.3.0 \
  -Pbudgetwise.releaseApiBaseUrl=https://budgetwis3.vercel.app/api/v1/
```

The APK is at `apps/android/app/build/outputs/apk/friends/app-friends.apk`. Retain `apps/android/app/build/outputs/mapping/friends/mapping.txt` privately for crash retracing. The optional manual **Pocket Peek Android friends release** GitHub workflow produces the verified artifact but cannot publish a release or change master. It needs the existing signing secrets plus the public repository variables `BUDGETWISE_RELEASE_API_BASE_URL` and `BUDGETWISE_FRIENDS_SIGNING_CERT_SHA256`. Property and secret names retain their existing spelling to preserve configuration.

Run the focused release-tool tests from the repository root:

```sh
python3 -m pip install -r tools/android-release/requirements-test.txt
python3 -m unittest discover -s tools/android-release -p 'test_*.py' -v
```

Write user-facing release notes to a local file. Prepare a new artifact directory, passing the public SHA-256 fingerprint of the expected release certificate:

```sh
python3 tools/android-release/prepare_release.py \
  --apk apps/android/app/build/outputs/apk/friends/app-friends.apk \
  --build-tools /absolute/path/to/Android/sdk/build-tools/37.0.0 \
  --expected-certificate-sha256 '<release-certificate-sha256>' \
  --previous-manifest apps/web/public/updates/pocket-peek-android-friends.json \
  --notes-file /absolute/path/to/release-notes.md \
  --output /absolute/path/to/new-friends-release-directory
```

The preparation command never uploads or publishes. It independently checks the APK signature and ZIP alignment, requires `app.pocketpeek`, reads version/SDK identity from the APK, rejects debuggable or Android Debug-signed binaries, checks that the friends updater permission is present, requires increasing versions, and creates the canonical APK filename, `pocket-peek-android-friends.json`, checksum, and verification evidence. A legacy BudgetWise binary or descriptor is rejected. It refuses to overwrite an existing artifact directory. The expected certificate is a public fingerprint, not a password.

## Publish and promote

Publishing the APK and deploying the descriptor are separate actions. Publish the binary first, verify its public download, then promote the descriptor. Do not deploy metadata pointing at an unpublished draft asset.

1. Verify that the artifact's `verification.json`, source commit, certificate, version, and tests match the reviewed release. Check the repository's GitHub account with `gh auth status`; this public repository belongs to `codes71`. Follow the per-command account rule in `CLAUDE.md` without switching the globally active account.
2. Create a draft at the exact source commit. Substitute the values from `verification.json` and use the prepared filenames:

   ```sh
   GH_TOKEN="$(gh auth token --user codes71)" gh release create pocket-peek-android-friends-v0.3.0-4 \
     /absolute/path/to/release/PocketPeek-0.3.0-4.apk \
     /absolute/path/to/release/SHA256SUMS.txt \
     --repo codes71/BudgetWise --target '<reviewed-full-source-sha>' \
     --title 'Pocket Peek 0.3.0 (friends)' \
     --notes-file /absolute/path/to/release/release-notes.md \
     --draft --latest=false
   ```

3. Review the draft, then publish it explicitly. Never replace an existing tag or upload with `--clobber`:

   ```sh
   GH_TOKEN="$(gh auth token --user codes71)" gh release edit pocket-peek-android-friends-v0.3.0-4 \
     --repo codes71/BudgetWise --draft=false --latest=false
   GH_TOKEN="$(gh auth token --user codes71)" gh release view pocket-peek-android-friends-v0.3.0-4 \
     --repo codes71/BudgetWise --json tagName,isDraft,isImmutable,assets,targetCommitish
   ```

4. Download the published asset to a new local directory. Compare SHA-256 and certificate with the prepared APK. When release immutability is enabled, `gh release verify-asset` can additionally check the published release attestation. See [GitHub's verification guide](https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/secure-your-dependencies/verify-release-integrity).
5. Copy the prepared `pocket-peek-android-friends.json` into `apps/web/public/updates/pocket-peek-android-friends.json` and review that single promotion. Merge/deploy only with the owner's authorization. Never promote it to `apps/web/public/updates/friends.json`, which belongs to legacy BudgetWise. The existing web deployment publishes the static descriptor. Verify the live descriptor separately from GitHub Actions and separately from the GitHub release.

For an urgent pause, promote the no-release sentinel again. That stops new update offers but cannot revoke an APK already downloaded or installed. Fix a bad release by publishing corrected code with a higher version code. Android does not generally allow a normal downgrade, so rollback is a new forward release. Database changes in any new release still require explicit non-destructive Room migrations and backward-compatible server contracts.

## Acceptance and data retention

Use a dedicated emulator or test install signed with the same certificate as its successor. Do not use the shared physical phone without approval, and do not run `connectedDebugAndroidTest` because the project's shared-phone rule forbids it.

Before the update, record locally seeded expenses, budgets, queued offline work, settings, account identity, Room schema, and downloaded model files. Then verify:

- A fresh app launch checks once without blocking the app; manual checks always retry.
- The no-release descriptor, current version, offline state, and server errors leave the app usable.
- A newer descriptor shows version, notes, download progress, cancel, retry, and install actions. No installation or settings screen opens without an explicit user action.
- The unknown-source permission handoff returns to the app without looping. Cancelling the Android installer leaves the app and its data intact.
- Corrupt bytes, wrong size/hash, wrong package, wrong certificate, older version, too-new SDK, untrusted URLs, and oversized responses are rejected before installation.
- A valid same-certificate in-place update completes, launches the new version, and retains all recorded local data and queued work. Repeat with offline queued changes.
- The Play release manifest has no direct-install permission, and its update controller is disabled.

Keep the final report explicit about what ran on an emulator, what ran on the physical phone, and what remains unverified. The release backend is static and requires no Firestore access or migration.
