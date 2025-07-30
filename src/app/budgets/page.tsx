'use client';

import { useState, useEffect } from 'react';
import { Landmark } from 'lucide-react';
import type { Budget } from '@/lib/types';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { getBudgets, setBudget } from '@/app/db-actions';
import { useToast } from '@/hooks/use-toast';
import Link from 'next/link';
import { ThemeToggle } from '@/components/dashboard/theme-toggle';
import { useAuth } from '@/context/auth-context';
import { useRouter } from 'next/navigation';

const categories = ['Groceries', 'Utilities', 'Entertainment', 'Transport', 'Housing', 'Health', 'Other'];

export default function BudgetsPage() {
  const { user, loading } = useAuth();
  const router = useRouter();
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [newCategory, setNewCategory] = useState(categories[0]);
  const [newLimit, setNewLimit] = useState('');
  const { toast } = useToast();
  
  useEffect(() => {
    if (!loading && !user) {
      router.push('/login');
    }
  }, [user, loading, router]);


  useEffect(() => {
    if (user) {
      async function fetchData() {
        const budgetsData = await getBudgets();
        setBudgets(budgetsData);
      }
      fetchData();
    }
  }, [user]);

  const handleUpdateBudget = async (category: string, limit: string) => {
    const numericLimit = parseFloat(limit);
    if (isNaN(numericLimit) || numericLimit <= 0) {
      toast({
        variant: 'destructive',
        title: 'Invalid Input',
        description: 'Please enter a valid positive number for the budget limit.',
      });
      return;
    }
    
    await setBudget({ category, limit: numericLimit });
    const newBudgets = await getBudgets();
    setBudgets(newBudgets);
    toast({
      title: 'Budget Updated',
      description: `Budget for ${category} has been updated.`,
    });
  };

  const handleAddBudget = async (e: React.FormEvent) => {
    e.preventDefault();
    const numericLimit = parseFloat(newLimit);
     if (isNaN(numericLimit) || numericLimit <= 0) {
      toast({
        variant: 'destructive',
        title: 'Invalid Input',
        description: 'Please enter a valid positive number for the budget limit.',
      });
      return;
    }
    
    if (budgets.some(b => b.category === newCategory)) {
        toast({
            variant: 'destructive',
            title: 'Category Exists',
            description: `A budget for ${newCategory} already exists. You can edit it below.`,
        });
        return;
    }

    await setBudget({ category: newCategory, limit: numericLimit });
    const newBudgets = await getBudgets();
    setBudgets(newBudgets);
    setNewLimit('');
     toast({
      title: 'Budget Added',
      description: `Budget for ${newCategory} has been added.`,
    });
  };

  const handleLimitChange = (category: string, newLimit: string) => {
    setBudgets(budgets.map(b => b.category === category ? { ...b, limit: parseFloat(newLimit) || 0 } : b));
  };
  
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
           <nav className="flex items-center space-x-6 text-sm font-medium">
            <Link href="/" className="text-muted-foreground transition-colors hover:text-foreground">Dashboard</Link>
            <Link href="/budgets" className="text-foreground transition-colors hover:text-foreground">Budgets</Link>
            <Link href="/myprofile" className="text-muted-foreground transition-colors hover:text-foreground">My Profile</Link>
          </nav>
          <div className="flex flex-1 items-center justify-end space-x-2">
            <ThemeToggle />
          </div>
        </div>
      </header>

      <main className="flex-1 container py-6">
        <div className="space-y-6">
            <div className="flex items-center justify-between">
                <div>
                    <h1 className="text-3xl font-bold">Manage Budgets</h1>
                    <p className="text-muted-foreground">Set and adjust your monthly spending limits.</p>
                </div>
            </div>

            <Card>
                <CardHeader>
                    <CardTitle>Add New Budget</CardTitle>
                    <CardDescription>Create a new spending limit for a category.</CardDescription>
                </CardHeader>
                <CardContent>
                    <form onSubmit={handleAddBudget} className="flex flex-col sm:flex-row items-end gap-4">
                        <div className="grid gap-2 flex-1">
                            <Label htmlFor="category">Category</Label>
                            <Select onValueChange={setNewCategory} defaultValue={newCategory}>
                                <SelectTrigger id="category">
                                    <SelectValue placeholder="Select a category" />
                                </SelectTrigger>
                                <SelectContent>
                                {categories.filter(c => !budgets.some(b => b.category === c)).map(category => (
                                    <SelectItem key={category} value={category}>
                                    {category}
                                    </SelectItem>
                                ))}
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="grid gap-2 flex-1">
                            <Label htmlFor="limit">Limit</Label>
                            <Input id="limit" type="number" placeholder="e.g., 500" value={newLimit} onChange={(e) => setNewLimit(e.target.value)} />
                        </div>
                        <Button type="submit">Add Budget</Button>
                    </form>
                </CardContent>
            </Card>

            <Card>
                <CardHeader>
                    <CardTitle>Existing Budgets</CardTitle>
                    <CardDescription>Update your current budget limits here.</CardDescription>
                </CardHeader>
                <CardContent className="space-y-4">
                    {budgets.map((budget) => (
                    <div key={budget._id} className="flex flex-col sm:flex-row items-center justify-between gap-4 p-4 border rounded-lg">
                        <Label className="font-semibold text-lg">{budget.category}</Label>
                        <div className="flex items-center gap-2">
                            <Input 
                                type="number"
                                value={budget.limit}
                                onChange={(e) => handleLimitChange(budget.category, e.target.value)}
                                onBlur={(e) => handleUpdateBudget(budget.category, e.target.value)}
                                className="w-32"
                                placeholder="Limit"
                            />
                             <Button size="sm" variant="outline" onClick={() => handleUpdateBudget(budget.category, String(budget.limit))}>
                                Update
                             </Button>
                        </div>
                    </div>
                    ))}
                    {budgets.length === 0 && (
                        <p className="text-center text-muted-foreground py-8">You haven't set any budgets yet.</p>
                    )}
                </CardContent>
            </Card>
        </div>
      </main>
    </div>
  );
}
