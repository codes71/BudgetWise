'use client';

import { Button } from '@/components/ui/button';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/context/auth-context';
import { signInAsGuest } from '@/app/actions';
import { useToast } from '@/hooks/use-toast';

export default function LandingPage() {
  const router = useRouter();
  const { handleLogin } = useAuth();
  const { toast } = useToast();

  const handleGuestSignIn = async () => {
    const result = await signInAsGuest();
    if (result.error) {
      toast({
        variant: 'destructive',
        title: 'Guest Sign-In Failed',
        description: result.error,
      });
    } else if (result.user) {
      await handleLogin(result.user);
      router.push('/dashboard');
    }
  };

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-background text-foreground">
      <div className="text-center space-y-4">
        <h1 className="text-5xl font-bold">Welcome to BudgetWise</h1>
        <p className="text-xl text-muted-foreground">Your personal finance companion. Track spending, set budgets, and gain insights.</p>
      </div>
      <div className="mt-8 flex gap-4">
        <Button onClick={() => router.push('/login')}>Login</Button>
        <Button variant="secondary" onClick={() => router.push('/signup')}>Sign Up</Button>
        <Button variant="outline" onClick={handleGuestSignIn}>Sign in as Guest</Button>
      </div>
      <div className="mt-12 p-8 bg-muted rounded-lg shadow-lg">
        <h2 className="text-2xl font-semibold mb-4">Dummy Dashboard Preview</h2>
        <p className="text-muted-foreground">This is where a preview of the dashboard will be shown.</p>
        {/* You can add more sophisticated dummy components here */}
      </div>  
    </div>
  );
}
