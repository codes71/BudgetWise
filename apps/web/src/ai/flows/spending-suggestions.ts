import * as z from 'zod/v4';
import { formatKyat } from '@/lib/kyat';

// A bounded budget comparison, not a chat/advice endpoint. No provider receives ledger data here.
const InputSchema = z.object({
  transactions: z.array(z.object({
    category: z.string().min(1).max(200), amount: z.number().int().safe(),
    date: z.string(), type: z.enum(['income', 'expense']),
  })).max(10_000),
  budgets: z.array(z.object({ category: z.string().min(1).max(200), limit: z.number().int().safe().nonnegative() })).max(1_000),
  today: z.iso.date(),
});
export type SpendingSuggestionsInput = z.infer<typeof InputSchema>;
export type SpendingSuggestionsOutput = { suggestions: string[] };

export async function getSpendingSuggestions(input: SpendingSuggestionsInput): Promise<SpendingSuggestionsOutput> {
  const { transactions, budgets, today } = InputSchema.parse(input);
  const month = today.slice(0, 7);
  const spent = new Map<string, number>();
  for (const row of transactions) {
    if (row.type !== 'expense' || !z.iso.date().safeParse(row.date).success
      || row.date.slice(0, 7) !== month || row.date > today) continue;
    const total = (spent.get(row.category) ?? 0) + row.amount;
    if (!Number.isSafeInteger(total)) throw new Error('Recorded amounts are too large to compare safely.');
    spent.set(row.category, total);
  }
  if (!spent.size) return { suggestions: [`No expense entries are recorded for ${month} through ${today}.`] };
  const limits = new Map(budgets.map((row) => [row.category, row.limit]));
  if (limits.size !== budgets.length) throw new Error('Category budgets must be unique.');
  const suggestions = [...spent].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, 5).map(([category, amount]) => {
    const limit = limits.get(category);
    const start = `${category}: net recorded expenses for ${month} through ${today} are ${formatKyat(amount)}.`;
    if (limit === undefined) return `${start} No category budget is set; you can add one to compare spending.`;
    const difference = amount - limit;
    if (!Number.isSafeInteger(difference)) throw new Error('Recorded amounts are too large to compare safely.');
    return difference > 0
      ? `${start} This is ${formatKyat(difference)} over the ${formatKyat(limit)} budget. Review the entries and your planned spending.`
      : `${start} This leaves ${formatKyat(-difference)} of the ${formatKyat(limit)} budget. Pending or unrecorded spending is not included.`;
  });
  return { suggestions };
}
