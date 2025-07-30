import { NextResponse } from 'next/server';

export async function POST() {
  const response = NextResponse.json({}, { status: 200 });
  response.cookies.set({
    name: 'session',
    value: '',
    maxAge: 0,
  });
  return response;
}
