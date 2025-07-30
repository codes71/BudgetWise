
import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

// This function is defined here to avoid importing firebase-admin in the middleware
async function verifySessionCookie(session: string | undefined, request: NextRequest) {
  if (!session) return null;
  // The actual verification must happen in a server-side environment (e.g., an API route or getServerSideProps).
  // For middleware, we can make a request to an internal API route to verify the session.
  // However, for simplicity and to avoid the original issue, we will do a basic check here
  // and let protected server components or API routes do the full verification.
  // Here we assume if a cookie exists, we let it pass, and server-side logic will validate it.
  const host = request.nextUrl.origin;
  const url = `${host}/api/verify-session`;

  try {
    const response = await fetch(url, {
      headers: {
        'Cookie': `session=${session}`
      }
    });
    if (response.ok) {
        return await response.json();
    }
    return null;
  } catch (error) {
    return null;
  }
}

export async function middleware(request: NextRequest) {
  const session = request.cookies.get('session')?.value;
  const publicRoutes = ['/login'];
  const isPublicRoute = publicRoutes.includes(request.nextUrl.pathname);

  // If trying to access a protected route without a session, redirect to login
  if (!session && !isPublicRoute) {
    return NextResponse.redirect(new URL('/login', request.url));
  }
  
  // If there is a session and the user tries to access a public route, redirect to home
  if (session && isPublicRoute) {
    // A quick check to see if we should redirect.
    // The full verification happens on the server, not in the edge middleware.
     return NextResponse.redirect(new URL('/', request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ['/((?!api|_next/static|_next/image|favicon.ico).*)'],
};
