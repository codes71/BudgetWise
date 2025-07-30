'use client';

import { useAuth } from '@/context/auth-context';
import { useRouter } from 'next/navigation';
import { useEffect } from 'react';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Landmark } from 'lucide-react';
import Link from 'next/link';
import { ThemeToggle } from '@/components/dashboard/theme-toggle';
import { signOut } from '@/app/actions';

export default function MyProfilePage() {
  const { user, loading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!loading && !user) {
      router.push('/login');
    }
  }, [user, loading, router]);

  if (loading || !user) {
    return <div className="flex items-center justify-center min-h-screen">Loading...</div>;
  }
  
  const handleSignOut = async () => {
    await signOut();
  };

  return (
     <div className="flex flex-col min-h-screen">
      <header className="sticky top-0 z-40 w-full border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="container flex h-16 items-center">
          <div className="mr-4 flex">
            <Link className="mr-6 flex items-center space-x-2" href="/">
              <Landmark className="h-6 w-6 text-primary" />
              <span className="font-bold font-headline sm:inline-block">
                BudgetWise
              </span>
            </Link>
          </div>
           <nav className="hidden items-center space-x-6 text-sm font-medium md:flex">
            <Link href="/" className="text-muted-foreground transition-colors hover:text-foreground">Dashboard</Link>
            <Link href="/budgets" className="text-muted-foreground transition-colors hover:text-foreground">Budgets</Link>
             <Link href="/myprofile" className="text-foreground transition-colors hover:text-foreground">My Profile</Link>
          </nav>
          <div className="flex flex-1 items-center justify-end space-x-2">
            <ThemeToggle />
          </div>
        </div>
      </header>

      <main className="flex-1 container py-6">
        <Card className="max-w-md mx-auto">
          <CardHeader>
            <CardTitle className="text-center text-2xl">My Profile</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col items-center space-y-4">
            <Avatar className="h-24 w-24">
              <AvatarFallback>{user.email?.charAt(0).toUpperCase()}</AvatarFallback>
            </Avatar>
            <div className="text-center">
              <p className="text-muted-foreground">{user.email}</p>
            </div>
            <form action={signOut}>
                <Button type="submit" variant="destructive">Sign Out</Button>
            </form>
          </CardContent>
        </Card>
      </main>
    </div>
  );
}
