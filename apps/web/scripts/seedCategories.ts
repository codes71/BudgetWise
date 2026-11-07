// Seeds the global categories into MongoDB with slug ids and { name, sort }.
//
//   npm run seed:categories
//
// Uses the app's MongoDB config. Only inserts missing docs, never overwrites.
import type { Db } from 'mongodb';
import { categorySlug, db, isDuplicateKey, type MongoRecord } from '../src/lib/mongodb';

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

export async function seedCategories(database?: Db): Promise<{ created: number; existing: number }> {
  const categories = (database ?? await db()).collection<MongoRecord>('categories');
  let created = 0;
  let existing = 0;
  for (const [sort, name] of DEFAULT_CATEGORIES.entries()) {
    try {
      await categories.insertOne({ _id: categorySlug(name), name, sort });
      created++;
    } catch (err) {
      if (!isDuplicateKey(err)) throw err;
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
