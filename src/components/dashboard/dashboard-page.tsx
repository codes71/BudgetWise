'use client';

import { useState, useMemo } from 'react';
import { DollarSign, Upload, Sparkles, PlusCircle, Settings, Landmark } from 'lucide-react';
import type { Transaction, Budget } from '@/lib/types';
import { mockTransactions, mockBudgets } from '@/lib/data';
import { Button } from '@/components/ui/button';
import { OverviewCards } from './overview-cards';
import { SpendingChart } from './spending-chart';
import { BudgetGoals } from './budget-goals';
import { RecentTransactions } from './recent-transactions';
import { DataImporter } from './data-importer';
import { AiSuggestions } from './ai-suggestions';
import { ThemeToggle } from './theme-toggle';
import { AddTransaction } from './add-transaction';
import { SetBudget } from './set-budget';


export function DashboardPage() {
  const [transactions, setTransactions] = useState<Transaction[]>(mockTransactions);
  const [budgets, setBudgets] = useState<Budget[]>(mockBudgets);

  const handleDataImported = (importedTransactions: Transaction[], importedBudgets: Budget[]) => {
    setTransactions(prev => [...prev, ...importedTransactions]);
    // For budgets, we can decide to merge or replace. Here we'll merge/update.
    setBudgets(prevBudgets => {
      const budgetMap = new Map(prevBudgets.map(b => [b.category, b]));
      importedBudgets.forEach(b => budgetMap.set(b.category, b));
      return Array.from(budgetMap.values());
    });
  };

  const handleTransactionAdded = (transaction: Transaction) => {
    setTransactions(prev => [transaction, ...prev]);
  };
  
  const handleBudgetSet = (budget: Budget) => {
     setBudgets(prev => {
        const newBudgets = [...prev];
        const existingBudgetIndex = newBudgets.findIndex(b => b.category === budget.category);
        if (existingBudgetIndex > -1) {
          newBudgets[existingBudgetIndex] = budget;
        } else {
          newBudgets.push(budget);
        }
        return newBudgets;
     });
  };
  
  const overview = useMemo(() => {
    const income = transactions.filter(t => t.type === 'income').reduce((acc, t) => acc + t.amount, 0);
    const expenses = transactions.filter(t => t.type === 'expense').reduce((acc, t) => acc + t.amount, 0);
    const balance = income - expenses;
    return { income, expenses, balance };
  }, [transactions]);
  
  return (
    <div className="flex flex-col min-h-screen">
      <header className="sticky top-0 z-40 w-full border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="container flex h-16 items-center">
          <div className="mr-4 flex">
            <a className="mr-6 flex items-center space-x-2" href="/">
              <Landmark className="h-6 w-6 text-primary" />
              <span className="font-bold font-headline sm:inline-block">
                BudgetWise
              </span>
            </a>
          </div>
          <div className="flex flex-1 items-center justify-end space-x-2">
            <AddTransaction onTransactionAdded={handleTransactionAdded}>
              <Button>
                <PlusCircle /> Add Transaction
              </Button>
            </AddTransaction>
            <SetBudget onBudgetSet={handleBudgetSet}>
              <Button variant="outline">
                <Settings /> Set Budget
              </Button>
            </SetBudget>
             <DataImporter onDataImported={handleDataImported}>
              <Button variant="outline">
                <Upload /> Import
              </Button>
            </DataImporter>
            <AiSuggestions transactions={transactions} budgets={budgets}>
              <Button variant="outline">
                <Sparkles/> AI Suggestions
              </Button>
            </AiSuggestions>
            <ThemeToggle />
          </div>
        </div>
      </header>

      <main className="flex-1 container py-6">
        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
          <OverviewCards data={overview} />
        </div>
        <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-5">
          <div className="lg:col-span-3">
            <SpendingChart transactions={transactions} />
          </div>
          <div className="lg:col-span-2">
            <BudgetGoals transactions={transactions} budgets={budgets} />
          </div>
        </div>
        <div className="mt-6">
          <RecentTransactions transactions={transactions} />
        </div>
      </main>
    </div>
  );
}
