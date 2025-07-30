'use server';
import { cookies } from 'next/headers';
import { admin } from './firebase-admin';

export async function getCurrentUser() {
  const session = cookies().get('session')?.value || '';

  if (!session) {
    return null;
  }

  try {
    const decodedClaims = await admin.auth().verifySessionCookie(session, true);
    return decodedClaims;
  } catch (error) {
    console.error('Error verifying session cookie:', error);
    return null;
  }
}
