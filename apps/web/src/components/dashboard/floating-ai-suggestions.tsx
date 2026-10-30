'use client';

import { Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { AiSuggestions } from './ai-suggestions';
import { useAuth } from '@/context/auth-context';

export function FloatingAiSuggestions() {
  const { user } = useAuth();
  
  if (!user) return null;

  return (
    <div className="fixed bottom-6 right-6 z-50">
      <AiSuggestions>
        <Button size="icon" className="rounded-full h-14 w-14 shadow-lg">
          <Sparkles className="h-6 w-6" />
          <span className="sr-only">AI Suggestions</span>
        </Button>
      </AiSuggestions>
    </div>
  );
}
