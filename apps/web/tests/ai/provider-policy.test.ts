import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/headers', () => ({ headers: vi.fn() }));
import { headers } from 'next/headers';
import { permittedProviders, requestProviders } from '@/ai/provider-policy';

const enabled = (): NodeJS.ProcessEnv => ({
  NODE_ENV: 'test', VERCEL: '1', VERCEL_ENV: 'preview', AI_PROVIDER_ORDER: 'openai,anthropic',
  AI_OPENAI_ACCOUNT_ELIGIBLE: 'true', AI_OPENAI_ALLOWED_COUNTRIES: 'MM,US', OPENAI_API_KEY: 'synthetic-key',
  AI_ANTHROPIC_ACCOUNT_ELIGIBLE: 'true', AI_ANTHROPIC_ALLOWED_COUNTRIES: 'US', ANTHROPIC_API_KEY: 'synthetic-key',
});

describe('provider policy', () => {
  it('does not treat a configured key as eligibility', () => {
    expect(permittedProviders({ VERCEL: '1', OPENAI_API_KEY: 'synthetic-key' }, 'MM')).toEqual([]);
    expect(permittedProviders({ ...enabled(), AI_OPENAI_ACCOUNT_ELIGIBLE: undefined }, 'MM')).toEqual([]);
  });
  it('permits OpenAI-only use from Myanmar and never routes it to Anthropic', () => {
    expect(permittedProviders(enabled(), 'MM')).toEqual(['openai']);
    expect(permittedProviders({ ...enabled(), AI_ANTHROPIC_ALLOWED_COUNTRIES: 'MM' }, 'MM')).toEqual(['openai']);
  });
  it('requires reviewed country scopes and respects explicit order', () => {
    expect(permittedProviders(enabled(), 'US')).toEqual(['openai', 'anthropic']);
    expect(permittedProviders({ ...enabled(), AI_PROVIDER_ORDER: 'anthropic,openai' }, 'US')).toEqual(['anthropic', 'openai']);
    expect(permittedProviders(enabled(), 'SG')).toEqual([]);
  });
  it.each([null, '', 'unknown', 'mm', 'MM,US', 'ZZ', 'CN', 'RU', 'UA'])('fails closed for unknown, malformed or unsupported country %s', (country) => {
    expect(permittedProviders(enabled(), country)).toEqual([]);
  });
  it.each(['', '*', 'openai,unknown', 'openai,openai', 'openai,'])('rejects malformed provider policy %s', (order) => {
    expect(permittedProviders({ ...enabled(), AI_PROVIDER_ORDER: order }, 'US')).toEqual([]);
  });
  it('rejects wildcard, unsupported and malformed country lists without silently widening them', () => {
    for (const countries of ['*', 'MM,', 'MM,CN', 'MM,UA', 'mm']) {
      expect(permittedProviders({ ...enabled(), AI_PROVIDER_ORDER: 'openai', AI_OPENAI_ALLOWED_COUNTRIES: countries }, 'MM')).toEqual([]);
    }
  });
  it('never trusts caller geography on a non-Vercel/rehosted service', () => {
    expect(permittedProviders({ ...enabled(), VERCEL: undefined }, 'US')).toEqual([]);
    expect(permittedProviders({ ...enabled(), VERCEL_ENV: 'development' }, 'US')).toEqual([]);
    expect(permittedProviders({ ...enabled(), VERCEL_ENV: undefined }, 'US')).toEqual([]);
  });
});

describe('request-country adapter', () => {
  beforeEach(() => {
    vi.mocked(headers).mockReset();
    for (const [key, value] of Object.entries(enabled())) vi.stubEnv(key, value);
  });
  afterEach(() => vi.unstubAllEnvs());
  it('uses the platform-provided country only within a Vercel runtime', async () => {
    vi.mocked(headers).mockResolvedValue(new Headers({ 'x-vercel-ip-country': 'MM' }) as Awaited<ReturnType<typeof headers>>);
    expect(await requestProviders()).toEqual(['openai']);
    vi.stubEnv('VERCEL', '');
    vi.mocked(headers).mockClear();
    expect(await requestProviders()).toEqual([]);
    expect(headers).not.toHaveBeenCalled();
  });
  it('fails closed without request context or country metadata', async () => {
    vi.mocked(headers).mockRejectedValue(new Error('not in a request'));
    expect(await requestProviders()).toEqual([]);
    vi.mocked(headers).mockResolvedValue(new Headers() as Awaited<ReturnType<typeof headers>>);
    expect(await requestProviders()).toEqual([]);
  });
});
