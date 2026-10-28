// One-time copy of the BudgetWise data from MongoDB into Firestore.
//
// Dry run by default: reads both sides and prints what it would create.
//   MONGODB_URI=... npm run copy:mongo-to-firestore             (dry run)
//   MONGODB_URI=... npm run copy:mongo-to-firestore -- --write  (copies)
// Firestore is configured exactly as for the app (see src/lib/firestore.ts).
// Run it before the Firestore app takes traffic.
//
// - Users and transactions keep their Mongo ObjectId hex as the doc id, so
//   mobile JWT user ids and the Android app's server ids stay valid.
// - Emails become unique regardless of case (userEmails). For case-variant
//   duplicates the oldest account wins; the others are skipped with their data.
// - Create-only. A user's transactions, budgets and categories are written
//   before the user doc, and a user whose doc already exists is skipped whole,
//   so reruns are idempotent and never bring back docs deleted in the app.
// - Rows whose user does not exist are skipped. Rate limits are not copied.
// - Prints counts only, never emails or other personal data.
import { MongoClient, ObjectId, type Db, type Document } from 'mongodb';
import type { DocumentReference, Firestore } from 'firebase-admin/firestore';
import { categorySlug, db, isDocId, sha256Hex } from '../src/lib/firestore';
import { emailDocId, normalizeEmail } from '../src/lib/users';

const READ_CHUNK = 100;
const WRITE_CHUNK = 400;

export interface UserCounts {
  source: number;
  created: number;
  alreadyCopied: number;
  duplicateEmail: number;
  emailTaken: number;
  invalid: number;
}

export interface RowCounts {
  source: number;
  created: number;
  existing: number;
  duplicate: number;
  orphaned: number;
  userSkipped: number;
  userAlreadyCopied: number;
  invalid: number;
}

export interface CopyReport {
  users: UserCounts;
  transactions: RowCounts;
  budgets: RowCounts;
  userCategories: RowCounts;
  globalCategories: Omit<RowCounts, 'orphaned' | 'userSkipped' | 'userAlreadyCopied'>;
  rateLimitsSkipped: number;
}

interface PlannedDoc {
  ref: DocumentReference;
  data: Record<string, unknown>;
}

const rowCounts = (source: number): RowCounts => ({
  source,
  created: 0,
  existing: 0,
  duplicate: 0,
  orphaned: 0,
  userSkipped: 0,
  userAlreadyCopied: 0,
  invalid: 0,
});

function chunks<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

// Mongo ids as Firestore doc ids: ObjectIds become their hex string.
function idOf(value: unknown): string | null {
  const id = value instanceof ObjectId ? value.toHexString() : value;
  return isDocId(id) ? id : null;
}

const idTime = (value: unknown): Date | undefined =>
  value instanceof ObjectId ? value.getTimestamp() : undefined;

const dateOr = (value: unknown, fallback: Date | undefined): Date | undefined =>
  value instanceof Date ? value : fallback;

async function existingPaths(firestore: Firestore, refs: DocumentReference[]): Promise<Set<string>> {
  const found = new Set<string>();
  for (const part of chunks(refs, READ_CHUNK)) {
    if (part.length === 0) continue;
    for (const snap of await firestore.getAll(...part)) if (snap.exists) found.add(snap.ref.path);
  }
  return found;
}

async function createAll(firestore: Firestore, docs: PlannedDoc[]): Promise<void> {
  for (const part of chunks(docs, WRITE_CHUNK)) {
    const batch = firestore.batch();
    for (const { ref, data } of part) batch.create(ref, data);
    await batch.commit();
  }
}

// Keeps the planned docs that are not in Firestore yet and counts the rest.
async function keepMissing(firestore: Firestore, docs: PlannedDoc[], counts: { created: number; existing: number }) {
  const existing = await existingPaths(firestore, docs.map((d) => d.ref));
  const missing = docs.filter((d) => !existing.has(d.ref.path));
  counts.existing += docs.length - missing.length;
  counts.created += missing.length;
  return missing;
}

function transactionData(row: Document, userId: string): Record<string, unknown> | null {
  const { date, description, amount, category, type, clientId, source, transcript } = row;
  if (
    typeof date !== 'string' ||
    typeof description !== 'string' ||
    typeof amount !== 'number' ||
    typeof category !== 'string' ||
    (type !== 'income' && type !== 'expense')
  ) {
    return null;
  }
  // Same defaults the old backfill script applied: times from the ObjectId, source web.
  const createdAt = dateOr(row.createdAt, idTime(row._id));
  return {
    userId,
    date,
    description,
    amount,
    category,
    type,
    ...(typeof clientId === 'string' && clientId ? { clientId } : {}),
    source: source === 'voice' || source === 'manual' || source === 'web' ? source : 'web',
    ...(typeof transcript === 'string' && transcript ? { transcript } : {}),
    createdAt,
    updatedAt: dateOr(row.updatedAt, createdAt),
  };
}

export async function copyMongoToFirestore(
  mongo: Db,
  firestore: Firestore,
  { write }: { write: boolean },
): Promise<CopyReport> {
  // Users, oldest first, so the oldest of a set of case-variant emails wins.
  const mongoUsers = (await mongo.collection('users').find({}).toArray())
    .map((u) => ({ u, id: idOf(u._id), created: dateOr(u.createdAt, idTime(u._id)) }))
    .sort(
      (a, b) =>
        (a.created?.getTime() ?? 0) - (b.created?.getTime() ?? 0) || String(a.id).localeCompare(String(b.id)),
    );

  const users: UserCounts = {
    source: mongoUsers.length,
    created: 0,
    alreadyCopied: 0,
    duplicateEmail: 0,
    emailTaken: 0,
    invalid: 0,
  };
  const knownIds = new Set<string>(); // every Mongo user id, copied or not
  const skipped = new Set<string>(); // users whose data is skipped with them
  const winners: { id: string; email: string; emailLower: string; u: Document; created?: Date }[] = [];
  const seenEmails = new Set<string>();
  for (const { u, id, created } of mongoUsers) {
    if (id) knownIds.add(id);
    const email = typeof u.email === 'string' ? normalizeEmail(u.email) : null;
    if (!id || !email?.email) {
      users.invalid++;
      if (id) skipped.add(id);
      continue;
    }
    if (seenEmails.has(email.emailLower)) {
      users.duplicateEmail++;
      skipped.add(id);
      continue;
    }
    seenEmails.add(email.emailLower);
    winners.push({ id, ...email, u, created });
  }

  const userRef = (id: string) => firestore.collection('users').doc(id);
  const emailRef = (emailLower: string) => firestore.collection('userEmails').doc(emailDocId(emailLower));
  const copiedBefore = await existingPaths(firestore, winners.map((w) => userRef(w.id)));
  const emailSnaps = new Map<string, string | undefined>();
  for (const part of chunks(winners, READ_CHUNK)) {
    if (part.length === 0) continue;
    const snaps = await firestore.getAll(...part.map((w) => emailRef(w.emailLower)));
    snaps.forEach((snap, i) => {
      if (snap.exists) emailSnaps.set(part[i].id, String(snap.get('userId')));
    });
  }

  const alreadyCopied = new Set<string>();
  const userDocs: PlannedDoc[] = [];
  for (const w of winners) {
    if (copiedBefore.has(userRef(w.id).path)) {
      users.alreadyCopied++;
      alreadyCopied.add(w.id);
      continue;
    }
    const owner = emailSnaps.get(w.id);
    if (owner !== undefined && owner !== w.id) {
      // Someone already signed up with this email on the Firestore app.
      users.emailTaken++;
      skipped.add(w.id);
      continue;
    }
    const { password, fullName, phoneNumber, profilePhotoUrl } = w.u;
    userDocs.push({
      ref: userRef(w.id),
      data: {
        email: w.email,
        emailLower: w.emailLower,
        ...(typeof password === 'string' ? { password } : {}),
        ...(fullName !== undefined ? { fullName } : {}),
        ...(phoneNumber !== undefined ? { phoneNumber } : {}),
        ...(profilePhotoUrl !== undefined ? { profilePhotoUrl } : {}),
        createdAt: w.created,
        updatedAt: dateOr(w.u.updatedAt, w.created),
      },
    });
    if (owner === undefined) userDocs.push({ ref: emailRef(w.emailLower), data: { userId: w.id } });
  }
  users.created = userDocs.filter((d) => d.ref.parent.id === 'users').length;

  // Rows owned by a user: decide by the owner first, then build the doc.
  const planRows = (
    rows: Document[],
    counts: RowCounts,
    build: (row: Document, userId: string) => PlannedDoc | null,
  ): PlannedDoc[] => {
    const planned = new Map<string, PlannedDoc>();
    for (const row of rows) {
      const userId = idOf(row.userId);
      if (!userId || !knownIds.has(userId)) counts.orphaned++;
      else if (skipped.has(userId)) counts.userSkipped++;
      else if (alreadyCopied.has(userId)) counts.userAlreadyCopied++;
      else {
        const doc = build(row, userId);
        if (!doc) counts.invalid++;
        else if (planned.has(doc.ref.path)) counts.duplicate++;
        else planned.set(doc.ref.path, doc);
      }
    }
    return [...planned.values()];
  };

  const mongoTransactions = await mongo.collection('transactions').find({}).toArray();
  const transactions = rowCounts(mongoTransactions.length);
  const transactionDocs = planRows(mongoTransactions, transactions, (row, userId) => {
    const id = idOf(row._id);
    const data = transactionData(row, userId);
    return id && data ? { ref: userRef(userId).collection('transactions').doc(id), data } : null;
  });

  const mongoBudgets = await mongo.collection('budgets').find({}).toArray();
  const budgets = rowCounts(mongoBudgets.length);
  const budgetDocs = planRows(mongoBudgets, budgets, (row, userId) => {
    const { category, limit } = row;
    if (typeof category !== 'string' || !category || typeof limit !== 'number') return null;
    const at = idTime(row._id);
    return {
      ref: userRef(userId).collection('budgets').doc(sha256Hex(category)),
      data: { userId, category, limit, createdAt: at, updatedAt: at },
    };
  });

  const mongoCategories = await mongo.collection('categories').find({}).sort({ _id: 1 }).toArray();
  const ownCategories = mongoCategories.filter((c) => c.userId !== undefined && c.userId !== null);
  const sharedCategories = mongoCategories.filter((c) => c.userId === undefined || c.userId === null);
  const userCategories = rowCounts(ownCategories.length);
  const userCategoryDocs = planRows(ownCategories, userCategories, (row, userId) => {
    const id = idOf(row._id);
    if (!id || typeof row.name !== 'string' || !row.name) return null;
    return {
      ref: userRef(userId).collection('categories').doc(id),
      data: { name: row.name, createdAt: idTime(row._id) },
    };
  });

  const globalCategories: CopyReport['globalCategories'] = {
    source: sharedCategories.length,
    created: 0,
    existing: 0,
    duplicate: 0,
    invalid: 0,
  };
  const globalDocs = new Map<string, PlannedDoc>();
  sharedCategories.forEach((row, sort) => {
    if (typeof row.name !== 'string' || !row.name.trim()) {
      globalCategories.invalid++;
      return;
    }
    const ref = firestore.collection('categories').doc(categorySlug(row.name));
    if (globalDocs.has(ref.path)) globalCategories.duplicate++;
    else globalDocs.set(ref.path, { ref, data: { name: row.name, sort } });
  });

  const missingRows = [
    ...(await keepMissing(firestore, transactionDocs, transactions)),
    ...(await keepMissing(firestore, budgetDocs, budgets)),
    ...(await keepMissing(firestore, userCategoryDocs, userCategories)),
    ...(await keepMissing(firestore, [...globalDocs.values()], globalCategories)),
  ];
  const rateLimitsSkipped = await mongo.collection('ratelimits').countDocuments();

  if (write) {
    // Owned rows first; the user doc is the marker that its data is complete.
    await createAll(firestore, missingRows);
    await createAll(firestore, userDocs);
  }

  return { users, transactions, budgets, userCategories, globalCategories, rateLimitsSkipped };
}

export function formatReport(report: CopyReport, write: boolean): string {
  const verb = write ? 'created' : 'to create';
  const line = (name: string, counts: object) =>
    `${name}: ${Object.entries(counts)
      .map(([k, v]) => `${k === 'created' ? verb : k} ${v}`)
      .join(', ')}`;
  return [
    write ? 'Mode: write.' : 'Mode: dry run, nothing written. Re-run with --write to copy.',
    line('users', report.users),
    line('transactions', report.transactions),
    line('budgets', report.budgets),
    line('user categories', report.userCategories),
    line('global categories', report.globalCategories),
    `rate limits: skipped ${report.rateLimitsSkipped}`,
  ].join('\n');
}

async function main() {
  const write = process.argv.includes('--write');
  const uri = process.env.MONGODB_URI;
  if (!uri) throw new Error('MONGODB_URI is not set');
  const target = process.env.FIRESTORE_EMULATOR_HOST
    ? `the emulator at ${process.env.FIRESTORE_EMULATOR_HOST}`
    : `project ${process.env.FIREBASE_PROJECT_ID ?? '(from GOOGLE_APPLICATION_CREDENTIALS)'}`;
  const client = await MongoClient.connect(uri);
  try {
    const mongo = client.db();
    console.log(`Reading Mongo database "${mongo.databaseName}", Firestore target: ${target}.`);
    const report = await copyMongoToFirestore(mongo, db(), { write });
    console.log(formatReport(report, write));
  } finally {
    await client.close();
  }
}

if (process.argv[1] && /copyMongoToFirestore\.[cm]?[jt]s$/.test(process.argv[1])) {
  main().catch((err) => {
    console.error(err instanceof Error ? err.message : err);
    process.exit(1);
  });
}
