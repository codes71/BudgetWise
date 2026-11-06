import type { Metadata } from 'next';
import { DeleteAccountForm } from '@/components/account/delete-account-form';
import { PublicPolicyLayout } from '@/components/legal/public-policy-layout';

export const metadata: Metadata = { title: 'Delete account | Pocket Peek' };

export default function DeleteAccountPage() {
  return (
    <PublicPolicyLayout title="Delete your Pocket Peek account">
      <DeleteAccountForm />
    </PublicPolicyLayout>
  );
}
