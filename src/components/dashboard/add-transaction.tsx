'use client';

import { useState, type ReactNode, useEffect, useCallback, useRef } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { CalendarIcon } from 'lucide-react';
import { format } from 'date-fns';
import { debounce } from 'lodash';

import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  DialogFooter,
  DialogDescription,
} from '@/components/ui/dialog';
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { Calendar } from '@/components/ui/calendar';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';
import type { Transaction } from '@/lib/types';
import { getCategorySuggestion, processScreenshot } from '@/app/actions';
import { useAuth } from '@/context/auth-context'; // New import

const transactionSchema = z.object({
  description: z.string().min(1, 'Description is required'),
  amount: z.coerce.number().positive('Amount must be positive'),
  category: z.string().min(1, 'Category is required'),
  type: z.enum(['income', 'expense']),
  date: z.date(),
});

type TransactionFormData = z.infer<typeof transactionSchema>;

interface AddTransactionProps {
  onTransactionAdded: (transaction: Omit<Transaction, 'id' | '_id'>) => void;
  children: ReactNode;
}



export function AddTransaction({ onTransactionAdded, children }: AddTransactionProps) {
  const [open, setOpen] = useState(false);
  const { toast } = useToast();
  const { categories } = useAuth(); // Get categories from context
  const [isProcessingImage, setIsProcessingImage] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const form = useForm<TransactionFormData>({
    resolver: zodResolver(transactionSchema),
    defaultValues: {
      description: '',
      amount: 0,
      type: 'expense',
      date: new Date(),
      category: ''
    },
  });

  const transactionType = useWatch({ control: form.control, name: 'type' });
  const description = useWatch({ control: form.control, name: 'description' });

  const handleFileChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;

    setIsProcessingImage(true);
    const reader = new FileReader();
    reader.onloadend = async () => {
      const base64Image = reader.result as string;
      try {
        const result = await processScreenshot(base64Image);
        if (result.success && result.data) {
          const { amount, date, description: extractedDescription, merchant, transactionId, categoryHint } = result.data; // Added merchant, transactionId, categoryHint
          if (amount) form.setValue('amount', amount, { shouldValidate: true });
          if (date) form.setValue('date', new Date(date), { shouldValidate: true });
          
          let newDescription = '';
          if (merchant) newDescription += merchant;
          if (transactionId) newDescription += ` (ID: ${transactionId})`;
          if (extractedDescription) newDescription += ` ${extractedDescription}`;

          if (newDescription) form.setValue('description', newDescription.trim(), { shouldValidate: true });
          
          if (categoryHint && categories.includes(categoryHint)) { // Use categories from context
            form.setValue('category', categoryHint, { shouldValidate: true });
          }
          toast({
            title: 'Screenshot Processed',
            description: 'Transaction details extracted successfully.',
          });
        } else {
          toast({
            variant: 'destructive',
            title: 'Processing Failed',
            description: result.error || 'Could not extract details from screenshot.',
          });
        }
      } catch (error) {
        toast({
          variant: 'destructive',
          title: 'Processing Error',
          description: 'An unexpected error occurred during processing.',
        });
      } finally {
        setIsProcessingImage(false);
        if (fileInputRef.current) {
          fileInputRef.current.value = ''; // Clear the input
        }
      }
    };
    reader.readAsDataURL(file);
  };

  const debouncedCategoryFetch = useCallback(
    debounce(async (desc: string) => {
      if (desc && transactionType === 'expense') {
        const { category } = await getCategorySuggestion(desc, categories); // Use categories from context
        if (category && categories.includes(category)) { // Use categories from context
          form.setValue('category', category, { shouldValidate: true });
        }
      }
    }, 500),
    [transactionType, form.setValue, categories] // Add categories to dependency array
  );

  useEffect(() => {
    debouncedCategoryFetch(description);
    return () => debouncedCategoryFetch.cancel();
  }, [description, debouncedCategoryFetch]);
  
  useEffect(() => {
    if (transactionType === 'income') {
      form.setValue('category', 'Income');
    } else {
      form.setValue('category', '');
    }
  }, [transactionType, form]);

  const onSubmit = (data: TransactionFormData) => {
    const newTransaction = {
      ...data,
      date: format(data.date, 'yyyy-MM-dd'),
    };
    onTransactionAdded(newTransaction);
    toast({
      title: 'Transaction Added',
      description: `${data.description} for $${data.amount} has been added.`,
    });
    form.reset({
      description: '',
      amount: 0,
      type: 'expense',
      date: new Date(),
      category: ''
    });
    setOpen(false);
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>Add Transaction</DialogTitle>
          <DialogDescription>
            Enter the details of your new transaction below.
          </DialogDescription>
        </DialogHeader>
        <div className="flex justify-center mb-4">
          <input
            type="file"
            accept="image/*"
            ref={fileInputRef}
            onChange={handleFileChange}
            style={{ display: 'none' }}
          />
          <Button
            type="button"
            variant="outline"
            onClick={() => fileInputRef.current?.click()}
            disabled={isProcessingImage}
          >
            {isProcessingImage ? 'Processing...' : 'Upload Screenshot'}
          </Button>
        </div>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4 py-4">
             <div className="grid grid-cols-2 gap-4">
                <FormField
                    control={form.control}
                    name="description"
                    render={({ field }) => (
                        <FormItem className="col-span-2">
                        <FormLabel>Description</FormLabel>
                        <FormControl>
                            <Input placeholder="e.g., Coffee shop, Paycheck" {...field} />
                        </FormControl>
                        <FormMessage />
                        </FormItem>
                    )}
                />
                <FormField
                    control={form.control}
                    name="amount"
                    render={({ field }) => (
                        <FormItem>
                        <FormLabel>Amount</FormLabel>
                        <FormControl>
                            <Input type="number" placeholder="0.00" {...field} />
                        </FormControl>
                        <FormMessage />
                        </FormItem>
                    )}
                />
                 <FormField
                    control={form.control}
                    name="type"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Type</FormLabel>
                        <Select onValueChange={field.onChange} defaultValue={field.value}>
                           <FormControl>
                            <SelectTrigger>
                              <SelectValue placeholder="Select a type" />
                            </SelectTrigger>
                          </FormControl>
                          <SelectContent>
                            <SelectItem value="expense">Expense</SelectItem>
                            <SelectItem value="income">Income</SelectItem>
                          </SelectContent>
                        </Select>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
            </div>
            {transactionType === 'expense' && (
                <FormField
                control={form.control}
                name="category"
                render={({ field }) => (
                    <FormItem>
                    <FormLabel>Category</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value} >
                        <FormControl>
                        <SelectTrigger>
                            <SelectValue placeholder="Select a category" />
                        </SelectTrigger>
                        </FormControl>
                        <SelectContent>
                        {categories.map(category => (
                            <SelectItem key={category} value={category}>
                            {category}
                            </SelectItem>
                        ))}
                        </SelectContent>
                    </Select>
                    <FormMessage />
                    </FormItem>
                )}
                />
            )}
            <FormField
              control={form.control}
              name="date"
              render={({ field }) => (
                <FormItem className="flex flex-col">
                  <FormLabel>Date</FormLabel>
                  <Popover>
                    <PopoverTrigger asChild>
                      <FormControl>
                        <Button
                          variant={'outline'}
                          className={cn(
                            'pl-3 text-left font-normal',
                            !field.value && 'text-muted-foreground'
                          )}
                        >
                          {field.value ? (
                            format(field.value, 'PPP')
                          ) : (
                            <span>Pick a date</span>
                          )}
                          <CalendarIcon className="ml-auto h-4 w-4 opacity-50" />
                        </Button>
                      </FormControl>
                    </PopoverTrigger>
                    <PopoverContent className="w-auto p-0" align="start">
                      <Calendar
                        mode="single"
                        selected={field.value}
                        onSelect={field.onChange}
                        initialFocus
                      />
                    </PopoverContent>
                  </Popover>
                  <FormMessage />
                </FormItem>
              )}
            />
            <DialogFooter>
              <Button type="submit">Add Transaction</Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
