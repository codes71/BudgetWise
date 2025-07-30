import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { DollarSign, TrendingUp, TrendingDown, Wallet } from 'lucide-react';
import { useAuth } from '@/context/auth-context';
import { formatCurrency } from '@/lib/utils';

interface OverviewCardsProps {
  data: {
    income: number;
    expenses: number;
    balance: number;
  };
}

export function OverviewCards({ data }: OverviewCardsProps) {
  const { income, expenses, balance } = data;
  const { currency } = useAuth();
  
  const overviewItems = [
    { title: 'Total Income', value: formatCurrency(income, currency), icon: TrendingUp },
    { title: 'Total Expenses', value: formatCurrency(expenses, currency), icon: TrendingDown },
    { title: 'Balance', value: formatCurrency(balance, currency), icon: Wallet },
  ];

  return (
    <>
      {overviewItems.map((item, index) => (
        <Card key={index} className="shadow-sm hover:shadow-md transition-shadow">
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">{item.title}</CardTitle>
            <item.icon className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{item.value}</div>
          </CardContent>
        </Card>
      ))}
       <Card className="shadow-sm hover:shadow-md transition-shadow">
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Savings Rate</CardTitle>
             <DollarSign className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{income > 0 ? `${((balance / income) * 100).toFixed(1)}%` : 'N/A'}</div>
          </CardContent>
        </Card>
    </>
  );
}
