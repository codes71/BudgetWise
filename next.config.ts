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
  // This alias setup is compatible with both Webpack and Turbopack.
  // It ensures that path aliases like `@/` work correctly in development and production.
  webpack: (config, { isServer }) => {
    config.resolve.alias['@'] = path.resolve(__dirname, 'src');
    
    // The following externals are only needed for the production build (isServer)
    // to prevent certain packages from being bundled on the server.
    if (isServer && process.env.NODE_ENV === 'production') {
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
