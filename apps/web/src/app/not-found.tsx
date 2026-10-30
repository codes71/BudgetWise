import Link from 'next/link';

export default function NotFound() {
  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100 dark:bg-gray-900 text-gray-900 dark:text-gray-100">
      <h1 className="text-6xl font-bold">404</h1>
      <p className="text-xl mt-4">Page Not Found</p>
      <Link href="/" className="mt-8 px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700">
        Go to Home
      </Link>
    </div>
  );
}
