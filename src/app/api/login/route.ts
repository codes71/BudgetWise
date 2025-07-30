import { NextResponse } from 'next/server';
import { admin } from '@/lib/firebase-admin';

export async function POST(request: Request) {
  const authorization = request.headers.get('Authorization');
  if (authorization?.startsWith('Bearer ')) {
    const idToken = authorization.split('Bearer ')[1];
    const decodedToken = await admin.auth().verifyIdToken(idToken);

    if (decodedToken) {
      //Generate session cookie
      const expiresIn = 60 * 60 * 24 * 5 * 1000; // 5 days
      const sessionCookie = await admin.auth().createSessionCookie(idToken, { expiresIn });
      const options = { name: 'session', value: sessionCookie, maxAge: expiresIn, httpOnly: true, secure: true };

      const response = NextResponse.json({}, { status: 200 });
      response.cookies.set(options);
      return response;
    }
  }

  return NextResponse.json({}, { status: 401 });