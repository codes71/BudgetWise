export interface Transaction {
  _id: string;
  id?: string;
  userId: string;
  date: string;
  description: string;
  amount: number;
  category: string;
  type: 'income' | 'expense';
  clientId?: string;
  source?: 'voice' | 'manual' | 'web';
  transcript?: string;
  createdAt?: Date | string;
  updatedAt?: Date | string;
}
export interface Category {
  id: string;
  name: string;
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
  isGuest?: boolean;
  createdAt?: string; // ISO date string
}