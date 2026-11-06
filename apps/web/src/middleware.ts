import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { verifySessionToken } from '@/lib/session-claims';

export async function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const isReadMethod = ['GET', 'HEAD', 'OPTIONS'].includes(request.method.toUpperCase());
  // A brief source-data freeze can be enabled explicitly during a verified database cutover.
  // Block before reading cookies or verifying claims, including API writers and web server actions.
  if (process.env.MIGRATION_READ_ONLY === '1' && !isReadMethod) {
    return NextResponse.json(
      { error: { code: 'temporarily_unavailable', message: 'Changes are temporarily unavailable. Please try again shortly.' } },
      { status: 503, headers: { 'Retry-After': '60', 'Cache-Control': 'no-store' } },
    );
  }

  // APIs enforce their own bearer/session rules. Public policy pages remain independent of web cookies.
  if (pathname === '/api' || pathname.startsWith('/api/')
    || pathname === '/privacy' || pathname.startsWith('/privacy/')
    || pathname === '/delete-account' || pathname.startsWith('/delete-account/')) {
    return NextResponse.next();
  }
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
  // Include APIs and policy pages so mutating requests cannot bypass an explicitly enabled source freeze.
  // Update metadata and framework static assets stay public and independent of web sessions.
  matcher: ['/((?!updates/|_next/static|_next/image|favicon.ico).*)'],
};
