# Cloud AI eligibility and bounded spending helpers

Source preparation reviewed 8 November 2026. Not deployed. No live provider key, account, billing, retention control or deployment variable was read or changed.

## Closed by default

`src/ai/generate.ts` resolves `src/ai/provider-policy.ts` for every cloud request. A key alone never enables a provider. The server must have all of the following for that provider:

- Its name in `AI_PROVIDER_ORDER`, a comma-separated order containing only `openai` and/or `anthropic`, without duplicates or empty elements.
- `AI_OPENAI_ACCOUNT_ELIGIBLE=true` or `AI_ANTHROPIC_ACCOUNT_ELIGIBLE=true` after the owner verifies the actual account/customer/entity/control/billing/model terms. This is an operator assertion, not a legal determination made by the code.
- `AI_OPENAI_ALLOWED_COUNTRIES` or `AI_ANTHROPIC_ALLOWED_COUNTRIES`, a comma-separated, uppercase ISO country scope that is a subset of the reviewed provider list in source. Wildcards, unknown codes and unsupported entries fail closed for that provider.
- Its existing API key, configured separately under a future explicitly authorized rollout.
- A permitted request country supplied by the Vercel platform while `VERCEL=1` and `VERCEL_ENV` is `production` or `preview`.

No defaults select a provider or country. Local/development/rehosted servers and standalone calls have no trusted country adapter and cannot send cloud requests. Tests use isolated synthetic configuration and mocked clients, not a runtime production bypass. The policy contains a conservative country snapshot; Ukraine is excluded because country-only evidence cannot distinguish provider-excluded subregions. This intentionally declines service in that country until a suitably precise policy can be reviewed.

The request-country check is only one control. It does not prove residence, developer eligibility, incorporation, headquarters or beneficial control. It must never be used to justify a VPN, proxy or US hosting workaround. Proxy/network routing can impair geolocation; the owner must verify the trusted ingress path before enabling any provider. Vercel documents `x-vercel-ip-country` as the country of the requester's public IP and overwrites forwarded IP values to prevent spoofing. No client body field, language, timezone or caller-provided country override can select a provider. [Vercel request headers](https://vercel.com/docs/headers/request-headers#x-vercel-ip-country).

Missing policy, unknown country, unsupported location and unconfirmed accounts produce a content-free `AiUnavailableError`; mobile scan/voice answer `503 ai_unavailable` with manual-entry guidance. Web screenshots offer manual entry, category suggestions leave the manual category picker in use, and manual ledger/budget functions do not need a provider. Only the permitted ordered providers can receive a non-refusal fallback. A refusal or caller cancellation is final. Provider SDK errors are sanitized before logging/propagating.

## Current primary provider evidence

- Anthropic's [API country list](https://platform.claude.com/docs/en/api/supported-regions) does not include Myanmar. Its [Supported Regions Policy](https://www.anthropic.com/legal/supported-regions), effective 12 November 2026, covers physical location and entity incorporation/headquarters/majority ownership or control. This is a forthcoming stated policy, not proof of a prior breach. Neither a server's country nor a provider API key establishes permission.
- OpenAI's [API country list](https://help.openai.com/en/articles/5347006-openai-api-supported-countries-and-territories) includes Myanmar. Therefore the source can permit an explicitly confirmed and configured OpenAI account for `MM`, while Anthropic cannot be selected for `MM`. That list alone does not establish the actual owner's account, billing or model access.
- OpenAI's [API data controls](https://developers.openai.com/api/docs/guides/your-data) distinguish response state from safety logs. `store:false` is not zero retention. Standard safety retention can be up to 30 days, with legal/harm exceptions and image-input rules. No special account retention agreement was verified.
- Anthropic's [commercial retention article](https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-my-organization-s-data) describes normal API deletion within 30 days and exceptions, including policy/law and covered-model safety retention. Do not promise the normal period applies universally or that deletion in Pocket Peek deletes provider safety records.

Before deployment, the owner must confirm account eligibility, acceptable downstream audience, models, billing, provider controls and the actual hosting trust boundary. Then independently authorize configuration/deployment and test with synthetic data. This document authorizes none of those actions.

## Spending suggestions

The web action now compares the signed-in account's persisted current-month expense rows with its persisted category budgets, using Yangon date boundaries. It ignores the caller's financial arrays. Generated prose and free-form financial instructions are no longer accepted by this feature. Fixed templates describe recorded totals, budget differences and an optional review step. They do not recommend investments, loans, tax positions, insurance, trading or financial products. Future and previous-month rows/income are excluded, zero budgets remain meaningful, signed discounts are included, and overflow/duplicate budgets/unbounded inputs fail instead of returning misleading figures.

The existing dialog and button layout remain. Its copy calls the output informational budget observations and states the comparison is not sent to an AI provider. This removes the unsupported professional-adviser claim, without claiming a financial-policy exemption for the app. Native extraction/review-before-save remains a separate feature.

## Known external facts still unverified

Actual provider account holder/customer, physical/entity/control facts, model access, billing permissions, account-specific retention/training settings, current Vercel plan/products/preferences and trust-boundary setup were not inspected. No reviewer credentials or real financial records were accessed. These are gates to enabling cloud AI, not proof of an existing policy violation.


## Native copy and consent preparation

English and Burmese Settings/disclosure copy now describes the configured provider choice without claiming Claude-first routing. The existing first-use bodies also explain normal safety retention with longer exceptions. The feature-specific keys are advanced from `scan_v1`/`voice_v1` to `scan_v2`/`voice_v2`, so an earlier acceptance does not silently skip this material retention clarification. Old preference values, accounts, models and ledger records are preserved. No recipients or collection categories are added; the strict provider policy narrows the existing named-provider scope. A regression test verifies content/capture stays unmounted until the updated choice and only the chosen feature becomes accepted.

Better Design principles, review rules and comprehension tools were attempted and returned HTTP 402 quota exhausted. No styling/layout changes or external visual approval is claimed. Rendered spacing must be checked from actual UI evidence by the final integration pass.


## Native unavailable and declined states

Scan and voice map `ai_unavailable` to a dedicated manual-entry state. Provider refusal codes (`scan_declined`, `parse_declined`) have a separate, neutral declined state. Both reuse existing problem-card components and localized English/Burmese copy. Manual entry is the primary action, with Retake for scans or Close for voice as the secondary action. They do not falsely describe a policy/eligibility response as a network failure. Retrying the same refused or unavailable content is also blocked in the ViewModels, including a retained callback. No claim is made about whether the service or a provider previously received a request.
