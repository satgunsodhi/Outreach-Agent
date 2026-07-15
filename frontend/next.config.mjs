/** @type {import('next').NextConfig} */

/**
 * BACKEND_URL points to the Spring Boot API server.
 *
 * - Development:  defaults to http://localhost:8080 (set in .env.local if different)
 * - Production:   set BACKEND_URL in your hosting environment (e.g. Railway, Render)
 *
 * By always proxying /api/* here, the Next.js app never needs to talk to the
 * database directly — the Spring Boot backend is the single source of truth.
 */
const backendUrl = process.env.BACKEND_URL || 'http://localhost:8080';

const nextConfig = {
  async rewrites() {
    return {
      beforeFiles: [
        {
          source: '/api/:path*',
          destination: `${backendUrl}/api/:path*`,
        },
      ],
    };
  },
};

export default nextConfig;
