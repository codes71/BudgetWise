// src/context/auth-context.tsx
'use client';

import { createContext, useContext, useEffect, useState, ReactNode, Dispatch, SetStateAction } from 'react';
import { getCategories, getBudgets, getTransactions } from '@/app/db-actions';
import { getUserDetails } from '@/app/actions'; // New import for fetching user details
import { verifySession } from '@/lib/auth';
import type { Budget, Transaction, UserPayload } from '@/lib/types';
import { guestBudgets, guestCategories, guestTransactions } from '@/lib/guest-data';
import { signOut as performSignOut } from '@/app/actions';

type Currency = 'INR' | 'MMK';

interface AuthContextType {
  user: UserPayload | null;
  setUser: Dispatch<SetStateAction<UserPayload | null>>;
  loading: boolean;
  signOut: (redirectTo?: string) => Promise<void>;
  currency: Currency;
  setCurrency: Dispatch<SetStateAction<Currency>>;
  transactions: Transaction[];
  setTransactions: Dispatch<SetStateAction<Transaction[]>>;
  budgets: Budget[];
  setBudgets: Dispatch<SetStateAction<Budget[]>>;
  categories: string[];
  setCategories: Dispatch<SetStateAction<string[]>>;
  fetchUserData: () => Promise<void>;
  handleLogin: (user: UserPayload) => Promise<void>;
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
  handleLogin: async () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [currency, setCurrency] = useState<Currency>('INR');
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [categories, setCategories] = useState<string[]>([]);

  const fetchUserData = async () => {
    try {
      const [fetchedCategories, fetchedBudgets, fetchedTransactions, userDetails] = await Promise.all([
        getCategories(),
        getBudgets(),
        getTransactions(),
        getUserDetails(),
      ]);
      setCategories(fetchedCategories);
      setBudgets(fetchedBudgets);
      setTransactions(fetchedTransactions);
      if (userDetails) {
        setUser(userDetails);
      }
    } catch (error) {
      console.error("Failed to fetch user data:", error);
    }
  };

  const fetchGuestUserData = (guestUser: UserPayload) => {
    setUser(guestUser);
    setCategories(guestCategories);
    setTransactions(guestTransactions);
    setBudgets(guestBudgets);
  };

  useEffect(() => {
    async function checkSessionAndFetchData() {
      try {
        const sessionUser = await verifySession();
        if (sessionUser) {
          if (sessionUser.isGuest) {
            fetchGuestUserData(sessionUser);
          } else {
            await fetchUserData();
          }
        } else {
          setUser(null);
        }
      } catch (error) {
        console.error("Failed to verify session or fetch initial data:", error);
      } finally {
        setLoading(false);
      }
    }
    checkSessionAndFetchData();
  }, []);

  const handleLogin = async (user: UserPayload) => {
    if (user.isGuest) {
      fetchGuestUserData(user);
    } else {
      setUser(user);
      await fetchUserData();
    }
  };

  const handleSignOut = async (redirectTo?: string) => {
    setUser(null);
    setTransactions([]);
    setBudgets([]);
    setCategories([]);
    await performSignOut(redirectTo);
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
        fetchUserData,
        handleLogin,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}