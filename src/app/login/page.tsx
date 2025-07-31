'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { Landmark } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useAuth } from '@/context/auth-context';
import { useToast } from '@/hooks/use-toast';
import { signIn } from '@/app/actions';


export default function LoginPage() {
  const { user, loading } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    if (!loading && user) {
      router.push('/');
    }
  }, [user, loading, router]);
  
  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setIsSubmitting(true);
    
    const formData = new FormData(event.currentTarget);
    try {
      const result = await signIn(formData);
      if (result?.error) {
        toast({
          variant: 'destructive',
          title: 'Login Failed',
          description: result.error,
        });
      }
    } catch (error) {
      // Redirects are handled by Next.js, so we don't need to do anything here.
    } finally {
      setIsSubmitting(false);
    }
  };


  if (loading) {
    return <div className="flex items-center justify-center min-h-screen">Loading...</div>;
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-background p-4">
      <Card className="w-full max-w-sm shadow-2xl">
        <CardHeader className="text-center">
          <div className="flex justify-center items-center mb-4">
            <Landmark className="h-10 w-10 text-primary" />
          </div>
          <CardTitle className="text-3xl font-bold">Welcome Back</CardTitle>
          <CardDescription>Sign in to manage your finances.</CardDescription>
        </CardHeader>
        <CardContent>
           <form onSubmit={handleSubmit} className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="email">Email</Label>
                <Input id="email" name="email" type="email" placeholder="name@example.com" required />
              </div>
               <div className="space-y-2">
                <Label htmlFor="password">Password</Label>
                <Input id="password" name="password" type="password" placeholder="********" required />
              </div>
              <Button type="submit" className="w-full" disabled={isSubmitting || loading}>
                {isSubmitting ? 'Signing In...' : 'Sign In'}
              </Button>
            </form>
        </CardContent>
        <CardFooter className="justify-center">
            <p className="text-sm text-muted-foreground">
                Don't have an account?{' '}
                <Link href="/signup" className="font-semibold text-primary hover:underline">
                    Sign Up
                </Link>
            </p>
        </CardFooter>
      </Card>
    </div>
  );
}
