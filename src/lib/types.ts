export interface Transaction {
  _id: string;
  id: string;
  userId: string;
  date: string;
  description: string;
  amount: number;
  category: string;
  type: 'income' | 'expense';
}

export interface Budget {
  _id: string;
  userId: string;
  category: string;
  limit: number;
}

export interface UserPayload {
  userId: string;
  email: string;
  fullName?: string | null;
  phoneNumber?: string | null;  
  profilePhotoUrl?: string | null;
}