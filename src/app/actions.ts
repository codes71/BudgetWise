'use server';

import { getSpendingSuggestions } from '@/ai/flows/spending-suggestions';
import { categorizeTransaction } from '@/ai/flows/categorize-transaction';
import type { Transaction, Budget } from '@/lib/types';
import dbConnect from '@/lib/db';
import UserModel from '@/lib/models/user';
import bcrypt from 'bcryptjs';
import { SignJWT, jwtVerify } from 'jose';
import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';

const secretKey = new TextEncoder().encode(process.env.JWT_SECRET || 'your-super-secret-jwt-key-that-is-at-least-32-chars-long');

async function encrypt(payload: any) {
  return new SignJWT(payload)
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuedAt()
    .setExpirationTime('1h') // Token expires in 1 hour
    .sign(secretKey);
}

async function decrypt(token: string): Promise<any> {
  try {
    const { payload } = await jwtVerify(token, secretKey, {
      algorithms: ['HS256'],
    });
    return payload;
  } catch (error) {
    return null;
  }
}

export async function signUp(formData: FormData) {
  const email = formData.get('email') as string;
  const password = formData.get('password') as string;

  if (!email || !password) {
    return { error: 'Email and password are required.' };
  }

  await dbConnect();

  const existingUser = await UserModel.findOne({ email });
  if (existingUser) {
    return { error: 'User with this email already exists.' };
  }

  const hashedPassword = await bcrypt.hash(password, 10);
  const newUser = new UserModel({
    email,
    password: hashedPassword,
  });

  try {
    await newUser.save();
    // Automatically sign in the user after registration
    return await signIn(formData);
  } catch (error) {
    console.error('Error signing up:', error);
    return { error: 'An unexpected error occurred.' };
  }
}

export async function signIn(formData: FormData) {
  const email = formData.get('email') as string;
  const password = formData.get('password') as string;

  if (!email || !password) {
    return { error: 'Email and password are required.' };
  }

  await dbConnect();
  
  const user = await UserModel.findOne({ email });

  if (!user || !user.password) {
    return { error: 'Invalid email or password.' };
  }
  
  const isPasswordValid = await bcrypt.compare(password, user.password);

  if (!isPasswordValid) {
    return { error: 'Invalid email or password.' };
  }

  // Create session
  const expires = new Date(Date.now() + 60 * 60 * 1000); // 1 hour
  const session = await encrypt({ userId: user._id, email: user.email, expires });

  cookies().set('session', session, { expires, httpOnly: true, secure: process.env.NODE_ENV === 'production' });

  redirect('/');
}

export async function signOut() {
  cookies().set('session', '', { expires: new Date(0) });
  redirect('/login');
}


export async function generateSuggestions(transactions: Transaction[], budgets: Budget[]) {
  const historicalData =
    'category,amount,date\n' +
    transactions
      .filter((t) => t.type === 'expense')
      .map((t) => `${t.category},${t.amount},${t.date}`)
      .join('\n');
      
  const budgetGoals = 'category,budget\n' + budgets.map((b) => `${b.limit ? `${b.category},${b.limit}` : ''}`).filter(Boolean).join('\n');

  try {
    const result = await getSpendingSuggestions({ historicalData, budgetGoals });
    return { suggestions: result.suggestions };
  } catch (error) {
    console.error('Error generating suggestions:', error);
    return { error: 'Failed to generate suggestions. Please try again.' };
  }
}

export async function getCategorySuggestion(description: string, categories: string[]) {
    try {
        const result = await categorizeTransaction({ description, categories });
        return { category: result.category };
    } catch (error) {
        console.error('Error generating category suggestion:', error);
        // Don't return an error to the user, just fail silently
        return { category: '' };
    }
}
