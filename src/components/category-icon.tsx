import type { FC } from 'react';
import { ShoppingCart, Lightbulb, Ticket, Car, Home, HeartPulse, MoreHorizontal, type LucideProps } from 'lucide-react';

interface CategoryIconProps extends LucideProps {
  category: string;
}

const iconMap: Record<string, React.ElementType<LucideProps>> = {
  Groceries: ShoppingCart,
  Utilities: Lightbulb,
  Entertainment: Ticket,
  Transport: Car,
  Housing: Home,
  Health: HeartPulse,
  Other: MoreHorizontal,
};

export const CategoryIcon: FC<CategoryIconProps> = ({ category, ...props }) => {
  const Icon = iconMap[category] || MoreHorizontal;
  return <Icon {...props} />;
};
