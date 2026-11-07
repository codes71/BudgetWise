'use server';

import { getSpendingSuggestions } from '@/ai/flows/spending-suggestions';
import { categorizeTransaction } from '@/ai/flows/categorize-transaction';
import type { Transaction, Budget, UserPayload } from '@/lib/types';
import { createUser, getUserById, toPublicUser, updateUserProfile, type UserRecord } from '@/lib/users';
import bcrypt from 'bcryptjs';
import { SignJWT, jwtVerify } from 'jose';
import { cookies, headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { revalidatePath } from 'next/cache';
import { logger } from '@/lib/logger';
import { extractTransactionData } from '@/ai/flows/extract-transaction-data';
import { verifySession } from '@/lib/auth';
import { authClientIp, authenticatePassword, limitSignIn, limitSignUp } from '@/lib/auth-credentials';
import { loginSchema, registerSchema } from '@/lib/mobile/schemas';
import { ApiError } from '@/lib/mobile/errors';
import { AiUnavailableError } from '@/ai/provider-policy';
import { listBudgets } from '@/lib/budgets';
import { transactionsCol } from '@/lib/mobile/transactions-service';
import { yangonDate } from '@/lib/mobile/scan';

const secretKey = new TextEncoder().encode(process.env.JWT_SECRET);

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

export async function signUp(formData: FormData): Promise<{ error: string } | { user: UserPayload }> {
  const parsed = registerSchema.safeParse({ email: formData.get('email'), password: formData.get('password') });
  if (!parsed.success) return { error: 'Enter a valid email and a password between 6 and 200 characters.' };
  const { email, password } = parsed.data;

  try {
    await limitSignUp(authClientIp(await headers()));
    const hashedPassword = await bcrypt.hash(password, 10);
    // Fails when the email exists in any letter case.
    const newUser = await createUser({
      email,
      passwordHash: hashedPassword,
      fullName: email.trim().split('@')[0], // Default full name
    });
    if (!newUser) {
      return { error: 'User with this email already exists.' };
    }
    // Signup has its own limit. Issue the session without consuming the login allowance.
    return await createPasswordSession(newUser);
  } catch (error) {
    if (error instanceof ApiError && error.status === 429) return { error: 'Too many requests. Please try again later.' };
    logger.error('Error signing up', error as Error);
    return { error: 'An unexpected error occurred.' };
  }
}

export async function signIn(formData: FormData): Promise<{ error: string } | { user: UserPayload }> {
  const parsed = loginSchema.safeParse({ email: formData.get('email'), password: formData.get('password') });
  if (!parsed.success) return { error: 'Invalid email or password.' };
  const { email, password } = parsed.data;
  try {
    await limitSignIn(email, authClientIp(await headers()));
    const user = await authenticatePassword(email, password);
    if (!user) return { error: 'Invalid email or password.' };
    return await createPasswordSession(user);
  } catch (error) {
    if (error instanceof ApiError && error.status === 429) return { error: 'Too many requests. Please try again later.' };
    logger.error('Error signing in', error as Error);
    return { error: 'An unexpected error occurred.' };
  }
}

async function createPasswordSession(user: UserRecord): Promise<{ user: UserPayload }> {
  // Create session
  const expires = new Date(Date.now() + 60 * 60 * 1000); // 1 hour
  const userPayload = {
      userId: user.id,
      email: user.email,
      fullName: user.fullName,
      phoneNumber: user.phoneNumber,
      profilePhotoUrl: user.profilePhotoUrl,
      createdAt: user.createdAt ? user.createdAt.toISOString() : undefined,
  };
  const session = await encrypt({ ...userPayload, expires });

  const cookieStore = await cookies();
  cookieStore.set('session', session, { expires, httpOnly: true, secure: process.env.NODE_ENV === 'production' });

  return { user: userPayload };
}

export async function signInAsGuest(): Promise<{ error?: string; user?: UserPayload }> {
  try {
    const guestId = `guest-${Date.now()}`;
    const userPayload: UserPayload = {
      userId: guestId,
      email: `${guestId}@example.com`,
      fullName: 'Guest User',
      isGuest: true,
    };

    const expires = new Date(Date.now() + 60 * 60 * 1000); // 1 hour
    const session = await encrypt({ ...userPayload, expires });

    const cookieStore = await cookies();
    cookieStore.set('session', session, { expires, httpOnly: true, secure: process.env.NODE_ENV === 'production' });

    return { user: userPayload };
  } catch (error) {
    logger.error('Error signing in as guest', error as Error);
    return { error: 'An unexpected error occurred during guest sign-in.' };
  }
}

export async function signOut(redirectTo: string = '/') {
  const cookieStore = await Promise.resolve(cookies());
  cookieStore.set('session', '', { expires: new Date(0) });
  redirect(redirectTo);
}

export async function updateUser(formData: FormData) {
    const cookieStore = await cookies();
    const session = cookieStore.get('session')?.value;
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

    try {
        const updatedUser = await updateUserProfile(userId, {
            fullName,
            phoneNumber,
            profilePhotoUrl,
        });

        if (!updatedUser) {
            return { error: 'User not found' };
        }

        // Re-encrypt the session with the new data
        const expires = new Date(Date.now() + 60 * 60 * 1000); // 1 hour
        const newSession = await encrypt({
            userId: updatedUser.id,
            email: updatedUser.email,
            fullName: updatedUser.fullName,
            phoneNumber: updatedUser.phoneNumber,
            profilePhotoUrl: updatedUser.profilePhotoUrl,
            createdAt: updatedUser.createdAt?.toISOString(),
            expires
        });
        const cookieStore = await cookies();
        cookieStore.set('session', newSession, { expires, httpOnly: true, secure: process.env.NODE_ENV === 'production' });

        revalidatePath('/myprofile');
        return { success: true, user: toPublicUser(updatedUser) };

    } catch (error) {
        logger.error('Error updating user', error as Error);
        return { error: 'An unexpected error occurred.' };
    }
}


// Keep the existing action contract, but client arrays are not authority for a financial comparison.
export async function generateSuggestions(_transactions: Transaction[], _budgets: Budget[]) {
  const session = await verifySession();
  if (!session || session.isGuest) return { error: 'Sign in to compare your saved spending and budgets.' };
  try {
    const today = yangonDate(new Date());
    const [rows, budgets] = await Promise.all([
      (await transactionsCol()).find({ userId: session.userId, type: 'expense',
        date: { $gte: `${today.slice(0, 7)}-01`, $lte: today } },
        { projection: { category: 1, amount: 1, date: 1, type: 1 } }).limit(10_001).toArray(),
      listBudgets(session.userId),
    ]);
    const result = await getSpendingSuggestions({ transactions: rows.map((row) => ({
      category: row.category, amount: row.amount, date: row.date, type: row.type,
    })), budgets, today });
    return { suggestions: result.suggestions };
  } catch (error) {
    logger.error('Error generating suggestions', error);
    return { error: 'Could not compare your saved spending and budgets. You can review them on the dashboard.' };
  }
}

export async function getCategorySuggestion(description: string, categories: string[]) {
    if (!(await verifySession())) return { category: '' };
    try {
        const result = await categorizeTransaction({ description, categories });
        return { category: result.category };
    } catch (error) {
        logger.error('Error generating category suggestion', error as Error);
        // Don't return an error to the user, just fail silently
        return { category: '' };
    }
}
export async function processScreenshot(base64Image: string) {
  if (!(await verifySession())) return { success: false, error: 'Sign in to use this feature.' };
  try {
    // The authenticated action calls the bounded extraction helper.
    const extractedData = await extractTransactionData({
      image: base64Image,
    });

    // The flow's output is already a structured and validated object.
    // No need for complex parsing or checks like .output().

    return { success: true, data: extractedData };
  } catch (error) {
    // Catch any errors from the flow's internal logic.
    logger.error("Error processing screenshot", error as Error);
    return {
      success: false,
      error: error instanceof AiUnavailableError
        ? "AI assistance is unavailable here. You can enter the transaction manually."
        : "Failed to process screenshot. You can enter the transaction manually or try again.",
    };
  }
}
export async function getUserDetails() {
  const cookieStore = await cookies();
  const sessionCookie = cookieStore.get('session')?.value;
  if (!sessionCookie) {
    return null;
  }

  try {
    const payload = await decrypt(sessionCookie);
    if (!payload || !payload.userId) {
      return null;
    }

    // If it's a guest user, return the payload directly as they are not in the DB
    if (payload.isGuest) {
      return payload;
    }

    // Null for unknown or malformed ids; the password hash is never returned.
    const user = await getUserById(payload.userId);
    return user && !user.deletionStartedAt ? toPublicUser(user) : null;
  } catch (error) {
    logger.error('Error fetching user details', error as Error);
    return null;
  }
}
