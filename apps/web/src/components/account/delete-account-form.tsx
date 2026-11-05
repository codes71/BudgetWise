'use client';

import { useRef, useState, type FormEvent } from 'react';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useAuth } from '@/context/auth-context';

type DeletionSession = { email: string; id: string; token: string };

async function errorCode(response: Response): Promise<string> {
  try {
    const body = await response.json();
    return typeof body?.error?.code === 'string' ? body.error.code : 'request_failed';
  } catch {
    return 'request_failed';
  }
}

function failureMessage(code: string): string {
  switch (code) {
    case 'invalid_credentials': return 'The email or password was not recognized. Check them and try again. If this account was already deleted, it can no longer sign in.';
    case 'invalid_password': return 'The password was not recognized. Enter your current account password.';
    case 'unauthorized': return 'We could not verify this request. Enter your password and try again.';
    case 'rate_limited': return 'There have been too many attempts. Wait a few minutes, then try again.';
    case 'deletion_incomplete': return 'Deletion started but has not finished. Use the same email and password to retry.';
    default: return 'We could not confirm deletion. Check your connection and retry with the same account.';
  }
}

export function DeleteAccountForm() {
  const { clearDeletedAccount } = useAuth();
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [complete, setComplete] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pending = useRef(false);
  // Only memory: retained after an ambiguous network failure so an idempotent
  // retry can confirm completion even if the server already removed the user.
  const retrySession = useRef<DeletionSession | null>(null);
  const successHeading = useRef<HTMLHeadingElement>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current || !confirmed) return;
    const form = event.currentTarget;
    const values = new FormData(form);
    const email = String(values.get('email') ?? '').trim();
    const normalized = email.toLowerCase();
    const password = String(values.get('password') ?? '');
    if (!email || !password) return;
    pending.current = true;
    setBusy(true);
    setError(null);
    try {
      let session = retrySession.current?.email === normalized ? retrySession.current : null;
      if (!session) {
        const response = await fetch('/api/v1/auth/token', {
          method: 'POST', cache: 'no-store',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email, password }),
        });
        if (!response.ok) throw new Error(await errorCode(response));
        const result = await response.json();
        if (typeof result?.token !== 'string' || typeof result?.user?.id !== 'string') throw new Error('request_failed');
        session = { email: normalized, id: result.user.id, token: result.token };
        retrySession.current = session;
      }
      const response = await fetch('/api/v1/account', {
        method: 'DELETE', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.token}` },
        body: JSON.stringify({ password }),
      });
      if (response.status !== 204) {
        const code = await errorCode(response);
        if (code === 'unauthorized') retrySession.current = null;
        throw new Error(code);
      }
      clearDeletedAccount(session.id);
      retrySession.current = null;
      form.reset();
      setComplete(true);
      requestAnimationFrame(() => successHeading.current?.focus());
    } catch (failure) {
      setError(failureMessage(failure instanceof Error ? failure.message : 'request_failed'));
    } finally {
      const field = form.elements.namedItem('password');
      if (field instanceof HTMLInputElement) field.value = '';
      pending.current = false;
      setBusy(false);
    }
  }

  if (complete) {
    return (
      <section role="status" aria-live="polite" className="space-y-4 rounded-lg border p-6">
        <h2 ref={successHeading} tabIndex={-1} className="text-xl font-semibold focus:outline-none">Your account has been deleted</h2>
        <p>Your BudgetWise account and synced financial data have been removed. This cannot be undone.</p>
        <p className="text-sm text-muted-foreground">Offline copies on other devices may remain until you remove that saved account or clear the app&apos;s storage there.</p>
        <Link href="/privacy" className="inline-flex min-h-12 items-center underline underline-offset-4">Read the privacy policy</Link>
      </section>
    );
  }

  return (
    <Card>
      <CardContent className="p-6">
        <form onSubmit={submit} aria-busy={busy} className="space-y-5">
          <p id="deletion-details">This permanently deletes your account and all its synced transactions, budgets, monthly plans and personal categories. You do not need the Android app to make this request.</p>
          <div className="space-y-2">
            <Label htmlFor="delete-email">Account email</Label>
            <Input id="delete-email" name="email" type="email" autoComplete="username" required maxLength={254} disabled={busy} className="min-h-12" onChange={() => { setConfirmed(false); setError(null); }} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="delete-password">Current password</Label>
            <Input id="delete-password" name="password" type="password" autoComplete="current-password" required disabled={busy} className="min-h-12" aria-describedby="deletion-details" />
          </div>
          <div className="flex min-h-12 items-start gap-3">
            <Checkbox id="delete-confirmed" checked={confirmed} onCheckedChange={(value) => setConfirmed(value === true)} disabled={busy} className="mt-1" />
            <Label htmlFor="delete-confirmed" className="flex min-h-12 cursor-pointer items-start text-sm leading-relaxed">I understand that deleting this account and its financial data is permanent.</Label>
          </div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <Button type="submit" variant="destructive" disabled={busy || !confirmed} className="min-h-12 h-auto w-full whitespace-normal py-3">
            {busy ? 'Deleting account…' : 'Delete account and data'}
          </Button>
          <p className="text-sm text-muted-foreground">Need help accessing the account? Contact <a href="mailto:thurashark@gmail.com" className="break-all underline underline-offset-4">thurashark@gmail.com</a>.</p>
        </form>
      </CardContent>
    </Card>
  );
}
