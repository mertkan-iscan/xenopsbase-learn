import { resolve } from 'node:path';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
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
  // Tailwind v4 is a Vite plugin and a CSS import -- there is no `tailwind.config.js`. The theme
  // is declared in `src/styles.css`'s `@theme` block, beside the palette it defines, so the
  // tokens and the utilities that consume them cannot drift apart in two files.
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    // ONE ORIGIN, AND NOW ONE ROUTER BEHIND IT (T-10.2).
    //
    // This block used to carry a copy of the service routing table, with a warning that its order
    // mattered: two services answer under `/api/v1/me`, so a general `/api` rule listed first sent
    // every playback token to identity. That table now lives in the gateway's `Upstreams`, in one
    // place, and everything here goes to the gateway -- which is exactly what happens in the
    // cluster, so a developer's browser and a customer's take the same path through the same code.
    //
    // What this costs is that `npm run dev` now needs the gateway running as well as the services.
    // What it buys is that sign-in, session cookies, CSRF and relaying are exercised in
    // development rather than first meeting reality in a cluster.
    proxy: {
      '/api': { target: process.env.GATEWAY_URL ?? 'http://localhost:8080', changeOrigin: false },
      '/auth': { target: process.env.GATEWAY_URL ?? 'http://localhost:8080', changeOrigin: false },
      // The two paths sign-in itself travels: out to the issuer, and back with the code.
      '/oauth2': { target: process.env.GATEWAY_URL ?? 'http://localhost:8080', changeOrigin: false },
      '/login/oauth2': { target: process.env.GATEWAY_URL ?? 'http://localhost:8080', changeOrigin: false },
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
