import dbConnect from '../src/lib/db'; // Adjust path as needed
import CategoryModel from '../src/lib/models/category'; // Adjust path as needed

const defaultCategories = [
  'Groceries',
  'Utilities',
  'Entertainment',
  'Transport',
  'Housing',
  'Health',
  'Food and Drink Item',
  'Other' // Add Income as a category if it's not already there
];

async function seedCategories() {
  await dbConnect();
  console.log('Database connected for seeding.');

  for (const categoryName of defaultCategories) {
    try {
      // Check if category already exists
      const existingCategory = await CategoryModel.findOne({ name: categoryName });
      if (!existingCategory) {
        await CategoryModel.create({ name: categoryName });
        console.log(`Seeded category: ${categoryName}`);
      } else {
        console.log(`Category already exists: ${categoryName}`);
      }
    } catch (error) {
      console.error(`Error seeding category ${categoryName}:`, error);
    }
  }

  console.log('Category seeding complete.');
  // Disconnect after seeding
  // mongoose.disconnect() is usually handled by Next.js's serverless functions
  // For a standalone script, you might need to explicitly disconnect if not using a persistent connection.
  // For now, we'll rely on the process exiting.
  process.exit(0);
}

seedCategories().catch(err => {
  console.error('Failed to seed categories:', err);
  process.exit(1);
});
