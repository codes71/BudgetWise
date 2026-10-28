// src/app/guest/page.tsx
'use client';

import { DashboardPage } from '@/components/dashboard/dashboard-page';
import { guestBudgets, guestCategories, guestTransactions } from '@/lib/guest-data';
import { useToast } from '@/hooks/use-toast';

// This is a functional, read-only "showroom" version of the dashboard for guest users.
// It uses the exact same UI components as the real dashboard, but is fed with mock data.
export default function GuestDashboard() {
  const { toast } = useToast();

  // Create dummy action handlers that show a toast message.
  const createDummyAction = (featureName: string) => () => {
    toast({
      title: 'Feature Locked',
      description: `Sign up or log in to ${featureName}.`,
    });
  };

  return (
    <DashboardPage
      // Pass mock data as props
      transactions={guestTransactions}
      budgets={guestBudgets}
      categories={guestCategories}
      // Pass dummy functions for actions
      onTransactionAdded={createDummyAction('add transactions')}
      onTransactionDeleted={createDummyAction('delete transactions')}
      onBudgetSet={createDummyAction('set budgets')}
      onBudgetUpdated={createDummyAction('update budgets')}
    />
  );
}
