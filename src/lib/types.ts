export interface Transaction {
  _id: string;
  id: string;
  date: string;
  description: string;
  amount: number;
  category: string;
  type: 'income' | 'expense';
}

export interface Budget {
  _id: string;
  category: string;
  limit: number;
}