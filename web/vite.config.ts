import { resolve } from 'node:path';
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
    // ORDER MATTERS HERE, and it is the one thing about this block worth reading twice.
    //
    // Two services answer under `/api/v1/me`: identity owns `/me` and `/me/reach/...`, and
    // streaming owns `/me/nodes/{id}/playback-token` (T-3.4). A prefix router cannot split those
    // by prefix alone, so the more specific rules are listed FIRST -- Vite tests proxy keys in
    // insertion order and takes the first match. Put `/api` first and every playback token
    // request goes to identity and 404s, which looks exactly like an entitlement refusal.
    //
    // In production the gateway does this routing (T-10.2) and it will need the same care; this
    // block is where the collision is documented until then.
    proxy: {
      '/api/v1/me/nodes': { target: process.env.STREAMING_URL ?? 'http://localhost:8083', changeOrigin: true },
      '/api/v1/videos': { target: process.env.STREAMING_URL ?? 'http://localhost:8083', changeOrigin: true },
      '/api': { target: process.env.IDENTITY_URL ?? 'http://localhost:8082', changeOrigin: true },
      '/v3/api-docs': { target: process.env.IDENTITY_URL ?? 'http://localhost:8082', changeOrigin: true },
    },
  },
  build: {
    rollupOptions: {
      // TWO ENTRIES, ONE BUILD (ADR-0110). `player.html` is the document that runs inside the
      // iframe a customer embeds, and it is the same page our own learner app embeds -- there is
      // no in-process shortcut for us, because a private variant is one nobody would notice
      // breaking. One build so the two cannot drift, two documents so the boundary is real.
      input: {
        app: resolve(import.meta.dirname, 'index.html'),
        player: resolve(import.meta.dirname, 'player.html'),
      },
    },
    // A budget rather than a warning nobody reads. The learner app is used on whatever device a
    // person happens to have; the admin console is not. Route-level splitting keeps the admin
    // tree out of the learner's download, and this fails loudly when something merges them.
    //
    // ONE CHUNK IS EXPECTED TO EXCEED IT: hls.js, at ~575kB, in its own file. It is imported
    // dynamically (src/player/useHls.ts) so it is downloaded by somebody who presses play and by
    // nobody else — which is the same reasoning as the admin split, applied to the one dependency
    // big enough to matter on its own. The number that would be a real problem is the entry
    // chunk's, and that is where to look if this list ever grows a second offender: hls.js
    // appearing inside `app` rather than beside it means a static import crept in.
    chunkSizeWarningLimit: 300,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
