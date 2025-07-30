import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';
import { CategoryIcon } from '@/components/category-icon';
import type { Transaction } from '@/lib/types';
import { useAuth } from '@/context/auth-context';
import { formatCurrency } from '@/lib/utils';

interface RecentTransactionsProps {
  transactions: Transaction[];
}

export function RecentTransactions({ transactions }: RecentTransactionsProps) {
  const { currency } = useAuth();
  
  const sortedTransactions = [...transactions].sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime()).slice(0, 10);
  
  return (
    <Card className="shadow-sm">
      <CardHeader>
        <CardTitle>Recent Transactions</CardTitle>
        <CardDescription>Your last 10 transactions.</CardDescription>
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
            {sortedTransactions.map(transaction => (
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
    </Card>
  );
}
