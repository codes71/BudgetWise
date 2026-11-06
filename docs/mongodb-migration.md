# Firestore to MongoDB Atlas migration

Pocket Peek uses its existing server API and custom bcrypt/JWT authentication. This migration changes the server database, preserving logical account and record IDs, existing password hashes, and API response shapes. Google sign-in remains a separate feature. The Android Room database and the existing BudgetWise installation are not modified by these tools.

The tools are implemented in `apps/web/scripts/firestoreSnapshot.ts` and `apps/web/scripts/migrateFirestoreToMongo.ts`. They do not delete, update, or seed Firestore. No command in this guide establishes that a production migration or cutover has already happened.

## Safety contract

- Read both scripts end-to-end and verify the exact source project, Atlas cluster, destination database, production deployment, and rollback deployment before running them.
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

The safest rollback window is before accepting new Mongo writes: keep all old writers frozen, restore the previously verified Firestore-backed deployment, and resume writes there after checking the source still matches the snapshot. Do not delete the imported Atlas database or private snapshot as part of rollback.

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
npx tsx scripts/exportFirestoreEncrypted.ts \
  --public-key 'snapshot-recipient-public.pem' \
  --output 'public/<fresh-opaque-backup-filename>.json' \
  --expect-source-project '<verified-active-project-id>'
```

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

## Explicit live-source import inside the established credential environment

`scripts/importFirestoreLiveToMongo.ts` is a separate one-time entry point for a final frozen-source migration when Firestore credentials must remain inside the established production build environment. It is never invoked by the ordinary app, API, or package build. Read it and its imported helpers end-to-end before staging it. The parent must already have completed the old-writer freeze, independently reviewed the **final** encrypted export's source project and fingerprint, and approved the exact empty Atlas target.

Copy only reviewed migration scripts, `src/lib/mongodb.ts`, and the public recipient key into the isolated staged source. Keep the RSA private key, decrypted snapshots, and credential files local. Supply the authorized target `MONGODB_URI` and `MONGODB_DB` through the approved deployment environment. Source credential values remain in the existing Vercel environment. Stage without assigning the production domain and do not promote the migration-only deployment.

```sh
npx tsx scripts/importFirestoreLiveToMongo.ts \
  --expect-source-project '<independently-verified-active-project-id>' \
  --expect-source-sha256 '<independently-reviewed-final-source-sha256>' \
  --mongo-db 'pocketpeek_migration_20261107' \
  --public-key 'snapshot-recipient-public.pem' \
  --encrypted-output 'public/<fresh-opaque-final-backup-filename>.json'
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
