'use client';

import { useAuth } from '@/context/auth-context';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription, CardFooter } from '@/components/ui/card';
import { updateUser, getUserDetails } from '@/app/actions'; // signOut removed
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useToast } from '@/hooks/use-toast';
import type { UserPayload } from '@/lib/auth';
import { AppHeader } from '@/components/layout/app-header'; // New import

export default function MyProfilePage() {
  const { user, loading, setUser } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [fullUser, setFullUser] = useState<UserPayload | null>(null);
  const [loadingFullUser, setLoadingFullUser] = useState(true);

  useEffect(() => {
    if (!loading && !user) {
      router.push('/login');
    } else if (user && !fullUser) {
      async function fetchFullUser() {
        const details = await getUserDetails();
        if (details) {
          setFullUser(details);
        }
        setLoadingFullUser(false);
      }
      fetchFullUser();
    }
  }, [user, loading, router, fullUser]);

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
        setFullUser(result.user);
        toast({
            title: 'Profile Updated',
            description: 'Your profile has been successfully updated.',
        });
    }
    setIsSubmitting(false);
  };
  
  if (loading || !user || loadingFullUser) {
    return <div className="flex items-center justify-center min-h-screen">Loading...</div>;
  }
  
  const getInitials = (name?: string | null) => {
    if (!name) return fullUser?.email?.charAt(0).toUpperCase() || '?';
    const parts = name.split(' ');
    if (parts.length > 1) {
        return parts[0][0] + parts[parts.length - 1][0];
    }
    return name.charAt(0);
  }

  return (
     <div className="flex flex-col min-h-screen">
      <AppHeader activePath="/myprofile" />

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
                                        <AvatarImage src={fullUser.profilePhotoUrl || ''} alt={fullUser.fullName || fullUser.email || ''} />
                                        <AvatarFallback>{getInitials(fullUser.fullName)}</AvatarFallback>
                                    </Avatar>
                                    <div className="grid gap-2 flex-1">
                                        <Label htmlFor="profilePhotoUrl">Profile Photo URL</Label>
                                        <Input id="profilePhotoUrl" name="profilePhotoUrl" defaultValue={fullUser.profilePhotoUrl || ''} placeholder="https://example.com/photo.jpg" />
                                    </div>
                                </div>

                                <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
                                    <div className="grid gap-2">
                                        <Label htmlFor="fullName">Full Name</Label>
                                        <Input id="fullName" name="fullName" defaultValue={fullUser.fullName || ''} placeholder="John Doe"/>
                                    </div>
                                    <div className="grid gap-2">
                                        <Label htmlFor="email">Email</Label>
                                        <Input id="email" type="email" defaultValue={fullUser.email || ''} readOnly disabled />
                                    </div>
                                    <div className="grid gap-2">
                                        <Label htmlFor="phoneNumber">Phone Number</Label>
                                        <Input id="phoneNumber" name="phoneNumber" type="tel" defaultValue={fullUser.phoneNumber || ''} placeholder="+1 (555) 123-4567" />
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
                            <form onSubmit={async (e) => {
                                e.preventDefault();
                                toast({
                                    title: 'Signing Out',
                                    description: 'See you soon!',
                                });
                                await signOut();
                            }}>
                                <Button type="submit" variant="destructive">Sign Out</Button>
                            </form>
                        </CardContent>
                    </Card>
                </div>
            </main>
        </div>
    );
}
