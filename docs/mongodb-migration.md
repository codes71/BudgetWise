# Firestore to MongoDB Atlas migration

Pocket Peek uses its existing server API and custom bcrypt/JWT authentication. This migration changes the server database, preserving logical account and record IDs, existing password hashes, and API response shapes. Google sign-in remains a separate feature. The Android Room database and the existing BudgetWise installation are not modified by these tools.

The tools are implemented in `apps/web/scripts/firestoreSnapshot.ts` and `apps/web/scripts/migrateFirestoreToMongo.ts`. They do not delete, update, or seed Firestore. The completed production migration is recorded below. Import commands shown here are historical receipts of the migration process, not runnable recovery instructions. The current CLI rejects `--mode import`, the live-source import entry point, and reverse-copy `--write` before store access. Read-only export, verification and local authenticated decryption remain available. Fixture-only write helpers require local `test-pocketpeek-*` Mongo databases and demo Firestore sources or an actual loopback emulator target.

## Deletion journal and recovery safety

The account-deletion request now records `_accountDeletionJournal` in the same majority-committed Mongo transaction that locks the account. The journal key is SHA-256 of a versioned domain plus the immutable account ID. It stores request time, active-store status/completion time, and `recoveryCleanup: pending`. It contains no email, password hash, profile, transcript or financial values. Hashes are pseudonymous identifiers, not anonymous data. New accounts can reuse an email because suppression refers to the old immutable account ID.

The journal is excluded from account-child purges, survives root deletion and lost-response retries, and is created before orphan cleanup when an authenticated old identity has no root. A failure to record the request rolls back the lock and prevents a purge. Only runtime and imported companion removal changes `activeStore` to `complete`; it never changes `recoveryCleanup` to complete. No TTL, expiry deadline, background purge, Firestore mutation or encrypted-backup deletion is implemented by this change.

The journal lives in the existing runtime database, separate from account-owned records. It must remain authoritative outside anything being restored. Never replace it with an older database snapshot, include an old copy as restore authority, or delete it during rollback. Whole-database recovery must preserve the latest journal separately and merge its complete deletion history before restoring account records. A journal missing from an old snapshot does not mean no account has been deleted. These rules also apply to new Mongo snapshots, provider recovery copies and rollback to retained Firestore.

Normal API/session access, account-owned write transactions and direct public-profile lookup also consult this journal. A restored old root without `deletionStartedAt` cannot reactivate a requested-deletion identity. Password login rejects such a restored root, including the mobile retry-login route. An actually locked root still permits password-confirmed deletion retry, and a valid old signed identity can finish deletion of restored remnants. The account-root write fence remains required alongside the journal read so a concurrent deletion conflicts with an in-flight write.

Historical migration tools cannot safely implement that contract, so their production writes now fail closed. There is no production bypass flag. `importSnapshot` permits only demo-project fixtures into test databases whose constructed Mongo client has exclusively loopback endpoints; reverse-copy fixture writes also verify the constructed Firestore client uses a demo project and loopback emulator. Unknown transport shapes fail closed. A future production restore needs separately reviewed code using the latest authoritative journal, suppression for roots, email claims, every owned/archived descendant, and a write freeze or equivalent transaction fencing so deletion cannot race with restoration. Count/hash parity against an old snapshot alone is insufficient.

This is a code safeguard and durable cleanup intent, not proof that recovery copies were removed. Retained Firestore data, encrypted local snapshots, backup-only deployments and provider recovery copies still need an owner-approved inventory, physical cleanup procedure, retention basis/window and verification. No such live action or retention decision is authorized by this source change. The API's successful active-account deletion response does not attest that those operations have completed. Resolve the recovery operations and policy before production promotion or claiming the complete deletion lifecycle is ready.

### Local recovery suppression dry-run

`apps/web/scripts/planRecoverySuppression.ts` reads only two private local files and prints counts/fingerprints. It has no network adapter, write mode, importable output records or production bypass. It does not establish that the supplied journal is current or complete, authorize a restore, or remove any copy. Do not obtain a live journal export without separate production-read authorization.

The journal input must be a separately preserved, complete export of the latest `_accountDeletionJournal`, rather than a collection from the historical snapshot. Its strict envelope is `{ format: "pocket-peek-deletion-journal", version: 1, sourceId, exportedAt, requests }`. `sourceId` is an independently reviewed non-secret identifier for the authoritative store. Export dates are ISO strings; requests are sorted by unique `_id` and contain only the runtime journal fields (`_id`, `formatVersion`, `requestedAt`, `activeStore`, optional `activeStoreCompletedAt`, `recoveryCleanup`). No email, raw account ID or account content belongs in it. Record its fingerprint using the exported `journalFingerprint` function in a separate trusted export receipt. The export producer, complete inventory, currentness and write freeze must be reviewed before any real restore tooling is authorized; a matching self-supplied hash is not that evidence.

After independently reviewing both source identities and fingerprints, run from `apps/web`:

```sh
npx tsx scripts/planRecoverySuppression.ts \
  --snapshot '/private/owner-only-directory/historical-snapshot.json' \
  --journal '/private/owner-only-directory/current-deletion-journal.json' \
  --expect-source-project '<reviewed-historical-project>' \
  --expect-snapshot-sha256 '<trusted-snapshot-fingerprint>' \
  --expect-journal-source '<reviewed-current-store-id>' \
  --expect-journal-sha256 '<trusted-journal-fingerprint>'
```

Both inputs must be owner-only regular files; symlinks are refused. Suppression includes every journal request, even after both cleanup statuses are complete. Roots, descendants, email claims and flat/archive rows with matching ownership are counted as suppressed. Conflicting or invalid ownership aborts. Old journal rows, migration markers and rate counters are withheld; unknown unowned records are held for mapping review. A re-registered email owner's new immutable ID is distinct. The result deliberately says `restoreAuthorized: false` and `journalCompletenessVerified: false`. Never turn the `otherwiseEligibleDocuments` count into approval to import those records.

See [the operational recovery-copy plan](account-deletion-operations.md) for target inventory, independent gates, retention decisions and verification receipts. No retention period is selected by this code.

## Completed migration and live cutover (7 November 2026)

Coordinator-verified evidence:

- The overnight read-only preflight had 241 source documents. The final frozen export had 242; only `rateLimits` changed from 17 to 18. Account and financial collection hashes were unchanged.
- The final encrypted frozen export authenticated locally. Create-only import achieved exact 242-record target parity. Source remained unchanged, and independent local data-parity and index readbacks verified all 11 runtime indexes with no TTL.
- Normal Mongo-backed deployment `dpl_4GZiWjRfKQSb539T3VUs7hLru19t`, from reviewed runtime commit `4a9f51e`, now serves canonical `https://budgetwis3.vercel.app` in personal Vercel project `codes-t/budget-wise`. The unrelated company-default account `thura-s` was untouched and must not be used or changed for this project.
- Existing reviewer JWT reads matched the prior account data. Fresh password login returned 200 with the same reviewer identity. A temporary reviewer-only 1-kyat transaction saved/replayed with 200, produced one record, then cleanup returned 204; the reviewer account is empty again. No existing user's financial data was modified by this verification.
- Firestore is retained, old-host mutation WAF protection remains active, and normal Mongo writes are live. The pre-write rollback window has closed. **Do not blindly switch to stale Firestore.** Freeze and reconcile accepted Mongo changes with a separately reviewed process before any rollback.

Release boundary: the coordinating session also verified Play internal release `0.3.0 (4) Pocket Peek internal` as Active and Available to internal testers, released 7 November 2026 at 12:58 PM Myanmar time. The retained, checked `Mytesters` list has four users, and the actual [opt-in invitation](https://play.google.com/apps/internaltest/4699527614541883626) was verified live. Testers currently see `app.pocketpeek` (unreviewed) until setup and first review finish. This does not prove a phone installation, closed-track release, public production release or public policy eligibility. Internal testing does not replace the separate new personal-account closed test of at least 12 continuously opted-in testers for 14 days. Reviewer owner-entry and public declarations remain separate; see [Console submission status](play-store/console-submission.md).

Earlier 241-document dry-run receipts remain historical evidence. Do not compare the now-writable destination to the old frozen snapshot as if no live changes could have occurred.

## Safety contract

- Read both scripts end-to-end and verify the exact source project, Atlas cluster, destination database, production deployment, and a rollback plan that accounts for accepted Mongo writes before running them.
- Use the credentials already approved for those systems through a private environment loader. The script does not load `.env.local`, use an interactive Google login, or accept credentials as command arguments. Do not print environment values, connection strings, snapshots, user documents, or passwords.
- Use a new, dedicated empty Atlas database. `MONGODB_DB` must exactly equal the `--mongo-db` argument. System databases are refused. Use the established Atlas URI through `MONGODB_URI`, never an argument.
- Quiesce **all old Firestore writers** before the final export and keep them quiesced through import, validation, and promotion. This includes the active production API, direct clients, background jobs, old server instances, and preview deployments. A twice-read fingerprint is a drift detector, not a substitute for a write freeze or a globally consistent Firestore snapshot.
- Keep Firestore intact. A failed or partial Mongo import is not a reason to delete source records. A different source fingerprint needs a fresh snapshot and a new destination database; the tools will not reconcile changing source data into a partly populated target.
- Keep raw snapshots in a private directory outside the repository, cloud shares, and ordinary deliverable uploads. They contain account password hashes and financial records. Each snapshot is created with `0600`, `O_EXCL`, `O_NOFOLLOW`, and fsync. Existing snapshots are never overwritten. Preserve it in the owner's approved encrypted backup storage.

## Data representation

| Firestore path | Mongo collection | Mongo `_id` | Ownership |
|---|---|---|---|
| `users/{uid}` | `users` | original `uid` | account root |
| `userEmails/{hash}` | `userEmails` | original hash | original `userId` field |
| `categories/{id}` | `categories` | original category ID | shared |
| `rateLimits/{hash}` | `rateLimits` | original hash | original fields |
| `users/{uid}/transactions/{id}` | `transactions` | `uid/id` | `userId: uid` |
| `users/{uid}/budgets/{id}` | `budgets` | `uid/id` | `userId: uid` |
| `users/{uid}/budgetMonths/{month}` | `budgetMonths` | `uid/month` | `userId: uid` |
| `users/{uid}/categories/{id}` | `userCategories` | `uid/id` | `userId: uid` |
| other existing documents, including deeper descendants and legacy root collections | `_firestoreArchive` | full original Firestore path | `ownerId` from `users/{uid}` or an existing string `userId` field when present |

The composite Mongo key prevents two users' same month/category IDs from colliding. The API continues returning the original leaf ID. Known documents keep original data fields and add only required Mongo identity/ownership metadata. An original `_id` field or contradictory `userId` is refused instead of silently overwritten.

The raw snapshot stores tagged values, including exact Timestamp seconds and nanoseconds, byte arrays, GeoPoint, references, special doubles, arrays, and maps. Document creation/update versions are retained. Recursive traversal uses `listDocuments` and follows children even if a parent document does not exist. All traversal network requests share a maximum of eight concurrent requests, and permits are released before recursive child walks. Sorted paths and tagged maps keep fingerprints independent of completion order. No orphan data is silently skipped.

Native Mongo runtime dates use BSON Date milliseconds, converted with the source SDK's `Timestamp.toDate()` semantics, including pre-epoch sub-millisecond boundaries. Any sub-millisecond Timestamp, GeoPoint, or reference in a known document also receives exact companion metadata in `_firestoreTypes`, keyed by `collection/id`. Account-owned companion/archive rows carry `ownerId` for account deletion. Unknown documents retain their entire tagged payload and original versions in the archive.

Unsupported source types or integers that the JavaScript reader cannot represent exactly fail the export. Extend the encoding and its round-trip tests before retrying; do not coerce or drop such values. Password hashes, IDs, financial amounts, arbitrary source fields, and server timestamps are not replaced with new values.

## Commands

Run from `apps/web`, with source and destination credentials supplied privately. These examples contain public target identifiers only. Replace all placeholders after checking the actual environments. The destination name below is an example and must be verified as empty on the intended Atlas cluster.

1. Freeze old writes. Capture a new private snapshot. Two full scans must have identical fingerprints before the snapshot is written.

```sh
npx tsx scripts/migrateFirestoreToMongo.ts \
  --mode export \
  --source-project '<verified-project-id>' \
  --snapshot '/private/owner-only-directory/firestore-before-cutover.json'
```

Record the printed `source.sha256` in the private migration receipt. Output contains counts and hashes, not records. Per-user report keys are hashes rather than user IDs. The fingerprint covers source project, paths, values, and document versions, excluding export wall-clock time.

2. Inspect the mapping and destination without writing. Set `MONGODB_DB` to the same approved database name in the private environment.

```sh
npx tsx scripts/migrateFirestoreToMongo.ts \
  --mode dry-run \
  --source-project '<verified-project-id>' \
  --snapshot '/private/owner-only-directory/firestore-before-cutover.json' \
  --expect-sha256 '<reviewed-source-sha256>' \
  --mongo-db 'pocketpeek_migration_20261107'
```

Review both source and destination counts and per-collection/per-user hashes. Different representations mean source and mapped-target hashes are not expected to equal each other. Mapped-target hash stability plus exact per-document BSON equality is the destination parity test.

3. Import create-only. This writes the dedicated destination database, including its `_migrationControl` marker. It does not write Firestore. Source fingerprints are checked again before and after import.

```sh
npx tsx scripts/migrateFirestoreToMongo.ts \
  --mode import \
  --source-project '<verified-project-id>' \
  --snapshot '/private/owner-only-directory/firestore-before-cutover.json' \
  --expect-sha256 '<reviewed-source-sha256>' \
  --mongo-db 'pocketpeek_migration_20261107'
```

Each app record uses `insertOne`. Existing records may be accepted only when BSON-identical and the destination has this exact source project's snapshot marker. Additional records, changed records, or another migration's marker abort. A network failure may leave a partial **destination**; a rerun with the exact snapshot resumes missing inserts without overwriting anything. Never point live traffic at an incomplete target.

4. Independently verify the whole target and recheck the frozen source immediately before cutover.

```sh
npx tsx scripts/migrateFirestoreToMongo.ts \
  --mode verify \
  --source-project '<verified-project-id>' \
  --snapshot '/private/owner-only-directory/firestore-before-cutover.json' \
  --expect-sha256 '<reviewed-source-sha256>' \
  --mongo-db 'pocketpeek_migration_20261107'

npx tsx scripts/migrateFirestoreToMongo.ts \
  --mode verify-source \
  --source-project '<verified-project-id>' \
  --snapshot '/private/owner-only-directory/firestore-before-cutover.json' \
  --expect-sha256 '<reviewed-source-sha256>'
```

The strict target verifier rejects missing **and extra** records. It is intended for the frozen, pre-cutover destination. After the app starts accepting writes, that target will legitimately differ from the original snapshot; do not rerun import or treat the old snapshot as its current contents.

## Validation and cutover

Before promotion:

- Create the native Mongo indexes and verify uniqueness without discarding conflicts. Preserve partial indexing for legacy rows that legitimately omit optional keys. Atlas must support the runtime's multi-document transactions.
- Run the integration suite against an isolated local Mongo replica set. Check duplicate signup/client-ID races, account isolation, month budgets, amount/date preservation, stale sessions, password-confirmed deletion retry, orphan cleanup, and deletion-versus-write races. Mongo snapshot reads alone do not prevent the old deletion race; the runtime must write an account fence inside every account-owned write transaction.
- Stage the exact backend build against the migrated destination without promoting it. Use the dedicated empty reviewer account to verify login and normal API access. Do not reset the owner's password. Check source and mapped parity before any synthetic write changes the target.
- Preserve the JWT signing identity and user IDs, so ordinary active sessions remain meaningful. Run independent checks for web cookie behavior and Android API behavior. Database migration does not move an Android app between package sandboxes.
- Confirm the staged deployment's team, project, database selector, commit/source, and environment. Promote only the verified deployment, then enable writes on that Mongo-backed deployment. Ensure old Firestore-backed previews/instances can no longer accept writes.

## Rollback

That pre-write rollback window is historical and is no longer available for the current live destination. Before accepting new Mongo writes, the safest rollback window was: keep all old writers frozen, restore the previously verified Firestore-backed deployment, and resume writes there after checking the source still matches the snapshot. Do not delete the imported Atlas database or private snapshot as part of rollback.

After accepting new Mongo writes, an immediate switch to stale Firestore would lose newly accepted changes from the user's view. Freeze writes again, export the current Mongo state, reconcile its new/changed/deleted records and IDs back to the rollback store with an independently reviewed tool, verify parity, then switch. This migration does **not** implement a reverse reconciliation tool or authorize a blind rollback after new writes.

## Local pure tests

```sh
npx vitest run --config tests/scripts/migration.vitest.config.ts
```

This dedicated config starts no database, emulator, or server. It tests exact encoding, private immutable snapshots, missing-parent traversal, destination mapping, source drift, conflict rejection, deterministic counts/hashes, and create-only exact resumption with in-memory fixtures. Full runtime Mongo integration testing remains a separate release requirement.

## Encrypted backup when source credentials must stay in Vercel

Use this explicit one-time path only when the active source's credentials are available to an authorized production build but cannot be exported locally. The ordinary app, API, and build commands do not invoke it. The source credentials stay in Vercel. No plaintext export endpoint is added.

1. Generate a dedicated RSA 4096-bit key pair **locally**, in an owner-only directory outside the repository and all build source trees:

```sh
npx tsx scripts/decryptFirestoreSnapshot.ts \
  --mode keygen \
  --private-key '/private/owner-only-directory/snapshot-private.pem' \
  --public-key '/private/owner-only-directory/snapshot-public.pem'
```

Both files are new and `0600`. Key generation never overwrites an old key. The command prints only the recipient public-key fingerprint. Keep the private key local; back it up through the owner's approved encrypted storage. A generated private key is not a deployment credential and must never be uploaded to Vercel, Git, a build cache, a public asset, or a shared deliverable.

2. Prepare a reviewed, separate staging directory from the exact current Firestore-backed source. Add only `firestoreSnapshot.ts`, `snapshotEncryption.ts`, `exportFirestoreEncrypted.ts`, and the **public** key file. Verify the intended personal Vercel team/project and the old runtime source. Configure a one-time, explicit staged production build to invoke:

```sh
mkdir -p public/_migration
npx tsx scripts/exportFirestoreEncrypted.ts \
  --public-key 'snapshot-recipient-public.pem' \
  --output 'public/_migration/<fresh-opaque-backup-filename>.json' \
  --expect-source-project '<verified-active-project-id>'
```

Create the output parent directory in the isolated staging tree before either encrypted export or live-source import. The initial staging failure was a missing output-parent directory, not a source-data or parity problem. Only encrypted envelopes may be written under `public/_migration`; private keys and plaintext snapshots stay outside the source/build tree.

The exporter derives the actual source project from the established runtime `FIREBASE_PROJECT_ID` and uses the same source credential fields or credential-file fallback. It refuses emulator configuration. The optional expected-project guard should be supplied once that identity is known, and a mismatch aborts. It makes two complete captures and requires matching fingerprints, encrypts the complete validated snapshot **in memory**, then writes only the ciphertext envelope with `O_EXCL`, `O_NOFOLLOW`, `0600`, and fsync.

The envelope uses a fresh 256-bit AES key and 96-bit nonce with AES-GCM authentication. RSA-4096 OAEP with SHA-256 wraps the AES key. Version, cipher, key algorithm, and recipient public-key fingerprint are authenticated as additional data. The serialized plaintext buffer and AES key buffers are cleared after use; source objects still exist in process memory until garbage-collected. Do not enable heap dumps, profiling, debug snapshots, or verbose data logging for this build.

Only the encrypted envelope may become a static asset. Never write a raw intermediate JSON snapshot or private key into a build directory. Do not modify the normal package build, production dashboard build command, or source credentials to make export automatic. Do not add an API endpoint that returns plaintext data, accepts an arbitrary recipient key, or creates repeatable exports on demand.

3. Stage without assigning the production domain, using the already authorized deployment flow. Do not promote this backup-only deployment. Check its build receipt: source project identity, document/collection counts, source fingerprint, recipient fingerprint, and `plaintextFileWritten: false`. Download the opaque encrypted asset into the private local directory. Its asset URL is not an authentication mechanism; confidentiality comes from the authenticated encryption and the separately held private key. Keep the deployment inaccessible under normal preview protection when available.

4. Authenticate and decrypt locally, using the independently recorded project and source fingerprint from the trusted build receipt:

```sh
npx tsx scripts/decryptFirestoreSnapshot.ts \
  --mode decrypt \
  --private-key '/private/owner-only-directory/snapshot-private.pem' \
  --encrypted '/private/owner-only-directory/downloaded-encrypted-snapshot.json' \
  --snapshot '/private/owner-only-directory/firestore-before-cutover.json' \
  --expect-source-project '<verified-active-project-id>' \
  --expect-source-sha256 '<reviewed-source-sha256>'
```

The local helper refuses to run private-key operations in a Vercel environment. It requires an owner-only private key, rejects unexpected recipients/algorithms, authenticates the ciphertext before using it, then validates the snapshot format, project, complete source checksum, and independently supplied expected checksum before creating a new `0600` plaintext snapshot. Tampering, truncation, a wrong key, a wrong project, or a changed fingerprint fails without writing plaintext. No key material, record, or credential is printed.

The envelope checksum is not a digital signature from Google or Vercel. Trust the deployment identity and source fingerprint independently; do not trust a replacement download and replacement fingerprint supplied together by an untrusted page.

### Encrypted capture limits and source verification

This produces a backup, not a source write freeze, a complete migration, or an approved traffic cutover. The build receipt explicitly says `sourceWriteFreezeVerified: false`. Two matching scans do not establish global consistency while writers remain active. The same complete old-writer freeze is required before the final cutover capture.

The exporter optionally accepts `--expect-source-sha256 '<prior-verified-fingerprint>'`. A stable capture that differs from that fingerprint aborts before writing an envelope. This can be used for a fresh build-side source drift check when local source credentials remain unavailable. A new pre-cutover encrypted capture can also be decrypted locally and compared with the earlier snapshot. Neither mechanism permits the general import command to skip its required source checks. The explicitly invoked live-source wrapper below performs those checks inside the existing credential environment when local source credentials remain unavailable.

The bounded exporter permits at most 128 MiB of serialized plaintext in memory and 256 MiB of envelope input. Larger sources require a separately reviewed streaming/encryption implementation, not a larger unreviewed buffer or a plaintext temporary file.

After preserving and verifying the private backup, retire the backup-only deployment through the normal approved lifecycle. Do not remove a deployment while it is still the only verified copy of the encrypted backup, and never delete the Firestore source as backup cleanup.

## Historical live-source import inside the established credential environment

`scripts/importFirestoreLiveToMongo.ts` is a separate one-time entry point for a final frozen-source migration when Firestore credentials must remain inside the established production build environment. It is never invoked by the ordinary app, API, or package build. Read it and its imported helpers end-to-end before staging it. The parent must already have completed the old-writer freeze, independently reviewed the **final** encrypted export's source project and fingerprint, and approved the exact empty Atlas target.

Copy only reviewed migration scripts, `src/lib/mongodb.ts`, and the public recipient key into the isolated staged source. Keep the RSA private key, decrypted snapshots, and credential files local. Supply the authorized target `MONGODB_URI` and `MONGODB_DB` through the approved deployment environment. Source credential values remain in the existing Vercel environment. Stage without assigning the production domain and do not promote the migration-only deployment.

```sh
mkdir -p public/_migration
npx tsx scripts/importFirestoreLiveToMongo.ts \
  --expect-source-project '<independently-verified-active-project-id>' \
  --expect-source-sha256 '<independently-reviewed-final-source-sha256>' \
  --mongo-db 'pocketpeek_migration_20261107' \
  --public-key 'snapshot-recipient-public.pem' \
  --encrypted-output 'public/_migration/<fresh-opaque-final-backup-filename>.json'
```

All five flags are mandatory. The source project must match the active established `FIREBASE_PROJECT_ID`, and the destination must exactly match `MONGODB_DB` and the connected client's selected database. System databases, malformed fingerprints, missing source credentials, emulator source settings, or a source snapshot that differs from the independently reviewed final export are refused. The verification connection uses `promoteLongs: false` so native 64-bit BSON values retain their exact comparison type.

The wrapper performs these operations in order:

1. Capture the live source twice, require identical fingerprints, and require that fingerprint to equal the independently reviewed final export.
2. Encrypt the entire validated snapshot in memory and durably create a new ciphertext-only backup before connecting to or writing the target. A backup failure aborts before any target writes.
3. Reject existing TTL deletion indexes, recheck the source fingerprint, and invoke the same create-only `planMigration`/`importSnapshot` implementation. Existing target conflicts, extras, or a different migration marker fail closed. No data is merged, replaced, or deleted.
4. Recheck the source and verify all target records exactly. Then compute the destination counts and fingerprint independently from MongoDB readback, comparing with the expected source mapping.
5. Explicitly call the existing native `ensureIndexes`, after data parity. Index conflicts stop the process without deleting or deduplicating records. Independently read back all eleven required runtime index key, uniqueness, and partial-string-filter contracts. No TTL index is allowed anywhere in the target.
6. Reverify all destination data and recheck the source once more after index creation. Only then emit a successful counts/hash receipt. Close both the verification connection and the helper's index-creation connection.

The receipt contains source project, target database, counts, hashes, verified index count, and boolean safeguards. It does not contain source documents, emails, password hashes, keys, connection strings, or a plaintext snapshot. It continues to report `sourceWriteFreezeVerified: false`: fingerprint checks detect drift but do not establish the parent-owned write freeze. A failure may leave an exact resumable **destination** or a ciphertext backup; Firestore is untouched, and traffic must stay on the existing store until the complete migration and staged application have passed verification.

Download, authenticate, and locally decrypt the newly produced final backup as described above. Inspect the trusted deployment's complete migration receipt and verify the new backend against the selected Atlas database before promotion. This wrapper does not switch domains, change credentials, enable traffic, delete the source, or authorize a blind rollback after Mongo accepts new writes.
