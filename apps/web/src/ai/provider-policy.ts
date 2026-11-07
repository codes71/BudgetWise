import { headers } from 'next/headers';

export type Provider = 'openai' | 'anthropic';
export class AiUnavailableError extends Error {
  override name = 'AiUnavailableError';
  constructor() { super('AI assistance is unavailable here. You can enter transactions manually.'); }
}

// Conservative API-country snapshot reviewed 8 November 2026. UA is deliberately excluded because
// both providers exclude subregions and country-only evidence cannot distinguish those locations.
// Account/entity/control eligibility is a separate required operator confirmation, never inferred
// from deployment region, billing currency, VPNs, language, or this network-country check.
const ANTHROPIC_COUNTRIES = new Set(`AL DZ AD AO AG AR AM AU AT AZ BS BH BD BB BE BZ BJ BT BO BA BW BR BN BG BF BI CV KH CM CA TD CL CO KM CG CR CI HR CY CZ DK DJ DM DO EC EG SV GQ EE SZ FJ FI FR GA GM GE DE GH GR GD GT GN GW GY HT VA HN HU IS IN ID IQ IE IL IT JM JP JO KZ KE KI KW KG LA LV LB LS LR LI LT LU MG MW MY MV MT MH MR MU MX FM MD MC MN ME MA MZ NA NR NP NL NZ NE NG MK NO OM PK PW PS PA PG PY PE PH PL PT QA RO RW KN LC VC WS SM ST SA SN RS SC SL SG SK SI SB ZA KR ES LK SR SE CH TW TJ TZ TH TL TG TO TT TN TR TM TV UG AE GB US UY UZ VU VN ZM ZW`.split(' '));
const OPENAI_COUNTRIES = new Set([...ANTHROPIC_COUNTRIES,
  ...`AF AX AW BM KY CF CD ER ET FO GF PF TF GL GP LY ML MQ YT MM NC NI RE BL SH MF PM SO SS SD SJ WF YE`.split(' '),
]);

const envKeys: Record<Provider, { account: string; countries: string; key: string }> = {
  openai: { account: 'AI_OPENAI_ACCOUNT_ELIGIBLE', countries: 'AI_OPENAI_ALLOWED_COUNTRIES', key: 'OPENAI_API_KEY' },
  anthropic: { account: 'AI_ANTHROPIC_ACCOUNT_ELIGIBLE', countries: 'AI_ANTHROPIC_ALLOWED_COUNTRIES', key: 'ANTHROPIC_API_KEY' },
};

// Pure resolver for isolated policy tests. No caller-provided country is accepted by any API/schema.
// Missing/malformed policy configuration, unknown location and unsupported countries all fail closed.
export function permittedProviders(env: Readonly<Record<string, string | undefined>>, country: string | null): Provider[] {
  if (env.VERCEL !== '1' || !['production', 'preview'].includes(env.VERCEL_ENV ?? '')
    || !country || !/^[A-Z]{2}$/.test(country)) return [];
  const order = (env.AI_PROVIDER_ORDER ?? '').split(',').map((value) => value.trim());
  if (!order.length || order.some((value) => value !== 'openai' && value !== 'anthropic')
    || new Set(order).size !== order.length) return [];
  return (order as Provider[]).filter((provider) => {
    const keys = envKeys[provider];
    if (env[keys.account] !== 'true' || !env[keys.key]?.trim()) return false;
    const allowed = (env[keys.countries] ?? '').split(',').map((value) => value.trim());
    const supported = provider === 'openai' ? OPENAI_COUNTRIES : ANTHROPIC_COUNTRIES;
    return allowed.length > 0 && allowed.every((value) => /^[A-Z]{2}$/.test(value) && supported.has(value))
      && allowed.includes(country);
  });
}

export async function requestProviders(): Promise<Provider[]> {
  // Only read Vercel's platform-provided request geography while running on that platform.
  // A local/rehosted caller cannot enable cloud AI just by sending an x-vercel-ip-country header.
  if (process.env.VERCEL !== '1' || !['production', 'preview'].includes(process.env.VERCEL_ENV ?? '')) return [];
  try { return permittedProviders(process.env, (await headers()).get('x-vercel-ip-country')); }
  catch { return []; } // No request context, including standalone calls/builds, is not eligible.
}
