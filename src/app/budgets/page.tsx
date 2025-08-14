'use client';

import { useState, useEffect } from 'react';
import { Landmark, User, Menu, LogOut } from 'lucide-react';
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
import { getBudgets, setBudget, signOut } from '../db-actions';
import { useToast } from '../../hooks/use-toast';
import Link from 'next/link';
import { ThemeToggle } from '../../components/dashboard/theme-toggle';
import { useAuth } from '../../context/auth-context';
import { useRouter } from 'next/navigation';
import { Sheet, SheetContent, SheetTrigger, SheetClose } from '../../components/ui/sheet';
import { Avatar, AvatarImage, AvatarFallback } from '../../components/ui/avatar';




export default function BudgetsPage() {
  const { user, loading } = useAuth();
  const router = useRouter();
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const { categories } = useAuth();
  const [newCategory, setNewCategory] = useState(categories.length > 0 ? categories[0] : '');
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
    
    await setBudget({ category: newCategory, limit: numericLimit });
    const newBudgets = await getBudgets();
    setBudgets(newBudgets);
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
                    <Link href="/" className="text-muted-foreground transition-colors hover:text-foreground">Dashboard</Link>
                  </SheetClose>
                  <SheetClose asChild>
                    <Link href="/budgets" className="text-foreground transition-colors hover:text-foreground">Budgets</Link>
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
              <Link href="/" className="text-muted-foreground transition-colors hover:text-foreground">Dashboard</Link>
              <Link href="/budgets" className="text-foreground transition-colors hover:text-foreground">Budgets</Link>
              <Link href="/myprofile" className="text-muted-foreground transition-colors hover:text-foreground">My Profile</Link>
            </nav>
          </div>
          <div className="flex items-center justify-end gap-2 sm:gap-4">
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