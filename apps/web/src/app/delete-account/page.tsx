import type { Metadata } from 'next';
import { DeleteAccountForm } from '@/components/account/delete-account-form';
import { PublicPolicyLayout } from '@/components/legal/public-policy-layout';

export const metadata: Metadata = { title: 'Delete account | BudgetWise' };

export default function DeleteAccountPage() {
  return (
    <PublicPolicyLayout title="Delete your BudgetWise account">
      <DeleteAccountForm />
    </PublicPolicyLayout>
  );
}
