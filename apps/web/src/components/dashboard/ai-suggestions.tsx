'use client';

import { useState, type ReactNode } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger, DialogFooter, DialogDescription } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Lightbulb } from 'lucide-react';
import { Skeleton } from '@/components/ui/skeleton';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { generateSuggestions } from '@/app/actions';
import { useAuth } from '@/context/auth-context';

interface AiSuggestionsProps {
  children: ReactNode;
}

export function AiSuggestions({ children }: AiSuggestionsProps) {
  const [open, setOpen] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const { transactions, budgets } = useAuth();


  const handleGetSuggestions = async () => {
    setIsLoading(true);
    setSuggestions([]);
    setError(null);
    try {
      const result = await generateSuggestions(transactions, budgets);
      if (result.error) {
         setError(result.error);
      } else if (result.suggestions) {
        setSuggestions(result.suggestions);
      }
    } catch (e) {
      setError('An unexpected error occurred.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>Spending Suggestions</DialogTitle>
          <DialogDescription>
            Compare this month’s saved spending with your budgets. These observations are informational, not financial advice.
          </DialogDescription>
        </DialogHeader>
        <div className="py-4">
          {isLoading ? (
            <div className="space-y-2">
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-5/6" />
            </div>
          ) : error ? (
            <Alert variant="destructive">
              <AlertTitle>Error</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          ) : suggestions.length > 0 ? (
            <ul className="space-y-3">
              {suggestions.map((suggestion, index) => (
                <li key={index} className="flex items-start gap-3">
                  <Lightbulb className="h-5 w-5 mt-0.5 text-primary flex-shrink-0" />
                  <span className="text-sm">{suggestion}</span>
                </li>
              ))}
            </ul>
          ) : (
            <div className="text-center text-muted-foreground py-8">
              <p>Check your recorded spending against your category budgets. No AI provider receives this comparison.</p>
            </div>
          )}
        </div>
        <DialogFooter>
          <Button onClick={handleGetSuggestions} disabled={isLoading || !transactions.length || !budgets.length}>
            {isLoading ? 'Comparing...' : suggestions.length > 0 ? 'Compare Again' : 'Get Suggestions'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
