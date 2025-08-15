'use client';

import Link from 'next/link';
import { Landmark, Menu, LogOut } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Sheet, SheetContent, SheetTrigger, SheetClose } from '@/components/ui/sheet';
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar';
import { ThemeToggle } from '@/components/dashboard/theme-toggle';
import { useAuth } from '@/context/auth-context';
import { useToast } from '@/hooks/use-toast';
import { useRouter } from 'next/navigation'; // Import useRouter

interface AppHeaderProps {
  activePath: string;
  rightHandElements?: React.ReactNode; // Optional prop for varying elements
}

export function AppHeader({ activePath, rightHandElements }: AppHeaderProps) {
  const { user, signOut } = useAuth();
  const { toast } = useToast();
  const router = useRouter(); // Initialize useRouter

  const handleSignOut = async () => {
    toast({
      title: 'Signed Out',
      description: 'See you soon!',
    });
    await signOut();
  };

  // Helper function to determine active link class
  const getLinkClassName = (path: string) => {
    return activePath === path
      ? 'text-foreground transition-colors hover:text-foreground'
      : 'text-muted-foreground transition-colors hover:text-foreground';
  };

  return (
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
                    <img src="/logo.png" alt="BudgetWise Logo" className="h-10 w-10" />
                    <span className="sr-only">BudgetWise</span>
                  </Link>
                </SheetClose>
                <SheetClose asChild>
                  <Link href="/" className={getLinkClassName('/')}>Dashboard</Link>
                </SheetClose>
                <SheetClose asChild>
                  <Link href="/budgets" className={getLinkClassName('/budgets')}>Budgets</Link>
                </SheetClose>
                <SheetClose asChild>
                  <Link href="/myprofile" className={getLinkClassName('/myprofile')}>My Profile</Link>
                </SheetClose>
              </nav>
            </SheetContent>
          </Sheet>
          <Link className="mr-6 flex items-center space-x-2" href="/">
            <img src="/logo.png" alt="BudgetWise Logo" className="h-10 w-10" />
            <span className="font-bold sm:inline-block">BudgetWise</span>
          </Link>
          <nav className="hidden items-center space-x-6 text-sm font-medium md:flex">
            <Link href="/" className={getLinkClassName('/')}>Dashboard</Link>
            <Link href="/budgets" className={getLinkClassName('/budgets')}>Budgets</Link>
            <Link href="/myprofile" className={getLinkClassName('/myprofile')}>My Profile</Link>
          </nav>
        </div>
        <div className="flex items-center justify-end gap-2 sm:gap-4">
          {rightHandElements} {/* Render varying elements here */}
          <ThemeToggle />
          <Link href="/myprofile" className="flex items-center gap-2">
            <Avatar className="h-9 w-9">
              <AvatarImage src={user?.profilePhotoUrl || ''} alt={user?.fullName || user?.email || ''} />
              <AvatarFallback>{user?.email?.charAt(0).toUpperCase()}</AvatarFallback>
            </Avatar>
            {user?.fullName && <span className="hidden lg:inline-block font-semibold">{user.fullName}</span>}
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
  );
}
