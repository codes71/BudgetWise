'use client';

import { useAuth } from '@/context/auth-context';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect } from 'react';
import { useToast } from '@/hooks/use-toast';

const FullPageLoader = () => (
  <div className="flex items-center justify-center min-h-screen bg-background">
    <div className="animate-spin rounded-full h-32 w-32 border-t-2 border-b-2 border-primary"></div>
  </div>
);

const GuestAccessDenied = () => {
  const { toast } = useToast();
  const router = useRouter();

  useEffect(() => {
    toast({
      title: 'Feature Access',
      description: 'Please log in or sign up to access this page.',
      variant: 'destructive',
    });
    // Redirect the user to the dashboard after showing the toast.
    router.replace('/dashboard');
  }, [toast, router]);

  // Render a loader while the redirect is happening to avoid showing a blank page.
  return <FullPageLoader />;
};


export function AuthenticatedLayout({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const pathname = usePathname();

  if (loading) {
    return <FullPageLoader />;
  }

  // If the user is a guest and trying to access a restricted page, show an access denied message.
  if (user?.isGuest && pathname !== '/dashboard') {
    return <GuestAccessDenied />;
  }

  // If loading is finished and there's still no user, this is a fallback to prevent rendering broken pages.
  // Middleware should handle the primary redirection.
  if (!user) {
    return <FullPageLoader />;
  }

  // Otherwise, the user is authenticated (or a guest on the dashboard), so render the children.
  return <>{children}</>;
}
