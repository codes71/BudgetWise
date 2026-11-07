import { afterEach, describe, expect, it, vi } from 'vitest';
import { logger } from '@/lib/logger';
import { errorDiagnostics } from '@/lib/error-diagnostics';

afterEach(() => vi.restoreAllMocks());

describe('content-free operational diagnostics', () => {
  const secret = 'PRIVATE-RECEIPT-password-bearer-speech@example.com';

  it('never serializes exception messages, custom names, stacks, request bodies or nested causes', () => {
    const write = vi.spyOn(console, 'error').mockImplementation(() => {});
    const error = Object.assign(new Error(secret, { cause: new Error(secret) }), {
      name: secret, stack: secret, status: 503,
      request: { authorization: secret, body: secret },
    });
    logger.error('Scan failed', error, { ms: 12, data: secret, error: secret, userId: secret, transcript: secret });
    const raw = String(write.mock.calls[0][0]);
    expect(raw).not.toContain(secret);
    expect(JSON.parse(raw)).toMatchObject({
      message: 'Scan failed', context: { ms: 12 }, error: { kind: 'http', status: 503 },
    });
  });

  it('retains bounded operational fields while dropping unknown values, nesting and provider strings', () => {
    const write = vi.spyOn(console, 'log').mockImplementation(() => {});
    logger.info('Scan done', {
      flow: 'scan', ms: 25, imageBytes: 42, kind: 'receipt', keptKind: 'receipt', rawEntries: 2,
      keptEntries: 2, mediaType: 'image/jpeg', reason: secret, status: secret, model: secret,
      stopReason: secret, outputTokens: Number.POSITIVE_INFINITY, inputTokens: -1,
      other: { content: secret }, token: secret,
    });
    const raw = String(write.mock.calls[0][0]);
    expect(raw).not.toContain(secret);
    expect(JSON.parse(raw).context).toEqual({
      flow: 'scan', ms: 25, imageBytes: 42, kind: 'receipt', keptKind: 'receipt', rawEntries: 2,
      keptEntries: 2, mediaType: 'image/jpeg',
    });
  });

  it('allows fixed refusal/deadline classifications, never attacker-defined names or invalid HTTP codes', () => {
    expect(errorDiagnostics({ name: 'RefusalError', message: secret })).toEqual({ kind: 'refusal' });
    expect(errorDiagnostics({ name: 'DeadlineError', stack: secret })).toEqual({ kind: 'timeout' });
    for (const status of [NaN, Infinity, 200, 999, secret]) {
      expect(errorDiagnostics({ name: secret, status })).toEqual({ kind: 'unexpected' });
    }
    expect(errorDiagnostics({ name: '__proto__', status: 422 })).toEqual({ kind: 'http', status: 422 });
  });
});
