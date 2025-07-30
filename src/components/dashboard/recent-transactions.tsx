'use client';

import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription, CardFooter } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { CategoryIcon } from '@/components/category-icon';
import type { Transaction } from '@/lib/types';
import { useAuth } from '@/context/auth-context';
import { formatCurrency } from '@/lib/utils';

interface RecentTransactionsProps {
  transactions: Transaction[];
}

const INITIAL_VISIBLE_COUNT = 10;
const LOAD_MORE_COUNT = 10;

export function RecentTransactions({ transactions }: RecentTransactionsProps) {
  const { currency } = useAuth();
  const [visibleCount, setVisibleCount] = useState(INITIAL_VISIBLE_COUNT);
  
  const sortedTransactions = [...transactions].sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime());

  const visibleTransactions = sortedTransactions.slice(0, visibleCount);
  
  const handleLoadMore = () => {
    setVisibleCount(prevCount => prevCount + LOAD_MORE_COUNT);
  };

  return (
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
  );
}
