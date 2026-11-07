import Link from 'next/link';
import Image from 'next/image';
import type { ReactNode } from 'react';

export function PublicPolicyLayout({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="border-b">
        <div className="mx-auto flex max-w-3xl flex-wrap items-center justify-between gap-4 px-4 py-5 sm:px-6">
          <Link href="/" className="inline-flex min-h-12 items-center gap-2 rounded-md font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <Image src="/logo.png" alt="" width={24} height={24} aria-hidden="true" />
            Pocket Peek
          </Link>
          <nav aria-label="Privacy and account" className="flex flex-wrap gap-x-5 gap-y-2 text-sm">
            <Link href="/privacy" className="inline-flex min-h-12 items-center rounded-md underline underline-offset-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">Privacy policy</Link>
            <Link href="/delete-account" className="inline-flex min-h-12 items-center rounded-md underline underline-offset-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">Delete account</Link>
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-3xl px-4 py-8 sm:px-6 sm:py-12">
        <h1 className="mb-6 text-3xl font-bold tracking-tight">{title}</h1>
        {children}
      </main>
      <footer className="mx-auto flex max-w-3xl flex-wrap gap-x-5 gap-y-2 border-t px-4 py-6 text-sm text-muted-foreground sm:px-6">
        <span>Published by thurashark</span>
        <a className="break-all underline underline-offset-4" href="mailto:thurashark@gmail.com">thurashark@gmail.com</a>
      </footer>
    </div>
  );
}

export function PolicyLinks() {
  return (
    <nav aria-label="Privacy and account" className="mt-5 flex flex-wrap justify-center gap-x-5 gap-y-2 text-sm text-muted-foreground">
      <Link href="/privacy" className="inline-flex min-h-12 items-center underline underline-offset-4">Privacy policy</Link>
      <Link href="/delete-account" className="inline-flex min-h-12 items-center underline underline-offset-4">Delete account</Link>
    </nav>
  );
}
