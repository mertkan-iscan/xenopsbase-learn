# ADR-0102: A company is a row, not a realm

- **Status:** Accepted
- **Date:** 2026-08-28
- **Task:** T-0.2 (#2)

## Context

The stemcell's realm is declarative and lives in git; its no-manual-configuration rule exists so
that nothing about an environment depends on what somebody once clicked. A multi-tenant product
creates customers at runtime, through an API call (T-1.5, #21). Those two facts are in tension,
and the resolution decides what "a company" *is* to the identity layer — which is the decision
every tenancy task downstream assumes an answer to.

The wrong resolution is discovered late, at the moment the first customer asks for their own SSO
(T-1.8, #24), when a library of users, groups and assignments already exists on whichever side of
the line was chosen.

Keycloak is pinned at 26.7.1, in both the local stack and the stemcell's cluster, so the options
are judged against that version rather than against Keycloak in the abstract.

## Decision criteria

Written before the comparison, from the issue:

- Does the realm definition stay in git, honouring the no-manual-configuration rule
- What does blocking a company actually do, and how quickly (T-1.4, #20 requires: at the
  gateway, within one token lifetime)
- What isolation is actually being claimed to a customer, and what proves the claim
- The blast radius of the stemcell ADR-0010's runtime-user problem, multiplied by the number of
  customers

## Decision

**One realm per environment. A company is a row in `identity`'s own database, and the tenant
reaches every service as a verified claim in the JWT.**

Concretely, and this is already running in the local stack:

- The realm `xenopslearn` is one JSON file in git (`local/keycloak/realm-xenopslearn.json`),
  imported at startup. Creating a customer touches Keycloak not at all: it is an INSERT in
  `identity`, which is what makes T-1.5's "provisioning is an API call, not a runbook" possible.
- Each user carries `tenant_id` and `side` attributes, mapped into the token by protocol mappers
  declared **on each client directly** — not through a shared client scope, because a
  `clientScopes` block in a realm import *replaces* Keycloak's built-in scopes rather than adding
  to them, silently stripping `sub` and `realm_access.roles` from every token. That failure
  happened on this realm's first import and is documented on T-9.9 (#87).
- Services read the tenant from the verified claim and nowhere else. `TenantContext`'s own
  warning is the binding sentence, quoted here so this ADR and the code cannot drift apart:

  > A verified claim in the JWT. Nowhere else. A tenant taken from a header, a query parameter or
  > a request body is a tenant the caller chooses, which is not a boundary at all — it is a
  > cross-tenant read with extra steps, and it will be added by someone reasonable who needs it
  > for testing.

  Honoured by structure, not by review: the ArchUnit rule `onlyTheFilterResolvesTheTenant`
  fails the build of any class outside `common.tenancy` that binds a tenant.

**The isolation claim, stated so it can be tested.** What is claimed to a customer is logical
isolation, and exactly this much: *a request authenticated as tenant B receives 404 for every
tenant A resource, on every endpoint, always.* Not "separate infrastructure", not "separate user
store". The claim is proved rather than believed: T-1.1's forged-header and interleaved-tenant
tests (#17) prove the boundary holds at the entry points, and T-1.6's cross-tenant leak test
(#22) proves it holds across the API surface — against fixtures deliberately similar enough to
be confused for one another.

**Blocking a company** is an UPDATE on the tenant row, enforced at the gateway on the next
request that arrives after its cached token validation expires — which is what bounds it to one
token lifetime (T-1.4). No realm is disabled; Keycloak never knows.

**The escape hatch, for the customer who demands their own user store.** Per-company SSO is an
identity provider *inside* the realm (T-1.8): the customer's IdP remains the system of record
for their people, brokered in, with each brokered login landing as an `app_user` row linked by
`sub`. A customer whose contract demands more than that — physically separate identity
infrastructure — gets a dedicated deployment of the whole platform, which this architecture
makes possible precisely because nothing in the product distinguishes "our only realm" from "our
realm on your instance". What makes that a migration rather than a data loss is the `app_user`
indirection below.

## Consequences for the stemcell's ADR-0010

ADR-0010 established that `sub` is an ownership key, pinned the ids of *declared* users so a
realm re-import restores the same subs, and named its own limit: runtime-created users still get
generated ids, and a realm delete still loses them. It called the durable fix — "a local user
record keyed to `sub` on first sight, so that identity has one level of indirection and a
re-keying becomes a migration instead of data loss" — the known successor, not needed by
anything the stemcell ships.

This product is the case that needs it. **Every real user here is runtime-created**, so pinned
ids protect only the five seeded development accounts, and ADR-0010's exposure is multiplied by
exactly the number of customers the platform ever signs. That is why `app_user` owns identity
and `sub` is a nullable, repairable link (ADR-0104, T-1.2 #18) — the successor ADR-0010 asked
for, built where it is actually load-bearing. ADR-0010 itself needs no amending: its analysis is
correct for what the stemcell ships, and it already names this successor. What this ADR adds is
the standing rule on our side: **the realm may be deleted and re-imported in any environment
without losing a customer**, because nothing the product stores is keyed to raw `sub` — and
T-1.7 (#23) is the test that keeps that true.

## Consequences

### What this makes easy

Provisioning and blocking are rows, so they are API calls with tests, not runbooks. The realm
stays one reviewable file in git. The number of customers is invisible to Keycloak — a thousand
tenants cost the identity layer nothing. Every service authenticates against one issuer, so
service-to-service auth (T-9.11) and the gateway stay single-realm simple.

### What this makes hard

Isolation is logical, so the tenant discriminator must be *everywhere* and a single missed
filter is a data leak — which is why T-1.1 pushes it into the persistence layer rather than each
query, and why T-1.6 makes a cross-tenant read a build failure. Per-company login-page
customization is limited to what IdP brokering inside one realm allows.

### What it commits us to

Keycloak does authentication only. Tenancy, membership and permissions are product data in
`identity`, per ADR-0103 — using per-realm roles or groups for authorization would put half the
boundary in a system that cannot see the other half. Reversing to realm-per-company after
customers exist would mean partitioning one realm's users into many and re-keying every `sub`
link; the `app_user` indirection makes that survivable, but it is a migration nobody should
plan to run.

## Alternatives considered

### A realm per company, provisioned through the Admin API — rejected

The realm definition leaves git on the first customer: runtime provisioning through the Admin
API is exactly the hand-configuration the no-manual-configuration rule forbids, done by code
instead of hands, with drift as unreviewable either way. Every realm multiplies ADR-0010 — each
customer's realm is runtime state that a re-import cannot restore. Keycloak's own guidance caps
realms per instance at low hundreds before startup and cache costs bite, which puts a ceiling on
the business inside an infrastructure choice. And blocking a company becomes a realm disable,
which still honours already-issued tokens — no better than the row, with none of its simplicity.

### Keycloak Organizations, one per company — rejected as the boundary, open as a mechanism

Verified against the pinned version rather than assumed: organizations are GA in Keycloak 26.7.1
(their earlier `organization` feature flag became default). They model exactly this shape —
companies inside one realm, each with its own identity providers and membership.

Rejected as the *tenant boundary* on two of the criteria. Organization membership is runtime
state inside Keycloak's database — the tenant boundary itself would acquire ADR-0010's
durability problem, unrestorable from git. And the product needs the tenant row anyway:
suspension state (T-1.4), groups (T-1.3), roles and scopes (E2) are product data that Keycloak
cannot own under ADR-0103, so an organization would be a second, unauthoritative copy of the
tenant, and two sources of truth about who a customer is will disagree.

Left open deliberately: T-1.8 may still use the organizations feature as *routing machinery* —
matching a login to the right per-company IdP — with the row remaining authoritative. That
choice belongs to T-1.8 and does not reopen this one.

### One realm per environment, companies as top-level Keycloak groups — rejected

Groups-as-tenants puts membership in Keycloak (ADR-0010 again), gives group admins a Keycloak
console concept that does not map to the product's scoped-role model (T-2.3), and still needs
the tenant row for everything above. It is the organizations option with less support.

## Revisit if

- A signed customer's contract requires identity infrastructure that brokered SSO inside one
  realm cannot satisfy, and a dedicated deployment is judged too expensive for that deal — that
  is the realm-per-company case arriving with evidence.
- The realm import grows per-company identity providers past **50** — the point where one
  reviewable JSON file stops being reviewable — T-1.8 must then find where per-company IdP
  configuration durably lives, and that finding may supersede parts of this ADR.
- Token validation at the gateway cannot meet T-1.4's one-token-lifetime bound for suspension
  without consulting Keycloak per request — the row would then no longer be the cheap place to
  enforce blocking, and the trade should be re-measured rather than assumed in either direction.
