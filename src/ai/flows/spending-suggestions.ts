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
  prompt: `You are a personal finance advisor providing suggestions to users on how to cut spending based on their historical data and budget goals.

Analyze the following historical spending data:
{{{historicalData}}}

Consider these budget goals:
{{{budgetGoals}}}

Provide a list of specific, actionable suggestions on areas where the user can cut spending. Focus on areas where spending exceeds budget goals or where historical spending shows potential for reduction.

Format your response as a list of strings.
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
