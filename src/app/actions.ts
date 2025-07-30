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
import { revalidatePath } from 'next/cache';

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
    fullName: email.split('@')[0], // Default full name
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
  const session = await encrypt({ 
      userId: user._id, 
      email: user.email,
      fullName: user.fullName,
      phoneNumber: user.phoneNumber,
      profilePhotoUrl: user.profilePhotoUrl,
      expires 
    });

  cookies().set('session', session, { expires, httpOnly: true, secure: process.env.NODE_ENV === 'production' });

  redirect('/');
}

export async function signOut() {
  cookies().set('session', '', { expires: new Date(0) });
  redirect('/login');
}

export async function updateUser(formData: FormData) {
    const session = cookies().get('session')?.value;
    if (!session) {
        return { error: 'Unauthorized' };
    }
    const payload = await decrypt(session);
    if (!payload?.userId) {
        return { error: 'Unauthorized' };
    }

    const userId = payload.userId;
    const fullName = formData.get('fullName') as string;
    const phoneNumber = formData.get('phoneNumber') as string;
    const profilePhotoUrl = formData.get('profilePhotoUrl') as string;

    await dbConnect();

    try {
        const updatedUser = await UserModel.findByIdAndUpdate(userId, {
            fullName,
            phoneNumber,
            profilePhotoUrl,
        }, { new: true });

        if (!updatedUser) {
            return { error: 'User not found' };
        }
        
        // Re-encrypt the session with the new data
        const expires = new Date(Date.now() + 60 * 60 * 1000); // 1 hour
        const newSession = await encrypt({
            userId: updatedUser._id,
            email: updatedUser.email,
            fullName: updatedUser.fullName,
            phoneNumber: updatedUser.phoneNumber,
            profilePhotoUrl: updatedUser.profilePhotoUrl,
            expires
        });
        cookies().set('session', newSession, { expires, httpOnly: true, secure: process.env.NODE_ENV === 'production' });
        
        revalidatePath('/myprofile');
        return { success: true, user: JSON.parse(JSON.stringify(updatedUser)) };

    } catch (error) {
        console.error('Error updating user:', error);
        return { error: 'An unexpected error occurred.' };
    }
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
