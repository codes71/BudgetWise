'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { Landmark } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Eye, EyeOff } from 'lucide-react';
import { Label } from '@/components/ui/label';
import { useAuth } from '@/context/auth-context';
import { useToast } from '@/hooks/use-toast';
import { signUp } from '@/app/actions';


export default function SignupPage() {
  const { user, loading } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [showPassword, setShowPassword] = useState(false);

  const togglePasswordVisibility = () => {
    setShowPassword((prev) => !prev);
  };

  useEffect(() => {
    if (!loading && user) {
      router.push('/');
    }
  }, [user, loading, router]);
  
  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setIsSubmitting(true);
    
    const formData = new FormData(event.currentTarget);
    const password = formData.get('password') as string;
    
    if(password.length < 6) {
        toast({
            variant: 'destructive',
            title: 'Sign Up Failed',
            description: 'Password must be at least 6 characters long.',
        });
        setIsSubmitting(false);
        return;
    }

    const result = await signUp(formData);

    // signUp action now handles redirect on success
    if (result?.error) {
       toast({
        variant: 'destructive',
        title: 'Sign Up Failed',
        description: result.error,
      });
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
          <CardTitle className="text-3xl font-bold">Create an Account</CardTitle>
          <CardDescription>Start managing your finances today.</CardDescription>
        </CardHeader>
        <CardContent>
           <form onSubmit={handleSubmit} className="space-y-4">
                <div className="space-y-2">
                    <Label htmlFor="email">Email</Label>
                    <Input id="email" name="email" type="email" placeholder="name@example.com" required />
                </div>
                <div className="space-y-2">
                    <Label htmlFor="password">Password</Label>
                    <div className="relative">
                      <Input
                        id="password"
                        name="password"
                        type={showPassword ? 'text' : 'password'}
                        placeholder="********"
                        required
                      />
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        className="absolute right-0 top-0 h-full px-3 py-2 hover:bg-transparent"
                        onClick={togglePasswordVisibility}
                      >
                        {showPassword ? (
                          <Eye className="h-4 w-4" aria-hidden="true" />
                        ) : (
                          <EyeOff className="h-4 w-4" aria-hidden="true" />
                        )}
                        <span className="sr-only">
                          {showPassword ? 'Hide password' : 'Show password'}
                        </span>
                      </Button>
                    </div>
                </div>
              <Button type="submit" className="w-full" disabled={isSubmitting || loading}>
                {isSubmitting ? 'Creating Account...' : 'Sign Up'}
              </Button>
            </form>
        </CardContent>
        <CardFooter className="justify-center">
            <p className="text-sm text-muted-foreground">
                Already have an account?{' '}
                <Link href="/login" className="font-semibold text-primary hover:underline">
                    Sign In
                </Link>
            </p>
        </CardFooter>
      </Card>
    </div>
  );
}
