'use server';

/**
 * @fileOverview Provides an AI-powered transaction categorizer.
 *
 * - categorizeTransaction - A function that suggests a category for a transaction description.
 * - CategorizeTransactionInput - The input type for the categorizeTransaction function.
 * - CategorizeTransactionOutput - The return type for the categorizeTransaction function.
 */

import {ai} from '@/ai/genkit';
import {z} from 'genkit';

const CategorizeTransactionInputSchema = z.object({
  description: z.string().describe('The transaction description, e.g., "Starbucks Coffee"'),
  categories: z.array(z.string()).describe('The list of available expense categories.'),
});
export type CategorizeTransactionInput = z.infer<typeof CategorizeTransactionInputSchema>;

const CategorizeTransactionOutputSchema = z.object({
  category: z.string().describe('The suggested category for the transaction.'),
});
export type CategorizeTransactionOutput = z.infer<typeof CategorizeTransactionOutputSchema>;

export async function categorizeTransaction(input: CategorizeTransactionInput): Promise<CategorizeTransactionOutput> {
  return categorizeTransactionFlow(input);
}

const prompt = ai.definePrompt({
  name: 'categorizeTransactionPrompt',
  input: {schema: CategorizeTransactionInputSchema},
  output: {schema: CategorizeTransactionOutputSchema},
  prompt: `You are an expert at categorizing financial transactions.
Based on the transaction description, select the most appropriate category from the provided list.

Transaction Description: "{{{description}}}"

Available Categories:
{{#each categories}}
- {{{this}}}
{{/each}}

Select only one category from the list.
`,
});

const categorizeTransactionFlow = ai.defineFlow(
  {
    name: 'categorizeTransactionFlow',
    inputSchema: CategorizeTransactionInputSchema,
    outputSchema: CategorizeTransactionOutputSchema,
  },
  async input => {
    if (!input.description?.trim()) {
        return { category: '' };
    }
    const {output} = await prompt(input);
    return output!;
  }
);