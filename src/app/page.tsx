'use client';

import { Button } from '@/components/ui/button';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/context/auth-context';
import { signInAsGuest } from '@/app/actions';
import { useToast } from '@/hooks/use-toast';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { DollarSign, BarChart2, BrainCircuit, ScanLine, Target, Github, Linkedin } from 'lucide-react';

const features = [
  {
    icon: <DollarSign className="h-8 w-8 text-primary" />,
    title: 'Smart Transaction Tracking',
    description: 'Effortlessly log your income and expenses to see where your money is going.',
  },
  {
    icon: <Target className="h-8 w-8 text-primary" />,
    title: 'Intelligent Budgeting',
    description: 'Set, monitor, and manage your financial goals with intuitive budgeting tools.',
  },
  {
    icon: <BrainCircuit className="h-8 w-8 text-primary" />,
    title: 'AI-Powered Categorization',
    description: 'Save time with our smart AI that automatically categorizes your spending for you.',
  },
  {
    icon: <BarChart2 className="h-8 w-8 text-primary" />,
    title: 'Visual Spending Insights',
    description: 'Understand your financial habits at a glance with clear, beautiful charts and graphs.',
  },
  {
    icon: <ScanLine className="h-8 w-8 text-primary" />,
    title: 'Receipt Scanning',
    description: 'Simply upload a screenshot of your receipt, and we\'ll extract the data for you.',
  },
];

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
    <div className="flex flex-col items-center justify-center min-h-screen bg-background text-foreground p-4 sm:p-6">
      <div className="text-center space-y-4 max-w-3xl">
        <h1 className="text-4xl sm:text-5xl font-bold">Welcome to BudgetWise</h1>
        <p className="text-lg sm:text-xl text-muted-foreground">
          Your personal finance companion. Track spending, set budgets, and gain insights with the power of AI.
        </p>
      </div>
      <div className="mt-8 flex flex-wrap justify-center gap-4">
        <Button onClick={() => router.push('/login')}>Login</Button>
        <Button variant="secondary" onClick={() => router.push('/signup')}>Sign Up</Button>
        <Button variant="outline" onClick={handleGuestSignIn}>Sign in as Guest</Button>
      </div>
      <div className="mt-16 w-full max-w-5xl">
        <h2 className="text-3xl font-semibold text-center mb-8">Features</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {features.map((feature) => (
            <Card key={feature.title} className="text-center">
              <CardHeader>
                <div className="flex justify-center items-center mb-4">
                  {feature.icon}
                </div>
                <CardTitle>{feature.title}</CardTitle>
              </CardHeader>
              <CardContent>
                <p className="text-muted-foreground">{feature.description}</p>
              </CardContent>
            </Card>
          ))}
        </div>
      </div>
      <div className="mt-16 w-full max-w-5xl text-center">
         <h2 className="text-3xl font-semibold text-center mb-8">About the Developer</h2>
         <Card>
            <CardHeader>
                <CardTitle>Thura Kyaw</CardTitle>
                <CardDescription>Full-Stack Developer</CardDescription>
            </CardHeader>
            <CardContent>
                <p className="mb-4 text-muted-foreground">This application was designed and developed by Thura Kyaw, showcasing modern web technologies and a passion for creating useful, user-friendly tools.</p>
                <div className="flex justify-center gap-4">
                    <a href="https://github.com/thurakyaw" target="_blank" rel="noopener noreferrer">
                        <Button variant="outline"><Github className="mr-2 h-4 w-4"/> GitHub</Button>
                    </a>
                    <a href="https://linkedin.com/in/thura-kyaw-dev" target="_blank" rel="noopener noreferrer">
                        <Button variant="outline"><Linkedin className="mr-2 h-4 w-4"/> LinkedIn</Button>
                    </a>
                </div>
            </CardContent>
         </Card>
      </div>
    </div>
  );
}
