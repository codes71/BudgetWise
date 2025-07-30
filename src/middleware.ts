
import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

async function verifySession(request: NextRequest) {
    const sessionCookie = request.cookies.get('session')?.value;
    if (!sessionCookie) {
        return null;
    }

    try {
        // The URL for verification should be an absolute URL
        const url = new URL('/api/verify-session', request.url);
        const response = await fetch(url.toString(), {
            headers: {
                'Cookie': `session=${sessionCookie}`
            },
        });

        if (response.ok) {
            return await response.json();
        }
        return null;
    } catch (e) {
        console.error('Error verifying session:', e);
        return null;
    }
}


export async function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const isPublicRoute = pathname === '/login' || pathname === '/signup';
  
  const user = await verifySession(request);

  if (!user && !isPublicRoute) {
    return NextResponse.redirect(new URL('/login', request.url));
  }
  
  if (user && isPublicRoute) {
     return NextResponse.redirect(new URL('/', request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ['/((?!api/|login|signup|_next/static|_next/image|favicon.ico).*)', '/', '/budgets', '/myprofile'],
};
