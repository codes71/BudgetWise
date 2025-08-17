// src/app/dashboard-page.tsx
'use client';

import { useState, useMemo, useEffect } from "react";
import type { Transaction } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { PlusCircle } from "lucide-react";
import { OverviewCards } from "./overview-cards";
import { SpendingChart } from "./spending-chart";
import { BudgetGoals } from "./budget-goals";
import { RecentTransactions } from "./recent-transactions";
import { CurrencyToggle } from "./currency-toggle";
import { AddTransaction } from "./add-transaction";
import { addTransaction as addTx } from "@/app/db-actions";
import { useAuth } from "@/context/auth-context";
import { useToast } from "@/hooks/use-toast";
import { AppHeader } from "@/components/layout/app-header"; // New import

export function DashboardPage() {
  // Call all hooks unconditionally at the top of the function
  const {
    user,
    transactions,
    budgets,
    setTransactions,
    signOut,
  } = useAuth();
  
  const { toast } = useToast(); // New

  const handleTransactionAdded = async (
    transaction: Omit<Transaction, "id" | "_id" | "userId">
  ) => {
    const optimisticTransaction: Transaction = {
      _id: `optimistic-${Date.now()}`,
      ...transaction,
      userId: user!.userId, // Assume user is logged in
    };

    // Optimistically update the UI
    setTransactions([optimisticTransaction, ...transactions]);

    try {
      const newTransaction = await addTx(transaction);
      if (newTransaction) {
        // Replace the optimistic transaction with the real one from the server
        setTransactions(currentTransactions =>
          currentTransactions.map(t =>
            t._id === optimisticTransaction._id ? newTransaction : t
          )
        );
      } else {
        throw new Error("Failed to add transaction");
      }
    } catch (error) {
      // If the API call fails, revert the optimistic update
      setTransactions(currentTransactions =>
        currentTransactions.filter(t => t._id !== optimisticTransaction._id)
      );
      toast({
        variant: "destructive",
        title: "Error",
        description: "Failed to add transaction. Please try again.",
      });
    }
  };

  const overview = useMemo(() => {
    const income = transactions
      .filter((t) => t.type === "income")
      .reduce((acc, t) => acc + t.amount, 0);
    const expenses = transactions
      .filter((t) => t.type === "expense")
      .reduce((acc, t) => acc + t.amount, 0);
    const balance = income - expenses;
    return { income, expenses, balance };
  }, [transactions]);

  return (
    <div className="flex flex-col min-h-screen">
      <AppHeader
        activePath="/dashboard"
        rightHandElements={
          <>
            <AddTransaction onTransactionAdded={handleTransactionAdded}>
              <Button>
                <PlusCircle className="h-5 w-5" />
                <span className="hidden sm:inline-block sm:ml-2">
                  Add Transaction
                </span>
              </Button>
            </AddTransaction>
            <CurrencyToggle />
          </>
        }
      />

      <main className="flex-1 container py-6 px-4 md:px-6">
        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
          <OverviewCards data={overview} />
        </div>
        <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-5">
          <div className="lg:col-span-3">
            {/* The SpendingChart will receive categories from the context */}
            <SpendingChart />
          </div>
          <div className="lg:col-span-2">
            <BudgetGoals transactions={transactions} budgets={budgets} />
          </div>
        </div>
        <div className="mt-6">
          <RecentTransactions />
        </div>
      </main>
    </div>
  );
}