import type { NextConfig } from 'next';
import path from 'path';

const nextConfig: NextConfig = {
  async headers() {
    return [{
      source: '/updates/:path*',
      headers: [{ key: 'Cache-Control', value: 'no-store' }],
    }];
  },
  /* config options here */
  typescript: {
    ignoreBuildErrors: true,
  },
  eslint: {
    ignoreDuringBuilds: true,
  },
  images: {
    remotePatterns: [
      {
        protocol: 'https',
        hostname: 'placehold.co',
        port: '',
        pathname: '/**',
      },
    ],
  },
  // This alias setup is compatible with both Webpack and Turbopack.
  // It ensures that path aliases like `@/` work correctly in development and production.
  webpack: (config, { isServer }) => {
    config.resolve.alias['@'] = path.resolve(__dirname, 'src');
    
    return config;
  },
};

export default nextConfig;
