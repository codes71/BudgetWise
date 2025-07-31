'use client';

import { useState, useMemo, useEffect } from 'react';
import { PlusCircle, Landmark, Menu, LogOut } from 'lucide-react';
import type { Transaction, Budget } from '@/lib/types';
import { Button } from '@/components/ui/button';
import { OverviewCards } from './overview-cards';
import { SpendingChart } from './spending-chart';
import { BudgetGoals } from './budget-goals';
import { RecentTransactions } from './recent-transactions';
import { ThemeToggle } from './theme-toggle';
import { CurrencyToggle } from './currency-toggle';
import { AddTransaction } from './add-transaction';
import { getBudgets, getTransactions, addTransaction as addTx, signOut } from '@/app/db-actions';
import Link from 'next/link';
import { useAuth } from '@/context/auth-context';
import { useRouter } from 'next/navigation';
import { Sheet, SheetContent, SheetTrigger, SheetClose } from '@/components/ui/sheet';
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar';

export function DashboardPage() {
  const { user, loading, setBudgets: setGlobalBudgets, setTransactions: setGlobalTransactions } = useAuth();
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
        setGlobalTransactions(transactionsData);
        setGlobalBudgets(budgetsData);
      }
      fetchData();
    }
  }, [user, setGlobalBudgets, setGlobalTransactions]);

  const handleTransactionAdded = async (transaction: Omit<Transaction, 'id' | '_id' | 'userId' >) => {
    const newTransaction = await addTx(transaction);
    if(newTransaction) {
      const updatedTransactions = [newTransaction, ...transactions];
      setTransactions(updatedTransactions);
      setGlobalTransactions(updatedTransactions);
      const newBudgets = await getBudgets();
      setBudgets(newBudgets);
      setGlobalBudgets(newBudgets);
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
  
  const handleSignOut = async () => {
    await signOut();
  };

  return (
    <div className="flex flex-col min-h-screen">
       <header className="sticky top-0 z-40 w-full border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="container flex h-16 items-center px-4 md:px-6">
          <div className="mr-auto flex items-center">
            <Sheet>
              <SheetTrigger asChild>
                <Button variant="outline" size="icon" className="md:hidden mr-4">
                  <Menu className="h-5 w-5" />
                  <span className="sr-only">Open Menu</span>
                </Button>
              </SheetTrigger>
              <SheetContent side="left">
                <nav className="grid gap-6 text-lg font-medium mt-6">
                   <SheetClose asChild>
                    <Link href="/" className="flex items-center gap-2 text-lg font-semibold">
                      <Landmark className="h-6 w-6 text-primary" />
                      <span className="sr-only">BudgetWise</span>
                    </Link>
                  </SheetClose>
                  <SheetClose asChild>
                    <Link href="/" className="text-foreground transition-colors hover:text-foreground">Dashboard</Link>
                  </SheetClose>
                  <SheetClose asChild>
                    <Link href="/budgets" className="text-muted-foreground transition-colors hover:text-foreground">Budgets</Link>
                   </SheetClose>
                   <SheetClose asChild>
                    <Link href="/myprofile" className="text-muted-foreground transition-colors hover:text-foreground">My Profile</Link>
                  </SheetClose>
                </nav>
              </SheetContent>
            </Sheet>
            <Link className="mr-6 flex items-center space-x-2" href="/">
              <Landmark className="h-6 w-6 text-primary" />
              <span className="font-bold sm:inline-block">
                BudgetWise
              </span>
            </Link>
            <nav className="hidden items-center space-x-6 text-sm font-medium md:flex">
              <Link href="/" className="text-foreground transition-colors hover:text-foreground">Dashboard</Link>
              <Link href="/budgets" className="text-muted-foreground transition-colors hover:text-foreground">Budgets</Link>
              <Link href="/myprofile" className="text-muted-foreground transition-colors hover:text-foreground">My Profile</Link>
            </nav>
          </div>
          
          <div className="flex items-center justify-end gap-2 sm:gap-4">
            <AddTransaction onTransactionAdded={handleTransactionAdded}>
              <Button>
                <PlusCircle className="h-5 w-5" />
                <span className="hidden sm:inline-block sm:ml-2">Add Transaction</span>
              </Button>
            </AddTransaction>
            <CurrencyToggle />
            <ThemeToggle />
            <Link href="/myprofile" className="flex items-center gap-2">
              <Avatar className="h-9 w-9">
                <AvatarImage src={user.profilePhotoUrl || ''} alt={user.fullName || user.email || ''} />
                <AvatarFallback>{user.email?.charAt(0).toUpperCase()}</AvatarFallback>
              </Avatar>
              {user.fullName && <span className="hidden lg:inline-block font-semibold">{user.fullName}</span>}
            </Link>
            <form action={handleSignOut}>
                <Button variant="ghost" size="icon" type="submit" aria-label="Sign Out">
                    <LogOut className="h-5 w-5 text-muted-foreground" />
                    <span className="sr-only">Sign Out</span>
                </Button>
            </form>
          </div>
        </div>
      </header>

      <main className="flex-1 container py-6 px-4 md:px-6">
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
