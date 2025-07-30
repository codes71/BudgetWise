'use client';

import { useState, useRef, type ReactNode } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger, DialogFooter, DialogDescription } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useToast } from '@/hooks/use-toast';
import type { Transaction, Budget } from '@/lib/types';

interface DataImporterProps {
  onDataImported: (transactions: Transaction[], budgets: Budget[]) => void;
  children: ReactNode;
}

export function DataImporter({ onDataImported, children }: DataImporterProps) {
  const [open, setOpen] = useState(false);
  const [isParsing, setIsParsing] = useState(false);
  const transactionFileRef = useRef<HTMLInputElement>(null);
  const budgetFileRef = useRef<HTMLInputElement>(null);
  const { toast } = useToast();

  const parseCSV = <T extends object>(file: File, expectedHeaders: (keyof T)[]): Promise<T[]> => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = (event) => {
        try {
          const csv = event.target?.result as string;
          const lines = csv.split(/[\r\n]+/).filter(line => line.trim() !== '');
          if (lines.length < 2) throw new Error('CSV file is empty or has only a header.');
          
          const header = lines[0].split(',').map(h => h.trim());
          const isValidHeader = expectedHeaders.every(h => header.includes(h as string));
          if (!isValidHeader) throw new Error(`Invalid headers. Expected: ${expectedHeaders.join(', ')}`);

          const data = lines.slice(1).map((line, index) => {
            const values = line.split(',');
            const entry = {} as T;
            header.forEach((h, i) => {
              const key = h as keyof T;
              let value: any = values[i]?.trim();
              if (key === 'amount' || key === 'limit') value = parseFloat(value);
              if (key === 'id') value = value || `imported-${Date.now()}-${index}`;
              entry[key] = value;
            });
            return entry;
          });
          resolve(data);
        } catch (error) {
          reject(error);
        }
      };
      reader.onerror = () => reject(new Error('Failed to read file.'));
      reader.readAsText(file);
    });
  };

  const handleImport = async () => {
    setIsParsing(true);
    try {
      let importedTransactions: Transaction[] = [];
      let importedBudgets: Budget[] = [];

      const transactionFile = transactionFileRef.current?.files?.[0];
      const budgetFile = budgetFileRef.current?.files?.[0];

      if (transactionFile) {
        importedTransactions = await parseCSV<Transaction>(transactionFile, ['id', 'date', 'description', 'amount', 'category', 'type']);
      }
      if (budgetFile) {
        importedBudgets = await parseCSV<Budget>(budgetFile, ['category', 'limit']);
      }

      onDataImported(importedTransactions, importedBudgets);
      
      toast({
        title: 'Import Successful',
        description: `Imported ${importedTransactions.length} transactions and ${importedBudgets.length} budget items.`,
      });
      setOpen(false);
    } catch (error: any) {
      console.error(error);
      toast({
        variant: 'destructive',
        title: 'Import Failed',
        description: error.message || 'An unknown error occurred.',
      });
    } finally {
      setIsParsing(false);
    }
  };
  
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Import Data</DialogTitle>
          <DialogDescription>
            Upload CSV files for transactions and budgets. Ensure they have the correct headers.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-4 py-4">
          <div className="space-y-2">
            <Label htmlFor="transactions-file">Transactions (CSV)</Label>
            <Input id="transactions-file" type="file" accept=".csv" ref={transactionFileRef} />
            <p className="text-xs text-muted-foreground">Headers: id,date,description,amount,category,type</p>
          </div>
          <div className="space-y-2">
            <Label htmlFor="budgets-file">Budgets (CSV)</Label>
            <Input id="budgets-file" type="file" accept=".csv" ref={budgetFileRef} />
             <p className="text-xs text-muted-foreground">Headers: category,limit</p>
          </div>
        </div>
        <DialogFooter>
          <Button onClick={handleImport} disabled={isParsing}>
            {isParsing ? 'Importing...' : 'Import'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
