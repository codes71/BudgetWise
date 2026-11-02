'use server';
import { cookies } from 'next/headers';
import { logger } from '@/lib/logger';
import { jwtVerify, type JWTPayload } from 'jose';
import { UserPayload } from '@/lib/types';

const secretKey = new TextEncoder().encode(process.env.JWT_SECRET || 'your-super-secret-jwt-key-that-is-at-least-32-chars-long');

interface ExpectedPayload extends JWTPayload {
  userId?: string;
  email?: string;
  fullName?: string;
  phoneNumber?: string;
  profilePhotoUrl?: string;
}

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
    const { payload } = await jwtVerify(sessionCookie, secretKey, {
      algorithms: ['HS256'],
    });

    // Check if the payload has the expected properties
    if (typeof payload.userId !== 'string' || typeof payload.email !== 'string') {
        if (process.env.NODE_ENV === 'development') {
          logger.error('Invalid payload in session cookie');
        }
        return null;
    }

    return {
      userId: payload.userId,
      email: payload.email,
      fullName: payload.fullName || null,
      phoneNumber: payload.phoneNumber || null,
      profilePhotoUrl: payload.profilePhotoUrl || null,
    } as UserPayload;
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