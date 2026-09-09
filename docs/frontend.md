# The frontend

**Task:** T-10.1

The web application is a service like the others: its own directory (`web/`), its own build, its
own tests, and decisions made once here rather than accumulated screen by screen.

```bash
make up          # the stack
make web         # the dev server on http://localhost:5173
```

## What was decided, and why

### It is a static build. No server-side rendering.

What ships is files: HTML, CSS and JavaScript, served from the edge. There is no Node process in
any request path.

The reason is not preference, it is [ADR-0101](adr/0101-video-is-delivered-by-the-edge.md) and the
test that enforces it. T-3.10 asserts that playback survives every one of our services being
stopped. A server-rendered page makes our own renderer a dependency of the page that plays the
video, so the property would be false the moment it was written down — and it would fail in the
way that is hardest to notice, because the video would still be at the edge and only the page
around it would be gone.

SEO is not a consideration: every screen is behind authentication.

### One application, two route trees.

`/` is the learner app; `/admin` is the console. One deployable, one session story, one API
client, one design system.

They genuinely differ — thousands of people on whatever device they have, against a handful of
administrators on a desktop — and the honest reading of that difference is a **bundle** concern
rather than a **deployment** concern. So it is answered with route-level code splitting and a
boundary the build enforces:

- the admin routes are lazy, so a learner never downloads the console (measured: the admin chunk
  is its own file, and the learner's entry does not contain it);
- `eslint` refuses an import from `src/learner/**` into `src/admin/**` and back, with the reason in
  the error message.

**Chosen in the reversible direction.** Splitting one application into two later is moving files
and adding a build target. Merging two into one later is a rewrite of the session, the client and
the design system. If the console ever needs its own release cadence or its own availability, that
is when it earns its own deployable.

### The browser talks to one origin.

In production that is the gateway, which serves the application and relays inward (T-9.11,
T-10.2). In development the Vite dev server proxies `/api` to `identity`, standing in for it.

The alternative — the app on `:5173` calling `identity` on `:8082` directly — means opening CORS
on every service to a development origin, which is a production-shaped hole cut for a development
convenience.

### The API client is generated, and the generation is checked.

`npm run api:generate` writes `web/api/identity-openapi.json` and the types beside it from the
service's own `/v3/api-docs`. `npm run api:check` regenerates against a running service and
**fails on any difference**.

Checked in, so a build needs no backend and a diff shows what changed. Checked, so the copy cannot
quietly become fiction. `check` also fails when it cannot reach the service, deliberately: a check
that passes with nothing to compare against reports success for work it did not do.

Nothing in `src/shared/api/client.ts` is hand-written knowledge about the API. Paths, request
bodies and response shapes come from the generated types, so a backend change that breaks a call
is a type error rather than a broken screen.

### Loading, failed and empty are components, not conventions.

`src/shared/state/States.tsx`, written before there were screens to retrofit. They are the three
states every screen has and the three every screen gets wrong when each one invents them: a
spinner nobody announces, an error swallowed into a blank page, an empty list that looks like a
failure.

`Loading` is a polite live region with `aria-busy`. `ErrorState` is `role="alert"`, because a
failure interrupts rather than updates, and it carries its retry. `Empty` says what to do next, or
it is just a smaller failure.

### The player is a third area, not a corner of `shared/`.

`src/player/**` is published on its own (T-10.7) as the package a customer embeds, so an import
reaching from it back into a screen is not untidiness — it is the extraction failing. The lint
rule refusing that is the same mechanism as the learner/admin boundary, held for a sharper reason,
and it costs nothing now while there is nothing to untangle. See [player.md](player.md) and
[ADR-0110](adr/0110-the-embeddable-player-is-an-iframe.md).

There are two build entries for the same reason: `index.html` is the application and
`player.html` is the document the iframe loads. Our own screens embed that document rather than
rendering the player component directly — a private in-process path is one nobody would notice
breaking.

### The build fails on a type error, a lint error, and an accessibility violation.

```bash
npm run verify     # lint, typecheck + build, tests
```

The accessibility check is `axe` inside component tests, on the shell and the shared states —
deliberately on the pieces everything else is built from, since a violation there is inherited by
every screen written afterwards. **Colour contrast is switched off in those runs and the reason is
written next to it**: jsdom has no layout, so axe cannot measure contrast and reports nothing
either way. Leaving it on would make the suite look like it checks something it does not. Contrast
belongs to a browser-based check when there are screens worth running one against (T-10.8).

## Sign-in, and why there is no token here (T-10.2)

**The browser holds an opaque session cookie and nothing else.** The gateway holds the access and
refresh tokens, renews them before they expire, and attaches one to every call it relays inward.

The usual answer — the authorization-code flow in the SPA, access token in `localStorage` or in a
variable — is wrong for *this* product rather than in general. This application will render
uploaded SCORM packages (T-4.1) and third-party embeds, so "any script that reaches the page can
read the token" stops being a caution and becomes a path: a package a customer uploaded, running on
this origin, reading a bearer token for their whole company. Keeping the token out of the browser
makes that path not exist rather than defended. The full argument is in the gateway's
`SecurityConfiguration`, next to the code that enforces it.

What follows from it, and is worth knowing before writing a screen:

- **There is no refresh logic in this application, and there must not be.** No expiry timer, no
  retry-on-401-because-the-token-expired. The gateway renews five minutes before expiry, so a
  request simply carries a fresh token.
- **A write needs the CSRF header.** The credential is a cookie now, so every write behind the
  gateway is reachable from any page a person has open. `shared/api/client.ts` adds the header;
  anything calling `fetch` directly has to as well.
- **A 401 means the session ended, not that you may not.** The gateway sends `SESSION_ENDED`, and
  the difference decides whether a screen preserves work or discards it.
- **Work that must survive re-authentication goes through `shared/auth/recovery.ts`.** Signing in
  again is a full navigation, so anything in memory is gone. The exam case is the reason it exists:
  park the answers, sign in, replay.
- **`npm run dev` now needs the gateway running** as well as the services, because the dev proxy
  sends everything to it. That is the point: sign-in, cookies, CSRF and relaying are exercised in
  development instead of first meeting reality in a cluster.

## What is deliberately not here yet

- **A design system.** It arrives with the screens it has to serve (T-10.3, T-10.4). What exists
  is visible focus, landmarks and a skip link — the parts that are structural rather than
  decorative.
- **CI.** The `verify` script is what a pipeline would run, and there is no pipeline (T-9.3 was
  dropped; see T-1.6 and T-1.7 for the same gap). The API drift check in particular is written to
  be a CI step.

## Verified, on 2026-09-01

Against the real local stack rather than mocks: the learner screen rendered `identity`'s answer to
`GET /api/v1/me` through the generated client and the dev proxy, and the console invited
`web-check@acme.test` through `POST /api/v1/users/invitations` — the token came back once, the row
landed in the database as `INVITED` with only its hash stored.

Both guards were checked by breaking them on purpose: an import from `learner` into `admin` fails
the lint with the boundary's reason, and an edited OpenAPI description fails `api:check` with the
instruction to regenerate.
