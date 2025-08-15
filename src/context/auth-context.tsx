// src/context/auth-context.tsx
'use client';

import { createContext, useContext, useEffect, useState, ReactNode, Dispatch, SetStateAction } from 'react';
import { getCategories, getBudgets, getTransactions } from '@/app/db-actions';
import { verifySession } from '@/lib/auth';
import type { Budget, Transaction } from '@/lib/types';
import { signOut as performSignOut } from '@/app/db-actions';

type Currency = 'INR' | 'MMK';

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
  currency: Currency;
  setCurrency: Dispatch<SetStateAction<Currency>>;
  transactions: Transaction[];
  setTransactions: Dispatch<SetStateAction<Transaction[]>>;
  budgets: Budget[];
  setBudgets: Dispatch<SetStateAction<Budget[]>>;
  categories: string[];
  setCategories: Dispatch<SetStateAction<string[]>>;
  // Add a function to fetch all user data after login
  fetchUserData: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType>({
  user: null,
  setUser: () => {},
  loading: true,
  signOut: async () => {},
  currency: 'INR',
  setCurrency: () => {},
  transactions: [],
  setTransactions: () => {},
  budgets: [],
  setBudgets: () => {},
  categories: [],
  setCategories: () => {},
  fetchUserData: async () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [currency, setCurrency] = useState<Currency>('INR');
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [categories, setCategories] = useState<string[]>([]);

  // Function to fetch all data for a logged-in user
  const fetchUserData = async () => {
    try {
      const [fetchedCategories, fetchedBudgets, fetchedTransactions] = await Promise.all([
        getCategories(),
        getBudgets(),
        getTransactions(),
      ]);
      setCategories(fetchedCategories);
      setBudgets(fetchedBudgets);
      setTransactions(fetchedTransactions);
    } catch (error) {
      console.error("Failed to fetch user data:", error);
      // Handle error, e.g., set some error state
    }
  };

  useEffect(() => {
    async function checkSessionAndFetchData() {
      try {
        const sessionUser = await verifySession();
        if (sessionUser) {
          setUser({
            userId: sessionUser.userId,
            email: sessionUser.email,
            fullName: sessionUser.fullName,
            phoneNumber: sessionUser.phoneNumber,
            profilePhotoUrl: sessionUser.profilePhotoUrl,
          });
          await fetchUserData(); // Fetch data if a session already exists
        }
      } catch (error) {
        console.error("Failed to verify session or fetch initial data:", error);
      } finally {
        setLoading(false);
      }
    }
    checkSessionAndFetchData();
  }, []);

  const handleSignOut = async () => {
    setUser(null);
    // setLoading(true);
    await performSignOut();
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        setUser,
        loading,
        signOut: handleSignOut,
        currency,
        setCurrency,
        transactions,
        setTransactions,
        budgets,
        setBudgets,
        categories,
        setCategories,
        fetchUserData, // Provide the new function
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}