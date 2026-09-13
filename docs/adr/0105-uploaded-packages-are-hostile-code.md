# ADR-0105: Uploaded packages are hostile code and run on a foreign origin

- **Status:** Accepted
- **Date:** 2026-08-31
- **Task:** T-0.5 (#5)

## Context

A SCORM package is a ZIP of HTML, JavaScript and media, produced by an authoring tool, uploaded
by a customer, and written by a vendor neither we nor the customer has met. It is third-party
code by construction, and there is no version of this product in which it is not: a learning
platform that cannot run SCORM is not a learning platform.

The standard makes the dangerous option the convenient one. SCORM's API discovery walks
`window.parent` (and `window.opener`) looking for an `API` or `API_1484_11` object, and reading a
property off another window is a **same-origin operation**. So the shortest path to a package
that works — serve it from the application's own origin, put the API object on `window` — is also
a complete compromise of the application. The convenience and the vulnerability are the same
line of code.

Two forces make the stakes higher here than in a single-tenant LMS. This platform is multi-tenant
in one database (ADR-0102), so an attacker who reaches the application's session reaches the API
that ADR-0103's permission model guards, as a real user, with that user's grants. And an uploaded
package is delivered to *learners*, so one malicious upload runs in as many browsers as the course
has students, on whatever schedule those students choose.

## Decision criteria

- **What a malicious package can reach in each option** — stated as an attack, not as a risk level
- **Whether the mitigation is structural or remembered** — a control that a later convenience can
  silently remove is a control with a shelf life
- **Cost in DNS, certificates and object-storage configuration**, measured rather than assumed
- **Whether the local stack can be faithful**, because an isolation property first met in
  production is a property nobody has tested

## Decision

**Uploaded packages are served from a content origin that is not the application's origin, one
origin per tenant, and no credential of any kind is ever presented to that origin.**

The origin scheme is fixed as: `https://<tenant>.<content-domain>` — for example
`https://acme.usercontent.xenopslearn.com` — with the application on its own separate domain.
Locally, `http://<tenant>.localhost:8090` against the app on `:8080`.

Everything below follows from that sentence.

### The launch chain, and where the API lives

1. The application page (app origin) embeds an iframe pointing at the **wrapper**, served from
   the tenant's content origin.
2. The wrapper implements `API_1484_11` (and the SCORM 1.2 `API`) and embeds the package's own
   entry point in a second iframe, on that same content origin.
3. The package's discovery walk finds the API on its parent — same origin, exactly as the
   standard expects — and never learns that anything unusual is happening.
4. The wrapper forwards every call to the application over `postMessage`; the application makes
   the real API call with the learner's session, from the app origin, where that session lives.

The package therefore runs in a browsing context that holds nothing: no cookie is ever set on the
content origin, no token is ever passed into it, and the wrapper stores nothing across launches.
An attacker who fully controls the package controls a document whose origin has nothing worth
stealing, and whose only channel out is a `postMessage` the application validates.

### One origin per tenant, decided now rather than later

A single shared content origin — option 2 — is a complete answer to the application-compromise
attack and only a partial answer to the cross-tenant one. Packages from two customers sharing an
origin share everything an origin is: `localStorage`, IndexedDB, service-worker registration
inside its own path scope, and script access to any sibling document either one can obtain a
handle to. That is the same class of failure ADR-0102 spends its entire length preventing in the
database, and it would be strange to prevent it there and concede it in the browser.

It is decided now because it is not retrofittable: a launch URL is embedded in course content,
recorded in attempt history and cached by browsers, so changing the origin scheme later rewrites
data rather than configuration.

**Measured, on 2026-08-31, because the third criterion demands a number rather than a fear:**
`*.localhost` resolves without any DNS record or hosts-file entry — `curl http://acme.localhost:8090`
and `http://globex.localhost:8090` both reached the local content origin (`::1`). In production
the cost is one wildcard DNS record and one wildcard certificate, both of which Cloudflare issues
for a zone as a matter of course. Per-tenant isolation is therefore approximately free, and the
argument for the shared origin was never cost — it was that nobody had priced the alternative.

### `postMessage` validation is mandatory on both ends

Both sides name the other exactly, and neither ever uses `'*'`:

- The application sends with the tenant's content origin as `targetOrigin`; the wrapper sends
  with the application's origin. A `'*'` here hands the SCORM runtime data — and anything else in
  the message — to whatever origin happens to be hosting the frame at that moment.
- Both ends check `event.origin` against an **exact string** built from configuration. Not
  `endsWith`, not a regular expression: `acme.usercontent.xenopslearn.com.evil.test` ends with
  nothing useful to an attacker only if the comparison is equality.
- Both ends check `event.source` is the window they expect, so a third frame cannot post a
  message that looks like the wrapper's.
- Anything failing either check is dropped silently. A message that answers "wrong origin" is a
  message that helps somebody find the right one.

### Ingest-time protections, before a package is ever served

Unpacking an archive from a stranger is its own attack surface (T-4.1 implements this list):

- **Path traversal** — every entry's normalized path must stay inside the destination root.
  Reject `..` segments, absolute paths, drive letters, backslash separators and symlink entries.
  The check is on the normalized result, not on the string, because `a/../../b` is only visible
  after normalization.
- **Zip bombs** — cap the entry count, the per-entry uncompressed size, the total uncompressed
  size and the compression ratio, and enforce them **while streaming**. The sizes declared in the
  archive's central directory are attacker-controlled and cannot be the thing that is checked.
- **MIME allowlist** — only known-good types are served, by extension, with the type set by us.
  Anything else is not served at all. Never a guessed type, and `X-Content-Type-Options: nosniff`
  on every response, so the browser cannot decide that a `.txt` was HTML.
- **CSP on the content origin** — `default-src 'self'` with `connect-src 'self'` (no exfiltration
  by `fetch`/XHR/WebSocket), `frame-ancestors` naming only the application origins (so the origin
  cannot be framed by an attacker's page), and `'unsafe-inline'`/`'unsafe-eval'` allowed because
  real authoring tools emit both — a CSP that forbids them forbids SCORM. Verified live on
  2026-08-31: the local content origin serves exactly that header on `/packages/*`, and answers
  404 on every other path.

**One limit, stated rather than implied:** CSP does not stop exfiltration by navigation. A
package can still put data in a URL and call `window.open` or navigate itself. The iframe's
`sandbox` attribute constrains the worst of it (`allow-top-navigation` is not granted), and the
residue is that a package can leak *its own* content — which its author already has. It cannot
leak the learner's session, because it never had it.

## Consequences

### What this makes easy

- A package compromise stays a package compromise. The blast radius is one tenant's own uploaded
  content, in one browser tab, with no credential in reach.
- The bridge is exercised in development: the local stack already runs the content origin on a
  different port, so the `postMessage` path is the normal path, not a production-only one.
- Per-tenant origins make an origin-scoped browser control (a service worker, a storage quota, a
  CORS rule) automatically tenant-scoped, without anybody remembering that it should be.

### What this makes hard

- Every asset URL is now cross-origin, so anything the application wants to read from a package
  (a manifest, a thumbnail) is read **server-side**, never by the browser reaching across.
- The wrapper is a real component with a versioned contract, deployed with the frontend but
  served from the content origin (T-4.3, T-10.7).
- Local development needs the app and the content origin on different hosts, which rules out
  "just serve everything from the dev server".

### What it commits us to

Wildcard DNS and a wildcard certificate for the content domain, and a launch URL built from
configuration rather than concatenated from the app origin. Reversal is expensive in exactly one
direction: moving *from* per-tenant origins *to* a shared one would be easy and pointless; moving
the other way later would rewrite every stored launch URL.

**The mitigation is made structural rather than remembered.** The failure mode is not that
somebody argues for the same origin — it is that somebody adds a convenience route,
`/packages/**` on the app origin, to fix a CORS complaint on a Friday. `TechnicalStructureTest`
now fails the build if any controller in any service maps a path under `/packages`, in the same
way T-3.10 keeps playback independent of the backend by asserting it rather than believing it.

## Alternatives considered

### Option 1: serve packages from the application origin — rejected

Written out concretely, because an option rejected in the abstract gets re-proposed as a
simplification:

1. A vendor ships `course.zip` containing `index.html`, which contains one extra script tag.
2. A customer's author uploads it. Nothing about it is remarkable; it is a working course.
3. A learner opens the course. The package's script now runs on `app.xenopslearn.com`.
4. It does not need to steal a token. It is **already** in the application's origin: it reads the
   DOM the SPA rendered, calls `/api/v1/...` with the ambient session, and reads any CSRF token
   from the page because same-origin code can.
5. With the learner's own grants it invites an account it controls (T-1.9), or assigns itself a
   role (T-2.3). If the learner happens to be an administrator, that is the whole tenant.
6. It exfiltrates with a cross-origin `fetch`, or an image URL, or a form post — the app origin
   has no CSP that would stop it, because the app origin has to run our own code.
7. Every one of those requests is authentic. The audit log's actor is the learner, because the
   learner is who made them.

The mitigation for this option would be sanitising uploaded HTML and JavaScript, which is not a
solved problem and is not going to be solved here. The option is rejected permanently.

### Option 2: one shared content origin for all tenants — rejected

Correct against option 1's attack, and the standard answer in most LMS products. Rejected because
its residual risk is cross-tenant, it is not retrofittable once launch URLs exist, and the reason
to accept it — cost — turned out to be one DNS record and one certificate (measured above).

### Option 3 was this decision, and it wins on the criteria as written.

## Revisit if

- A customer requires their own content domain (a vanity origin), which is this decision with
  their DNS rather than ours — an extension, not a reversal.
- The wildcard certificate becomes a constraint (a registrar or CDN that will not issue one, or a
  compliance rule against wildcards), in which case per-tenant becomes per-package paths on a
  single origin **plus** the storage isolation that no longer comes for free.
- A browser changes what an origin guarantees — the decision rests on the same-origin policy, and
  it is worth re-reading if that ever stops being the boundary it is today.

## Amendment, 2026-09-11 (T-4.1): the separator, and what the measurement missed

**The origin scheme is now `https://<tenant>--usercontent-<env>.<domain>`** — one label below the
apex, with a double hyphen where this document originally wrote a dot. On dev:
`https://acme--usercontent-dev.xenopsoftware.com`. Locally nothing changes:
`http://<tenant>.localhost:8090`, because `*.localhost` needs neither DNS nor a certificate.

### Why the original scheme could not be deployed

The measurement above says the production cost is "one wildcard DNS record and one wildcard
certificate, both of which Cloudflare issues for a zone as a matter of course". **That is true of a
zone and false of this one**, and the difference was not visible until somebody tried to deploy it.

- Cloudflare's Universal SSL covers the apex and **one** label below it. `*.xenopsoftware.com` is
  covered; `*.usercontent-dev.xenopsoftware.com` is not, and needs an Advanced Certificate at
  roughly $10 a month per zone. The stemcell's own edge configuration already says this in as many
  words — "One label below the apex, so Cloudflare's Universal SSL certificate covers it" — and
  this ADR was written without reading it.
- The wildcard DNS record is worse. `xenopsoftware.com` is a **company** zone with a live site on
  it. A `*` record there captures every name in the zone that does not already exist, which is not
  a thing to do to a domain that is not this project's.

So the honest cost of the original scheme was a recurring certificate bill plus a wildcard record
on somebody else's domain — not "approximately free". The criterion that asked for a number was
right; the number was taken from a general fact about Cloudflare rather than from this zone.

### What the amendment buys and what it gives up

**Buys:** every tenant origin is covered by a certificate that already exists, at no cost, and the
per-tenant isolation the decision is entirely about is unchanged. `acme--usercontent-dev` and
`globex--usercontent-dev` are two different origins to a browser in exactly the way
`acme.usercontent` and `globex.usercontent` would have been.

**Gives up two things**, and neither is free:

1. **Provisioning a company now includes creating a DNS record**, because there is no wildcard.
   One record and one ingress rule per tenant, created with the tenant. On dev that is two of each,
   listed in `platform/envs/dev/services/content-origin.yaml`. In production it is an API call on
   the provisioning path, which is work this decision did not previously imply.
2. **A company id may not be two characters long.** RFC 5891 reserves any label with `--` in its
   third and fourth characters for internationalised domain names, so `ab--usercontent-dev` is a
   name resolvers and registrars are entitled to refuse. `ContentOriginProperties` throws rather
   than building one — loudly, because the alternative is a customer whose courses do not load and
   an id that cannot be changed by then.

The separate-domain option — a dedicated zone with tenants at its first label, which is what "with
the application on its own separate domain" above literally asks for — is strictly better than
either: Universal SSL covers it, a wildcard record is safe on a zone that is only ours, and a
different **registrable** domain means no shared cookie scope with the application at all. It costs
a domain registration. It is the right move the moment somebody is willing to buy one, and it is a
change of configuration rather than of design: `CONTENT_ORIGIN_TEMPLATE` and one DNS record.

### Also amended: the CSP is served by the service

This document's isolation has two halves, and for a while only one of them was in the product. The
`Content-Security-Policy` that stops package code calling anywhere outbound lived in
`local/content-origin/Caddyfile` — a development file — and nothing would have served it from a
cluster. `ContentOriginProperties.contentSecurityPolicy()` now emits it from the service, on both
the wrapper and every file, built from the application origin the service is already configured
with. The decision criteria ask whether a mitigation is structural or remembered; this one was
remembered.
