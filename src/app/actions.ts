'use server';

import { getSpendingSuggestions } from '@/ai/flows/spending-suggestions';
import type { Transaction, Budget } from '@/lib/types';

export async function generateSuggestions(transactions: Transaction[], budgets: Budget[]) {
  const historicalData =
    'category,amount,date\n' +
    transactions
      .filter((t) => t.type === 'expense')
      .map((t) => `${t.category},${t.amount},${t.date}`)
      .join('\n');
      
  const budgetGoals = 'category,budget\n' + budgets.map((b) => `${b.category},${b.limit}`).join('\n');

  try {
    const result = await getSpendingSuggestions({ historicalData, budgetGoals });
    return { suggestions: result.suggestions };
  } catch (error) {
    console.error('Error generating suggestions:', error);
    return { error: 'Failed to generate suggestions. Please try again.' };
  }
}
