import type { NextConfig } from 'next';
import path from 'path';

const nextConfig: NextConfig = {
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
  webpack: process.env.NODE_ENV === 'development' ? undefined : (config, { isServer }) => {
    config.resolve.alias['@'] = path.resolve(__dirname, 'src');
    if (isServer) {
      config.externals = config.externals || [];
      config.externals.push(
        '@genkit-ai/googleai',
        'genkit',
        'handlebars',
        'dotprompt'
      );
    }
    return config;
  },
};

export default nextConfig;
