'use server';
import { cookies } from 'next/headers';
import { logger } from '@/lib/logger';
import { UserPayload } from '@/lib/types';
import { verifySessionToken } from '@/lib/session-claims';
import { requireActiveAccount } from '@/lib/account-access';

export async function verifySession(): Promise<UserPayload | null> {
  const cookieStore = await cookies();
  if (!cookieStore) {
    if (process.env.NODE_ENV === 'development') {
      logger.error('No cookie store found');
    }
    return null;
  }
  const sessionCookie = cookieStore.get('session')?.value;
  if (!sessionCookie) {
    if (process.env.NODE_ENV === 'development') {
      logger.error('No session cookie found');
    }
    return null;
  }

  try {
    const session = await verifySessionToken(sessionCookie);
    if (!session) return null;
    if (!session.isGuest) await requireActiveAccount(session.userId);
    return session;
  } catch (error) {
    if (process.env.NODE_ENV === 'development') {
      logger.error('Failed to verify session:', error);
    }
    return null;
  }
}


export async function getCurrentUser() {
  const session = await verifySession();
  if (!session) return null;
  // This is a simplified user object for general use cases.
  // The full payload is available from verifySession()
  return { uid: session.userId, email: session.email };
}
