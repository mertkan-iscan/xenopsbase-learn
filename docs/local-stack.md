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
| web | http://localhost:5173 | frontend — nothing runs here yet |
| **content origin** | **http://localhost:8090** | **a different origin, deliberately** |
| Keycloak | http://localhost:8081 | `admin` / `admin` |
| MinIO console | http://localhost:9001 | |
| NATS monitoring | http://localhost:8222 | |
| Postgres | `localhost:5432` | |
| Valkey | `localhost:6379` | |

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

**Video has no local equivalent.** Cloudflare Stream is managed transcoding, an adaptive ladder, an
edge and a signing scheme. `streaming` therefore runs against a fake `MediaProvider` by default,
which is enough for entitlement, token minting, heartbeats, interval accounting and gating — the
entire domain. Upload, encode webhooks and signed playback need a real account (T-9.14).

**A green local build does not prove edge delivery works.** It cannot, and nothing here should be
read as if it did.

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
| `keycloak` | Keycloak's own |

## The content origin

`http://localhost:8090` serves the `packages` bucket and nothing else.

It exists because a SCORM package is third-party JavaScript uploaded by a customer, and the
standard's API discovery walks `window.parent` — a same-origin operation. Serving packages from the
application's origin makes that walk succeed and hands every uploaded package the application's
DOM, cookies and tokens, for every tenant.

A different port is a different origin to a browser, so the wrapper-and-`postMessage` design
(T-4.3) is exercised here rather than first met in production. The CSP served on that origin also
blocks package code from calling outbound.

Do not "simplify" this by serving packages from the app origin. That is not a simplification, it is
the vulnerability (ADR-0105).

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
