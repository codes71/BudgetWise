
import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { getCurrentUser } from '@/lib/auth';

export async function middleware(request: NextRequest) {
  const session = request.cookies.get('session')?.value;
  const publicRoutes = ['/login'];
  const isPublicRoute = publicRoutes.includes(request.nextUrl.pathname);

  // If there's no session and the route is not public, redirect to login
  if (!session && !isPublicRoute) {
    return NextResponse.redirect(new URL('/login', request.url));
  }

  // If there is a session and the user tries to access a public route, redirect to home
  if (session && isPublicRoute) {
    try {
      // We quickly verify the cookie to see if we should redirect
      await getCurrentUser();
      return NextResponse.redirect(new URL('/', request.url));
    } catch (e) {
      // If verification fails, let them proceed to the public route, but clear the cookie
       const response = NextResponse.next();
       response.cookies.delete('session');
       return response;
    }
  }

  return NextResponse.next();
}

export const config = {
  matcher: ['/((?!api|_next/static|_next/image|favicon.ico).*)'],
};
