import { NextResponse } from 'next/server';
import { admin } from '@/lib/firebase-admin';
import { cookies } from 'next/headers';

export async function GET() {
  const session = cookies().get('session')?.value || '';

  if (!session) {
    return NextResponse.json({ error: 'No session cookie' }, { status: 401 });
  }

  try {
    const decodedClaims = await admin.auth().verifySessionCookie(session, true);
    return NextResponse.json({ user: decodedClaims }, { status: 200 });
  } catch (error) {
    return NextResponse.json({ error: 'Invalid session cookie' }, { status: 401 });
  }
}
