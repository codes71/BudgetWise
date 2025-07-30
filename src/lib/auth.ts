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
    // Session cookie is invalid or expired.
    // It's also possible that the user is trying to access a public route with an invalid cookie.
    // The middleware will handle redirects.
    return null;
  }
}
