'use server';
import { cookies } from 'next/headers';
import { jwtVerify } from 'jose';

const secretKey = new TextEncoder().encode(process.env.JWT_SECRET || 'your-super-secret-jwt-key-that-is-at-least-32-chars-long');

interface UserPayload {
    userId: string;
    email: string;
    fullName?: string;
    phoneNumber?: string;
    profilePhotoUrl?: string;
}

export async function verifySession() {
  const sessionCookie = cookies().get('session')?.value;
  if (!sessionCookie) {
    return null;
  }

  try {
    const { payload } = await jwtVerify(sessionCookie, secretKey, {
      algorithms: ['HS256'],
    });
    return payload as UserPayload;
  } catch (error) {
    console.error('Failed to verify session:', error);
    return null;
  }
}


export async function getCurrentUser() {
  const session = await verifySession();
  if (!session) return null;
  return { uid: session.userId, email: session.email };
}
