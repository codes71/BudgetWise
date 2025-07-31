'use client';

import { useAuth } from '@/context/auth-context';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription, CardFooter } from '@/components/ui/card';
import { Landmark, User, Camera, Menu, LogOut } from 'lucide-react';
import Link from 'next/link';
import { ThemeToggle } from '@/components/dashboard/theme-toggle';
import { signOut, updateUser } from '@/app/actions';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useToast } from '@/hooks/use-toast';
import { Sheet, SheetContent, SheetTrigger, SheetClose } from '@/components/ui/sheet';

export default function MyProfilePage() {
  const { user, loading, setUser } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    if (!loading && !user) {
      router.push('/login');
    }
  }, [user, loading, router]);

  const handleProfileUpdate = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setIsSubmitting(true);
    
    const formData = new FormData(event.currentTarget);
    const result = await updateUser(formData);

    if (result.error) {
        toast({
            variant: 'destructive',
            title: 'Update Failed',
            description: result.error,
        });
    } else if (result.success && result.user) {
        setUser(result.user);
        toast({
            title: 'Profile Updated',
            description: 'Your profile has been successfully updated.',
        });
    }
    setIsSubmitting(false);
  };
  
  if (loading || !user) {
    return <div className="flex items-center justify-center min-h-screen">Loading...</div>;
  }
  
  const handleSignOut = async () => {
    await signOut();
  };
  
  const getInitials = (name?: string | null) => {
    if (!name) return user.email?.charAt(0).toUpperCase() || '?';
    const parts = name.split(' ');
    if (parts.length > 1) {
        return parts[0][0] + parts[parts.length - 1][0];
    }
    return name.charAt(0);
  }

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
                    <Link href="/budgets" className="text-muted-foreground transition-colors hover:text-foreground">Budgets</Link>
                  </SheetClose>
                  <SheetClose asChild>
                    <Link href="/myprofile" className="text-foreground transition-colors hover:text-foreground">My Profile</Link>
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
              <Link href="/budgets" className="text-muted-foreground transition-colors hover:text-foreground">Budgets</Link>
              <Link href="/myprofile" className="text-foreground transition-colors hover:text-foreground">My Profile</Link>
            </nav>
          </div>
          <div className="flex items-center justify-end gap-2 sm:gap-4">
            <ThemeToggle />
            <Link href="/myprofile" className="flex items-center gap-2">
              <Avatar className="h-9 w-9">
                <AvatarImage src={user.profilePhotoUrl || ''} alt={user.fullName || user.email || ''} />
                <AvatarFallback>{getInitials(user.fullName)}</AvatarFallback>
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
        <div className="mx-auto max-w-4xl space-y-8">
            <div>
                <h1 className="text-3xl font-bold">My Profile</h1>
                <p className="text-muted-foreground">Manage your personal information and settings.</p>
            </div>
            <form onSubmit={handleProfileUpdate}>
                <Card>
                    <CardHeader>
                        <CardTitle>Profile Information</CardTitle>
                        <CardDescription>Update your photo and personal details here.</CardDescription>
                    </CardHeader>
                    <CardContent className="space-y-6">
                         <div className="flex items-center gap-6">
                            <Avatar className="h-24 w-24">
                                <AvatarImage src={user.profilePhotoUrl || ''} alt={user.fullName || user.email || ''} />
                                <AvatarFallback>{getInitials(user.fullName)}</AvatarFallback>
                            </Avatar>
                            <div className="grid gap-2 flex-1">
                                <Label htmlFor="profilePhotoUrl">Profile Photo URL</Label>
                                <Input id="profilePhotoUrl" name="profilePhotoUrl" defaultValue={user.profilePhotoUrl || ''} placeholder="https://example.com/photo.jpg" />
                            </div>
                        </div>

                        <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
                            <div className="grid gap-2">
                                <Label htmlFor="fullName">Full Name</Label>
                                <Input id="fullName" name="fullName" defaultValue={user.fullName || ''} placeholder="John Doe"/>
                            </div>
                            <div className="grid gap-2">
                                <Label htmlFor="email">Email</Label>
                                <Input id="email" type="email" defaultValue={user.email || ''} readOnly disabled />
                            </div>
                             <div className="grid gap-2">
                                <Label htmlFor="phoneNumber">Phone Number</Label>
                                <Input id="phoneNumber" name="phoneNumber" type="tel" defaultValue={user.phoneNumber || ''} placeholder="+1 (555) 123-4567" />
                            </div>
                        </div>
                    </CardContent>
                    <CardFooter className="border-t px-6 py-4 flex justify-between items-center">
                        <Button type="submit" disabled={isSubmitting}>
                            {isSubmitting ? 'Saving...' : 'Save Changes'}
                        </Button>
                    </CardFooter>
                </Card>
            </form>

            <Card>
                 <CardHeader>
                    <CardTitle>Account Actions</CardTitle>
                    <CardDescription>Manage your account settings.</CardDescription>
                </CardHeader>
                <CardContent>
                    <form action={signOut}>
                        <Button type="submit" variant="destructive">Sign Out</Button>
                    </form>
                </CardContent>
            </Card>
        </div>
      </main>
    </div>
  );
}
