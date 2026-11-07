import type { Metadata } from 'next';
import Link from 'next/link';
import { PublicPolicyLayout } from '@/components/legal/public-policy-layout';

export const metadata: Metadata = { title: 'Privacy policy | Pocket Peek' };

export default function PrivacyPage() {
  return (
    <PublicPolicyLayout title="Privacy policy">
      <article className="space-y-8 leading-relaxed [&_a]:underline [&_a]:underline-offset-4 [&_h2]:mb-3 [&_h2]:text-xl [&_h2]:font-semibold [&_p+p]:mt-3 [&_ul]:list-disc [&_ul]:space-y-2 [&_ul]:pl-6">
        <div>
          <p className="mb-3 text-sm text-muted-foreground">Last updated: 8 November 2026</p>
          <p>Pocket Peek is published by thurashark. This policy explains how the Android app and Pocket Peek website handle your information. For privacy questions, contact <a href="mailto:thurashark@gmail.com">thurashark@gmail.com</a>.</p>
        </div>
        <section aria-labelledby="privacy-account">
          <h2 id="privacy-account">Account and financial information</h2>
          <p>We use your email address and a password hash to manage your account. If you add them, we also store your name, phone number and profile-photo address.</p>
          <p>Pocket Peek stores the transactions, budgets, monthly plans and categories you save. Entries may include amounts, dates, descriptions, merchant names, receipt items, payment references and recognized text from voice entry. We use this information to show your finances and synchronize your account across devices.</p>
        </section>
        <section aria-labelledby="privacy-ai">
          <h2 id="privacy-ai">Receipt scans and voice entry</h2>
          <p>When you choose to scan a receipt or payment screenshot, the image is sent to the Pocket Peek service and an AI provider to extract draft entries. You review the result before saving it. This scan flow does not save the image in the account database.</p>
          <p>Speech recognition runs on your Android device. Microphone audio is not sent to the Pocket Peek voice-parsing API. The recognized words and relevant account categories are sent to the service to interpret an entry or spending question. A saved entry may include its recognized text.</p>
          <p>Website spending suggestions compare your saved spending and budgets in Pocket Peek without sending that comparison to an AI provider. Optional AI categorization sends the entry description and relevant categories. Android scan and voice requests use the chosen image or recognized words plus category context.</p>
          <p>Cloud AI is available only when Pocket Peek has explicitly enabled an eligible provider for the request’s country. That provider can be OpenAI or Anthropic Claude. If no provider is permitted or availability cannot be established, cloud assistance is unavailable and manual entry remains available. Providers may retain inputs and outputs for security, abuse prevention or legal reasons even when Pocket Peek does not save an image or disables response storage. Standard API safety retention can be up to 30 days, with longer exceptions under the provider’s terms and account controls. Their processing and retention terms also apply: <a href="https://www.anthropic.com/legal/commercial-terms">Anthropic commercial terms</a> and <a href="https://openai.com/enterprise-privacy/">OpenAI business and API privacy</a>. Manual entry is available if you prefer not to use these features.</p>
        </section>
        <section aria-labelledby="privacy-device">
          <h2 id="privacy-device">Information on your device</h2>
          <p>The Android app keeps an offline copy of account data and pending changes. Saved sign-in information is encrypted using Android Keystore. Camera and microphone permissions support scanning and voice entry. Notification permission supports reminders and download-progress notifications.</p>
          <p>Optional speech models are downloaded from Hugging Face and stored on your device. Model-download and other network providers receive the connection information needed to deliver their services.</p>
        </section>
        <section aria-labelledby="privacy-providers">
          <h2 id="privacy-providers">Service providers and security</h2>
          <p>Pocket Peek uses MongoDB Atlas for active account and financial records, and Vercel to host the website and API. Network requests to the production service use HTTPS. Service providers process information to operate these features and may process it outside your country.</p>
          <p>Hosting services receive your IP address and may derive approximate location, such as city and country. The service uses the country associated with a request’s IP address to restrict AI provider availability; this does not request your device’s GPS location. We use request counts and service logs to prevent abuse and diagnose failures. These can include IP addresses, account identifiers, error categories or codes and AI request timing or usage. Application diagnostics exclude receipt content, recognized speech, passwords and raw provider error messages. Shared network rate-limit counters are not individual financial records. A rate-limit window can expire without its counter being deleted. Provider-managed operational and security logs follow the relevant provider&apos;s retention terms.</p>
          <p>The Android release does not include advertising or analytics SDKs. Pocket Peek does not sell your financial records.</p>
        </section>
        <section aria-labelledby="privacy-delete">
          <h2 id="privacy-delete">Retention and deletion</h2>
          <p>Your account and synced financial records are kept while you use the account. You can request permanent deletion in Android Settings or on the <Link href="/delete-account">Delete account page</Link>, without reinstalling the app. You must confirm the request with your password.</p>
          <p>Completed deletion removes the account, its synced transactions, budgets, monthly plans, personal categories and account-specific usage counters from the active Pocket Peek database. If a request is interrupted, return to the deletion flow to retry until it confirms completion.</p>
          <p>Deletion through the Android app also removes that account&apos;s local cache and pending changes on that device after the server confirms completion. Other saved accounts and downloaded speech models are kept. Offline copies on other devices can remain until that account is removed or the app&apos;s storage is cleared on those devices. Information already processed by service providers, including their security logs, is subject to their retention terms.</p>
          <p>Recovery copies may remain in the previous Google Cloud Firestore database and encrypted migration backups. Your account deletion request also records the need to clean up those copies. Their removal remains pending until separately verified; confirmation that the active account is deleted does not mean the recovery copies have been erased. Contact us for the status of recovery-copy cleanup.</p>
          <p>A minimal record of the deleted account&apos;s hashed identifier and deletion status is retained separately to prevent old copies from restoring a deleted account. It contains no email address, password or financial records.</p>
          <p>For questions or help requesting deletion, email <a href="mailto:thurashark@gmail.com">thurashark@gmail.com</a> from your registered address. We may need to verify that you own the account before acting.</p>
        </section>
      </article>
    </PublicPolicyLayout>
  );
}
