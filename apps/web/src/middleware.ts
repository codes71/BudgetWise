import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { verifySession } from '@/lib/auth';

export async function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const currentUser = await verifySession();

  const publicRoutes = ['/login', '/signup'];
  const protectedRoutes = ['/dashboard', '/budgets', '/myprofile'];

  // --- Handle Redirects for Authenticated Users ---

  // If a user is logged in, redirect them from the landing page to the dashboard.
  if (currentUser && pathname === '/') {
    return NextResponse.redirect(new URL('/dashboard', request.url));
  }

  // If a non-guest user is logged in, prevent them from accessing login/signup pages.
  if (currentUser && !currentUser.isGuest && publicRoutes.includes(pathname)) {
    return NextResponse.redirect(new URL('/dashboard', request.url));
  }

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
  // Match all routes except for API routes, static files, and image optimization files.
  matcher: ['/((?!api|_next/static|_next/image|favicon.ico).*)'],
};
