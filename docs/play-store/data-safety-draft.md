# Data safety working inventory

This is a draft for the Console form, grounded in the implementation. It is not a submitted or approved declaration. The owner must confirm provider contracts, operational logging/retention and the precise choices offered by the current form.

| Data | Observed collection and purpose | Choice and remaining verification |
|---|---|---|
| Email, account ID, name | Account creation/authentication and cross-device account management. Name can be derived from email. | Account credentials are required for account-backed functions. Classify personal-information fields accurately. |
| Expenses, income, budgets, plans, merchant and receipt details | Stored in Firestore for the synced ledger, budgeting and display. | Assess financial information and purchase-history categories. Sync is not local-only or ephemeral. |
| Receipt/payment photos | Chosen images go to the server and Anthropic/OpenAI for draft extraction; the scan route does not save the original to the account database. | Optional scanning. Photos and sensitive information shown in them leave the device. Do not claim ephemeral processing without verified provider retention. |
| Voice-derived text | Audio is recognized on-device; recognized words/categories can go to AI for parsing. Saved entries may include recognized text. | Optional voice/cloud features. Raw audio upload was not found in this path. Assess text according to its content. |
| AI suggestions/categorization on the web | Summaries, budget goals, descriptions and categories are sent when the user requests those features. | Describe the shared service in the policy; do not assume every Android request sends the whole ledger. |
| Security/diagnostics and network metadata | API abuse counters can include IP addresses/account IDs and request counts; logs include errors and provider timing/usage. | Review actual Vercel/AI/model-host practices and current form categories. Rate-limit expiry is not automatic record deletion. |

The source does not bundle Android advertising, analytics, Crashlytics or Sentry SDK integrations. That bounded finding is not a guarantee that no network/provider metadata is collected.

Production release API traffic uses HTTPS. Microphone/camera permissions are feature-specific. First-use AI disclosure identifies the sent content, purpose and recipients before an affirmative choice, with manual entry available.

Provider transfers may qualify for Google's service-provider sharing exception only if the actual relationship meets its conditions. Source dependencies alone cannot establish the exception. Do not mark "no sharing" merely because a provider hosts the processing.

Deletion is password-confirmed, removes active cloud account records and owned financial descendants, invalidates ordinary access, and clears the requesting Android account locally after confirmation. Shared security records and provider operational logs have separately disclosed limits. Confirm backup and log retention before publishing a final retention statement.

Sources: [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469), [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311), [account deletion](https://support.google.com/googleplay/android-developer/answer/13327111).
