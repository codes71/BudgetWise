'use server';
import { cookies } from 'next/headers';
import { jwtVerify, type JWTPayload } from 'jose';

const secretKey = new TextEncoder().encode(process.env.JWT_SECRET || 'your-super-secret-jwt-key-that-is-at-least-32-chars-long');

export interface UserPayload {
    userId: string;
    email: string;
    fullName?: string;
    phoneNumber?: string;
    profilePhotoUrl?: string;
}

interface ExpectedPayload extends JWTPayload {
  userId?: string;
  email?: string;
  fullName?: string;
  phoneNumber?: string;
  profilePhotoUrl?: string;
}

export async function verifySession() {
  const cookieStore = await cookies();
  if (!cookieStore) {
    console.error('No cookie store found');
    return null;
  }
  const sessionCookie = cookieStore.get('session')?.value;
  if (!sessionCookie) {
    console.error('No session cookie found');
    return null;
  }

  try {
    const { payload } = await jwtVerify(sessionCookie, secretKey, {
      algorithms: ['HS256'],
    });

    // Check if the payload has the expected properties
    if (typeof payload.userId !== 'string' || typeof payload.email !== 'string') {
        console.error('Invalid payload in session cookie');
        return null;
    }

    return payload as UserPayload;
  } catch (error) {
    console.error('Failed to verify session:', error);
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
