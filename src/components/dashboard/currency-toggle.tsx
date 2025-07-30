'use client';

import { Coins } from 'lucide-react';
import { useAuth } from '@/context/auth-context';

import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  DropdownMenuLabel,
  DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';

export function CurrencyToggle() {
  const { currency, setCurrency } = useAuth();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon">
          <Coins className="h-[1.2rem] w-[1.2rem]" />
          <span className="sr-only">Toggle currency</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>Currency</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={() => setCurrency('INR')}>
          INR (₹)
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => setCurrency('MMK')}>
          MMK (K)
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
