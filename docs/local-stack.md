# The local stack

**Task:** T-9.9

Everything the platform depends on, on one machine.

```bash
cp .env.example .env
make up
```

`make up` waits until the stack is actually serving rather than returning when the containers start.
The gap between those two moments is where the confusing failures live — Keycloak accepts
connections for some time before its realm exists, and a service that starts against a realm-less
Keycloak fails in a way that reads as a configuration error.

| | | |
|---|---|---|
| app origin | http://localhost:8080 | services — nothing runs here yet |
| web | http://localhost:5173 | the frontend — `make web` (T-10.1, docs/frontend.md) |
| **content origin** | **http://\<tenant\>.localhost:8090** | **a different origin per company, deliberately** |
| Keycloak | http://localhost:8081 | `admin` / `admin` |
| MinIO console | http://localhost:9001 | |
| NATS monitoring | http://localhost:8222 | |
| Postgres | `localhost:5432` | |
| Valkey | `localhost:6379` | the permission cache (T-2.5); stop it and the stack still works |

## Seeded users

Password is the username. Development only.

| User | Tenant | Side |
|---|---|---|
| `platform-admin` | — | `PLATFORM` |
| `acme-admin` | `acme` | `TENANT` |
| `acme-learner` | `acme` | `TENANT` |
| `globex-admin` | `globex` | `TENANT` |
| `globex-learner` | `globex` | `TENANT` |

The two tenants carry deliberately similar data — same first and last names, same shape of email.
The cross-tenant leak test (T-1.6) is worth nothing against fixtures that could never be confused
for one another, and a leak found by a test that had to try is the point.

```bash
make token U=acme-learner     # an access token, for poking at things with curl
```

The token carries `tenant_id` and `side` as claims. **That is the only place a tenant may come
from.** A tenant read from a header is a tenant the caller chooses, which is not a boundary (T-1.1).

## What is faithful here, and what is not

This matters more than it looks, because "it works locally" is about to become the only evidence
this project has.

| Dependency | Locally | In production | Faithful? |
|---|---|---|---|
| Postgres | container | managed | yes |
| Keycloak | container, realm from this repo | same image, same realm | yes |
| Object storage | MinIO | Hetzner Object Storage or R2 | **yes** — all three speak the S3 API, so it is the same adapter and the same buckets. Endpoint and credentials are the only difference |
| Message bus | NATS JetStream | same | yes |
| Cache | Valkey | same | yes |
| Content origin | second port, real CSP | Cloudflare in front of R2 | **partly** — origin isolation and the `postMessage` bridge are real here; edge signing and lifecycle rules are not |
| **Video** | **nothing** | Cloudflare Stream | **no** |
| **Mail** | **nothing** | an SMTP provider | **no** — `LoggingMailer` records every letter and delivers none, with a WARN at startup |

**Video has no local equivalent.** Cloudflare Stream is managed transcoding, an adaptive ladder, an
edge and a signing scheme. `streaming` therefore runs against a fake `MediaProvider` by default,
which is enough for entitlement, token minting, heartbeats, interval accounting and gating — the
entire domain. Upload, encode webhooks and signed playback need a real account (T-9.14).

**A green local build does not prove edge delivery works.** It cannot, and nothing here should be
read as if it did.

**Mail has no local equivalent either, and it is the same shape of gap.** Reminders (T-5.6) are
claimed, recorded and handed to a `Mailer` that logs the recipient and the subject and delivers
nothing. That exercises every call site and proves nothing about delivery. Set
`spring.mail.host` and `platform.mail.from` to send for real; see
[deadlines.md](deadlines.md).

## Databases

One per module, one role per database.

```bash
make psql D=identity
```

The process count is still open — `identity`, `catalog` and `assessment` may start inside one
`core` process (ADR-0109). **The data boundary is not open.** A merged process holds three
datasources; a split later moves no data. Enforcement is credentials rather than convention: a
cross-module query does not return the wrong answer, it fails to connect.

| Database | Module |
|---|---|
| `identity` | tenants, users, groups, roles, permissions |
| `catalog` | content items, courses, modules, gates, assignments |
| `assessment` | banks, questions, tests, forms, attempts |
| `streaming` | video assets, encode state, playback tokens |
| `reporting` | telemetry, rollups, exports |
| `packaging` | uploaded packages: state, manifest facts, entry point |
| `keycloak` | Keycloak's own |

## The content origin

`http://<tenant>.localhost:8090` serves one tenant's uploaded packages and nothing else.

It exists because a SCORM package is third-party JavaScript uploaded by a customer, and the
standard's API discovery walks `window.parent` — a same-origin operation. Serving packages from the
application's origin makes that walk succeed and hands every uploaded package the application's
DOM, cookies and tokens, for every tenant.

A different port is a different origin to a browser, so the wrapper-and-`postMessage` design
(T-4.3) is exercised here rather than first met in production. The CSP served on that origin also
blocks package code from calling outbound.

Do not "simplify" this by serving packages from the app origin. That is not a simplification, it is
the vulnerability (ADR-0105).

**ADR-0105 fixes the production scheme as one origin per tenant** — `<tenant>.<content-domain>`,
because packages from two customers sharing an origin share everything an origin is. **The local
stack is per-tenant now.** `*.localhost` resolves to loopback with no DNS record and no hosts entry
(measured 2026-08-31: `acme.localhost:8090` and `globex.localhost:8090` both reached it on `::1`),
so the launch URLs `packaging` issues are `http://<tenant>.localhost:8090/packages/<tenant>/<id>/…`
and two companies are two origins to a browser exactly as they will be in production.

### It proxies to `packaging`, not to MinIO

An earlier version of the Caddyfile pointed `/packages/*` straight at MinIO, and it could not have
worked: the `packages` bucket is private on purpose, and Caddy cannot sign an S3 request. The two
ways out were making the bucket public — which makes every tenant's uploaded content
world-readable by URL guess, silently — or putting something in front that holds the credential.

The something is the `packaging` service, and it is strictly better than the proxy-to-storage
version, because object storage cannot do the two things ADR-0105 requires of every response here:
serve each file as the type **we** decided from an extension allowlist rather than a type the
archive chose, and refuse to let the browser sniff past it.

So the chain is: browser → `<tenant>.localhost:8090/packages/…` → Caddy strips `/packages` and
rewrites to `/served/…` → `packaging` on `:8087`. The rename is not decoration: an ArchUnit rule in
every module fails the build on any mapping whose path contains the word `packages`, because the
mistake this decision guards against is a convenience route appearing on the **application's**
origin. `packaging.content-origin.path-prefix` is the setting that keeps the two ends agreeing.

`packaging` runs on the developer's machine like every other service, so the Caddyfile reaches it
at `host.docker.internal:8087` (mapped to the host gateway by `extra_hosts` for Linux, where
Docker does not provide it).

### One header worth knowing about

`frame-ancestors` on that origin lists `'self'` as well as the application's origins. Without
`'self'` the wrapper cannot frame the package's own entry point — which is the second half of the
launch chain — and the symptom is a wrapper that loads, both SCORM API objects present, and a
blank white frame with `ERR_BLOCKED_BY_RESPONSE` in the console.

## Resetting

```bash
make reset     # destroys the volumes and rebuilds from the seed
```

Cheap, and meant to be used. Nothing in this stack is worth protecting.

## Versions

Pinned in `docker-compose.yml`, and Keycloak matches the image the stemcell's cluster runs, so the
realm definition is portable between them. When the service test harness lands (T-9.10) these
versions and its Testcontainers versions have to come from one place — a compose file that drifts
from the test fixtures is a second definition of the system, and the day they disagree is an
afternoon lost to a bug that exists in only one of them.
