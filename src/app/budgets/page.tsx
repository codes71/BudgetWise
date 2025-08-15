'use client';

import { useState, useEffect } from 'react';
import type { Budget } from '../../lib/types';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { setBudget, getBudgets } from '../db-actions'; // signOut removed
import { useToast } from '../../hooks/use-toast';
import { useAuth } from '../../context/auth-context';
import { useRouter } from 'next/navigation';
import { AppHeader } from '../../components/layout/app-header'; // New import




export default function BudgetsPage() {
  const { user, loading, budgets, setBudgets} = useAuth();
  const router = useRouter();
  const { categories } = useAuth();
  const [newCategory, setNewCategory] = useState(categories.length > 0 ? categories[0] : '');
  const [newLimit, setNewLimit] = useState('');
  const { toast } = useToast();
  
  useEffect(() => {
    if (!loading && !user) {
      router.push('/login');
    }
  }, [user, loading, router]);

  // Removed the local useEffect that fetches budgets, as AuthContext now manages this.

  const handleUpdateBudget = async (category: string, limit: string | number) => {
    const numericLimit = typeof limit === 'string' ? parseFloat(limit) : limit;
    if (isNaN(numericLimit) || numericLimit <= 0) {
      toast({
        variant: 'destructive',
        title: 'Invalid Input',
        description: 'Please enter a valid positive number for the budget limit.',
      });
      return;
    }
    
    await setBudget({ category, limit: numericLimit });
    const updatedBudgets = await getBudgets(); // Fetch updated budgets
    setBudgets(updatedBudgets); // Update global state
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
    
    await setBudget({ category: newCategory, limit: numericLimit });
    const updatedBudgets = await getBudgets(); // Fetch updated budgets
    setBudgets(updatedBudgets); // Update global state
    setNewLimit('');
     toast({
      title: 'Budget Set',
      description: `Budget for ${newCategory} has been set.`,
    });
  };

  const handleLimitChange = (category: string, newLimit: string) => {
    setBudgets(budgets.map(b => b.category === category ? { ...b, limit: parseFloat(newLimit) || 0 } : b));
  };
  
  if (loading || !user) {
    return <div className="flex items-center justify-center min-h-screen">Loading...</div>;
  }
  
  const handleSignOut = async () => {
    toast({
      title: 'Signing Out',
      description: 'See you soon!',
    });
    await signOut();
  };

  return (
    <div className="flex flex-col min-h-screen">
      <AppHeader activePath="/budgets" />

      <main className="flex-1 container py-6 px-4 md:px-6">
        <div className="space-y-6">
            <div className="flex items-center justify-between">
                <div>
                    <h1 className="text-3xl font-bold">Manage Budgets</h1>
                    <p className="text-muted-foreground">Set and adjust your monthly spending limits.</p>
                </div>
            </div>

            <Card>
                <CardHeader>
                    <CardTitle>Set Budget</CardTitle>
                    <CardDescription>Create or update a spending limit for a category.</CardDescription>
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
                                {categories.map(category => (
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
                        <Button type="submit">Set Budget</Button>
                    </form>
                </CardContent>
            </Card>

            <Card>
                <CardHeader>
                    <CardTitle>Existing Budgets</CardTitle>
                    <CardDescription>Your current budget limits.</CardDescription>
                </CardHeader>
                <CardContent className="space-y-4">
                    {budgets.map((budget) => (
                    <div key={budget._id} className="flex flex-col sm:flex-row items-center justify-between gap-4 p-4 border rounded-lg">
                        <Label className="font-semibold text-lg flex-1">{budget.category}</Label>
                        <div className="flex items-center gap-2 w-full sm:w-auto">
                            <Input
                                type="number"
                                value={budget.limit}
                                onChange={(e) => handleLimitChange(budget.category, e.target.value)}
                                className="w-full sm:w-32"
                                placeholder="e.g., 500"
                            />
                            <Button onClick={() => handleUpdateBudget(budget.category, budget.limit)}>Update</Button>
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