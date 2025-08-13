'use server';

/**
 * @fileOverview Provides AI-powered suggestions on areas where users can cut spending.
 *
 * - getSpendingSuggestions - A function that returns spending suggestions.
 * - SpendingSuggestionsInput - The input type for the getSpendingSuggestions function.
 * - SpendingSuggestionsOutput - The return type for the getSpendingSuggestions function.
 */

import {ai} from '@/ai/genkit';
import {z} from 'genkit';

const SpendingSuggestionsInputSchema = z.object({
  historicalData: z
    .string()
    .describe(
      'Historical spending data, including categories, amounts, and dates.  Should be a CSV format string with columns category,amount,date.\nExample: category,amount,date\ngroceries,100,2024-01-01\nutilities,200,2024-01-01'
    ),
  budgetGoals: z
    .string()
    .describe(
      'User-defined budget goals for each category. Should be a CSV format string with columns category,budget.\nExample: category,budget\ngroceries,500\nutilities,300'
    ),
});
export type SpendingSuggestionsInput = z.infer<typeof SpendingSuggestionsInputSchema>;

const SpendingSuggestionsOutputSchema = z.object({
  suggestions: z.array(z.string()).describe('AI-powered suggestions on areas to cut spending.'),
});
export type SpendingSuggestionsOutput = z.infer<typeof SpendingSuggestionsOutputSchema>;

export async function getSpendingSuggestions(input: SpendingSuggestionsInput): Promise<SpendingSuggestionsOutput> {
  return spendingSuggestionsFlow(input);
}

const prompt = ai.definePrompt({
  name: 'spendingSuggestionsPrompt',
  input: {schema: SpendingSuggestionsInputSchema},
  output: {schema: SpendingSuggestionsOutputSchema},
  prompt: `You are a savvy personal finance advisor. Your goal is to provide highly specific, actionable, and data-driven advice to help a user cut spending.

Analyze the user's historical spending data provided below. Compare it against their stated budget goals.

**Historical Spending Data (CSV):**
\`\`\`csv
{{{historicalData}}}
\`\`\`

**Budget Goals (CSV):**
\`\`\`csv
{{{budgetGoals}}}
\`\`\`

Based on your analysis, provide a list of concrete suggestions. For each suggestion:
1.  **Identify a specific category** where spending is high or over budget.
2.  **Quantify the problem.** For example, "You spent $150 on 'Entertainment' this month, which is $50 over your $100 budget."
3.  **Offer a clear, actionable tip.** Instead of "spend less on food," suggest "try meal prepping for lunches to reduce your $200 'Restaurants' spending."
4.  Keep each suggestion concise and to the point.

Generate at least 3 high-quality suggestions.
`,
});

const spendingSuggestionsFlow = ai.defineFlow(
  {
    name: 'spendingSuggestionsFlow',
    inputSchema: SpendingSuggestionsInputSchema,
    outputSchema: SpendingSuggestionsOutputSchema,
  },
  async input => {
    const {output} = await prompt(input);
    return output!;
  }
);