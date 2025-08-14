'use client';

import { Bar, BarChart, ResponsiveContainer, XAxis, YAxis, Tooltip, Legend } from 'recharts';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import type { Transaction } from '@/lib/types';
import { useAuth } from '@/context/auth-context';
import { formatCurrency } from '@/lib/utils';
import { getCategories } from '@/app/db-actions'; 



export function SpendingChart({ transactions }: SpendingChartProps) {
  const { currency, categories } = useAuth(); // Get categories from context
  const spendingByCategory = transactions
    .filter(t => t.type === 'expense')
    .reduce((acc, t) => {
      acc[t.category] = (acc[t.category] || 0) + t.amount;
      return acc;
    }, {} as Record<string, number>);

  // Generate chartData to include all categories, even if spending is 0
  const chartData = categories.map(categoryName => ({
    name: categoryName,
    spending: spendingByCategory[categoryName] || 0, // Use 0 if no spending
  }));
  
  return (
    <Card className="h-full shadow-sm">
      <CardHeader>
        <CardTitle>Spending by Category</CardTitle>
        <CardDescription>A visual breakdown of your expenses.</CardDescription>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={chartData} margin={{ top: 5, right: 20, left: -10, bottom: 5 }}>
            <XAxis dataKey="name" stroke="#888888" fontSize={12} tickLine={false} axisLine={false} />
            <YAxis stroke="#888888" fontSize={12} tickLine={false} axisLine={false} tickFormatter={(value: number) => formatCurrency(value, currency, { notation: 'compact' })} />
            <Tooltip
              cursor={{ fill: 'hsl(var(--muted))' }}
              contentStyle={{
                background: 'hsl(var(--background))',
                border: '1px solid hsl(var(--border))',
                borderRadius: 'var(--radius)',
              }}
              formatter={(value: number) => formatCurrency(value, currency)}
            />
            <Legend iconType="circle" />
            <Bar dataKey="spending" fill="hsl(var(--primary))" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  );
}