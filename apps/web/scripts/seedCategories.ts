// Seeds the global categories into Firestore as categories/{slug} { name, sort }.
//
//   npm run seed:categories
//
// Uses the same Firestore config as the app (see src/lib/firestore.ts). It
// only creates missing docs and never overwrites one, so it is safe to rerun.
import type { Firestore } from 'firebase-admin/firestore';
import { ALREADY_EXISTS, categorySlug, db, errorCode } from '../src/lib/firestore';

export const DEFAULT_CATEGORIES = [
  'Groceries',
  'Utilities',
  'Entertainment',
  'Transport',
  'Housing',
  'Health',
  'Food and Drink Item',
  'Other',
];

export async function seedCategories(firestore: Firestore = db()): Promise<{ created: number; existing: number }> {
  let created = 0;
  let existing = 0;
  for (const [sort, name] of DEFAULT_CATEGORIES.entries()) {
    try {
      await firestore.collection('categories').doc(categorySlug(name)).create({ name, sort });
      created++;
    } catch (err) {
      if (errorCode(err) !== ALREADY_EXISTS) throw err;
      existing++;
    }
  }
  return { created, existing };
}

if (process.argv[1] && /seedCategories\.[cm]?[jt]s$/.test(process.argv[1])) {
  seedCategories()
    .then(({ created, existing }) => {
      console.log(`Category seeding complete: ${created} created, ${existing} already existed.`);
      process.exit(0);
    })
    .catch((err) => {
      console.error('Failed to seed categories:', err instanceof Error ? err.message : err);
      process.exit(1);
    });
}
