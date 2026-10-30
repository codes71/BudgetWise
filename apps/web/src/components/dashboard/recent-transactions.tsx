'use client';

import { useState, useEffect } from 'react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription, CardFooter } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { CategoryIcon } from '@/components/category-icon';
import type { Transaction } from '@/lib/types';
import { useAuth } from '@/context/auth-context';
import { formatCurrency } from '@/lib/utils';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger } from '@/components/ui/alert-dialog';
import { Trash2 } from 'lucide-react';
import { deleteTransaction } from '@/app/db-actions';
import { useToast } from '@/hooks/use-toast';

interface RecentTransactionsProps {
  transactions: Transaction[];
}

const INITIAL_VISIBLE_COUNT = 10;
const LOAD_MORE_COUNT = 10;

export function RecentTransactions() {
  const { user, currency, transactions, setTransactions } = useAuth();
  const { toast } = useToast();
  const [visibleCount, setVisibleCount] = useState(INITIAL_VISIBLE_COUNT);
  const [transactionToDelete, setTransactionToDelete] = useState<Transaction | null>(null);

  // useEffect(() => {
  //   setTransactions(initialTransactions);
  // }, [initialTransactions]);

  const sortedTransactions = [...transactions].sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime());

  const visibleTransactions = sortedTransactions.slice(0, visibleCount);

  const handleLoadMore = () => {
    setVisibleCount(prevCount => prevCount + LOAD_MORE_COUNT);
  };

  const handleDeleteClick = (transaction: Transaction) => {
    setTransactionToDelete(transaction);
  };

  const handleCancelDelete = () => {
    setTransactionToDelete(null);
  };

  const handleConfirmDelete = async () => {
    if (user?.isGuest) {
      toast({
        title: 'Feature Locked',
        description: 'Sign up or log in to delete transactions.',
      });
      setTransactionToDelete(null);
      return;
    }

    if (transactionToDelete) {
      const result = await deleteTransaction(transactionToDelete._id);
      if (result.success) {
        setTransactions(transactions.filter(t => t._id !== transactionToDelete._id));
        toast({
          title: 'Transaction deleted',
          description: 'The transaction has been successfully deleted.',
        });
      } else {
        toast({
          title: 'Error deleting transaction',
          description: result.error,
          variant: 'destructive',
        });
      }
      setTransactionToDelete(null);
    }
  };

  return (
    <>
      <Card className="shadow-sm">
        <CardHeader>
          <CardTitle>Recent Transactions</CardTitle>
          <CardDescription>Your most recent transactions.</CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Description</TableHead>
                <TableHead>Category</TableHead>
                <TableHead>Date</TableHead>
                <TableHead className="text-right">Amount</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {visibleTransactions.map(transaction => (
                <TableRow key={transaction._id}>
                  <TableCell className="font-medium">{transaction.description}</TableCell>
                  <TableCell>
                    <Badge variant="outline" className="flex items-center gap-2 w-fit">
                      <CategoryIcon category={transaction.category} className="h-3 w-3" />
                      {transaction.category}
                    </Badge>
                  </TableCell>
                  <TableCell>{new Date(transaction.date).toLocaleDateString()}</TableCell>
                  <TableCell className={`text-right font-semibold ${transaction.type === 'income' ? 'text-primary' : ''}`}>
                    {transaction.type === 'expense' ? '-' : '+'}
                    {formatCurrency(transaction.amount, currency)}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button variant="ghost" size="icon" onClick={() => handleDeleteClick(transaction)}>
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
         {visibleCount < sortedTransactions.length && (
          <CardFooter className="flex justify-center">
            <Button onClick={handleLoadMore} variant="outline">
              Load More
            </Button>
          </CardFooter>
        )}
      </Card>
      {transactionToDelete && (
        <AlertDialog open onOpenChange={(open) => !open && handleCancelDelete()}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Are you sure?</AlertDialogTitle>
              <AlertDialogDescription>
                This action cannot be undone. This will permanently delete this transaction.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel onClick={handleCancelDelete}>Cancel</AlertDialogCancel>
              <AlertDialogAction onClick={handleConfirmDelete}>Delete</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      )}
    </>
  );
}
