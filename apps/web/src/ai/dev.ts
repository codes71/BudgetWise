import { config } from 'dotenv';
config();

import '@/ai/genkit'; // Import genkit.ts to ensure ai is initialized
import '@/ai/flows/spending-suggestions.ts';
import '@/ai/flows/categorize-transaction.ts';
import '@/ai/flows/extract-transaction-data.ts'; // New import