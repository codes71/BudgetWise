import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { verifySessionToken } from '@/lib/session-claims';

export async function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  // Edge routing uses JWT claims only. Node server actions/API independently require an active account.
  const currentUser = await verifySessionToken(request.cookies.get('session')?.value);

  const protectedRoutes = ['/dashboard', '/budgets', '/myprofile'];

  // --- Handle Redirects for Authenticated Users ---

  // If a user is logged in, redirect them from the landing page to the dashboard.
  if (currentUser && pathname === '/') {
    return NextResponse.redirect(new URL('/dashboard', request.url));
  }

  // Do not redirect sign-in based on JWT claims alone. A deleted account may still have a valid cookie;
  // Node authorization rejects it, and redirecting it back to the dashboard would create a login loop.

  // --- Handle Route Protection for Unauthenticated Users ---

  // If a user is NOT logged in and tries to access a protected route, redirect them.
  if (!currentUser && protectedRoutes.includes(pathname)) {
    return NextResponse.redirect(new URL('/login', request.url));
  }

  // --- Allow all other requests to proceed ---
  // This allows Next.js to handle all other routes, including 404 pages.
  return NextResponse.next();
}

export const config = {
  // Update metadata is public static content, independent of web sessions.
  matcher: ['/((?!api|updates/|privacy(?:/|$)|delete-account(?:/|$)|_next/static|_next/image|favicon.ico).*)'],
};
