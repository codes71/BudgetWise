'use client';

import { useState, useMemo, useEffect } from 'react';
import { Sparkles, PlusCircle, Settings, Landmark, User } from 'lucide-react';
import type { Transaction, Budget } from '@/lib/types';
import { Button } from '@/components/ui/button';
import { OverviewCards } from './overview-cards';
import { SpendingChart } from './spending-chart';
import { BudgetGoals } from './budget-goals';
import { RecentTransactions } from './recent-transactions';
import { AiSuggestions } from './ai-suggestions';
import { ThemeToggle } from './theme-toggle';
import { CurrencyToggle } from './currency-toggle';
import { AddTransaction } from './add-transaction';
import { getBudgets, getTransactions, addTransaction as addTx } from '@/app/db-actions';
import Link from 'next/link';
import { useAuth } from '@/context/auth-context';
import { useRouter } from 'next/navigation';

export function DashboardPage() {
  const { user, loading, signOut } = useAuth();
  const router = useRouter();
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [budgets, setBudgets] = useState<Budget[]>([]);

  useEffect(() => {
    if (!loading && !user) {
      router.push('/login');
    }
  }, [user, loading, router]);

  useEffect(() => {
    if (user) {
      async function fetchData() {
        const [transactionsData, budgetsData] = await Promise.all([
          getTransactions(),
          getBudgets(),
        ]);
        setTransactions(transactionsData);
        setBudgets(budgetsData);
      }
      fetchData();
    }
  }, [user]);

  const handleTransactionAdded = async (transaction: Omit<Transaction, 'id' | '_id' | 'userId' >) => {
    const newTransaction = await addTx(transaction);
    if(newTransaction) {
      setTransactions(prev => [newTransaction, ...prev]);
      const newBudgets = await getBudgets();
      setBudgets(newBudgets);
    }
  };
  
  const overview = useMemo(() => {
    const income = transactions.filter(t => t.type === 'income').reduce((acc, t) => acc + t.amount, 0);
    const expenses = transactions.filter(t => t.type === 'expense').reduce((acc, t) => acc + t.amount, 0);
    const balance = income - expenses;
    return { income, expenses, balance };
  }, [transactions]);
  
  if (loading || !user) {
    return <div className="flex items-center justify-center min-h-screen">Loading...</div>;
  }
  
  return (
    <div className="flex flex-col min-h-screen">
      <header className="sticky top-0 z-40 w-full border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="container flex h-16 items-center px-4 md:px-6">
          <div className="mr-4 flex">
            <Link className="mr-6 flex items-center space-x-2" href="/">
              <Landmark className="h-6 w-6 text-primary" />
              <span className="font-bold font-headline sm:inline-block">
                BudgetWise
              </span>
            </Link>
          </div>
           <nav className="hidden items-center space-x-6 text-sm font-medium md:flex">
            <Link href="/" className="text-foreground transition-colors hover:text-foreground">Dashboard</Link>
            <Link href="/budgets" className="text-muted-foreground transition-colors hover:text-foreground">Budgets</Link>
            <Link href="/myprofile" className="text-muted-foreground transition-colors hover:text-foreground">My Profile</Link>
          </nav>
          <div className="flex flex-1 items-center justify-end space-x-2">
            <AddTransaction onTransactionAdded={handleTransactionAdded}>
              <Button>
                <PlusCircle /> Add Transaction
              </Button>
            </AddTransaction>
            <AiSuggestions transactions={transactions} budgets={budgets}>
              <Button variant="outline">
                <Sparkles/> AI Suggestions
              </Button>
            </AiSuggestions>
            <CurrencyToggle />
            <ThemeToggle />
            <Button asChild variant="outline" size="icon">
              <Link href="/myprofile">
                <User />
              </Link>
            </Button>
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
