'use client';

import { createContext, useContext, useEffect, useState, ReactNode, Dispatch, SetStateAction } from 'react';
import { verifySession } from '@/lib/auth';

interface User {
  userId: string;
  email: string;
  fullName?: string | null;
  phoneNumber?: string | null;
  profilePhotoUrl?: string | null;
}

interface AuthContextType {
  user: User | null;
  setUser: Dispatch<SetStateAction<User | null>>;
  loading: boolean;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType>({
  user: null,
  setUser: () => {},
  loading: true,
  signOut: async () => {},
});


export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function checkSession() {
      const sessionUser = await verifySession();
      if (sessionUser) {
        setUser({
            userId: sessionUser.userId,
            email: sessionUser.email,
            fullName: sessionUser.fullName,
            phoneNumber: sessionUser.phoneNumber,
            profilePhotoUrl: sessionUser.profilePhotoUrl,
        });
      }
      setLoading(false);
    }

    checkSession();
  }, []);
  
  const handleSignOut = async () => {
    // This will be handled by a server action now, but we can keep a client function for components
    // The actual cookie removal and redirect happens on the server.
    // For a better UX, we can optimistically update the state.
    setUser(null);
    // The server action will handle the redirect.
    // No need to call router.push here.
  };


  return (
    <AuthContext.Provider value={{ user, setUser, loading, signOut: handleSignOut }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
