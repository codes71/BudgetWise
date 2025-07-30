import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { verifySession } from '@/lib/auth';

export async function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const isPublicRoute = pathname === '/login' || pathname === '/signup';
  
  const currentUser = await verifySession();

  if (!currentUser && !isPublicRoute) {
    return NextResponse.redirect(new URL('/login', request.url));
  }
  
  if (currentUser && isPublicRoute) {
     return NextResponse.redirect(new URL('/', request.url));
  }

  return NextResponse.next();
}

export const config = {
  // Match all routes except for API routes, static files, and image optimization files.
  matcher: ['/((?!api|_next/static|_next/image|favicon.ico).*)'],
};
