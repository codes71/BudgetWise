import type { ImgHTMLAttributes } from 'react';

/** Approved square mark; callers keep their existing dimensions and surrounding layout. */
export function PocketPeekLogo({ alt = 'Pocket Peek', ...props }: Omit<ImgHTMLAttributes<HTMLImageElement>, 'src'>) {
  return <img src="/logo.png" alt={alt} {...props} />;
}
