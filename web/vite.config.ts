import react from '@vitejs/plugin-react';
// From vitest/config rather than vite: the `test` block below is Vitest's, and importing
// defineConfig from vite leaves it untyped -- which is how a typo in a test setting becomes a
// suite that silently does not run what you think it runs.
import { defineConfig } from 'vitest/config';

/**
 * The frontend is a static build (docs/frontend.md). There is no server-side rendering and no
 * Node process in a request path: what ships is files, served from the edge, so a learner's
 * page loads when none of our services are running — which is what T-3.10 asserts about
 * playback and would be untrue the moment a page needed us to render it.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // The browser talks to ONE origin, in development as in production. In production that is
    // the gateway, which serves the app and relays to the services behind it (T-9.11, T-10.2);
    // here the dev server stands in for it. The alternative -- calling http://localhost:8082
    // directly -- would mean opening CORS on every service to a development origin, which is a
    // production-shaped hole cut for a development convenience.
    proxy: {
      '/api': { target: process.env.IDENTITY_URL ?? 'http://localhost:8082', changeOrigin: true },
      '/v3/api-docs': { target: process.env.IDENTITY_URL ?? 'http://localhost:8082', changeOrigin: true },
    },
  },
  build: {
    // A budget rather than a warning nobody reads. The learner app is used on whatever device a
    // person happens to have; the admin console is not. Route-level splitting keeps the admin
    // tree out of the learner's download, and this fails loudly when something merges them.
    chunkSizeWarningLimit: 300,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
