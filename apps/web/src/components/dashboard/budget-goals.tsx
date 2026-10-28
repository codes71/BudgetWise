import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { ScrollArea } from '@/components/ui/scroll-area';
import { CategoryIcon } from '@/components/category-icon';
import type { Transaction, Budget } from '@/lib/types';
import { useAuth } from '@/context/auth-context';
import { formatCurrency } from '@/lib/utils';

interface BudgetGoalsProps {
  transactions: Transaction[];
  budgets: Budget[];
}

export function BudgetGoals({ transactions, budgets }: BudgetGoalsProps) {
  const { currency } = useAuth();
  const spendingByCategory = transactions
    .filter(t => t.type === 'expense')
    .reduce((acc, t) => {
      acc[t.category] = (acc[t.category] || 0) + t.amount;
      return acc;
    }, {} as Record<string, number>);

  return (
    <Card className="h-full shadow-sm">
      <CardHeader>
        <CardTitle>Budget Goals</CardTitle>
        <CardDescription>Track your spending against your monthly limits.</CardDescription>
      </CardHeader>
      <CardContent>
        <ScrollArea className="h-[300px]">
          <div className="space-y-4 pr-4">
            {budgets.map(budget => {
              const spent = spendingByCategory[budget.category] || 0;
              const progress = (spent / budget.limit) * 100;
              const isOverBudget = spent > budget.limit;
              
              return (
                <div key={budget.category}>
                  <div className="flex items-center justify-between mb-1">
                    <div className="flex items-center gap-2">
                       <CategoryIcon category={budget.category} className="h-4 w-4 text-muted-foreground" />
                       <span className="text-sm font-medium">{budget.category}</span>
                    </div>
                    <span className={`text-sm ${isOverBudget ? 'text-destructive' : 'text-muted-foreground'}`}>
                      {formatCurrency(spent, currency)} / {formatCurrency(budget.limit, currency)}
                    </span>
                  </div>
                  <Progress value={Math.min(progress, 100)} className={isOverBudget ? '[&>div]:bg-destructive' : ''} />
                </div>
              );
            })}
          </div>
        </ScrollArea>
      </CardContent>
    </Card>
  );
}
