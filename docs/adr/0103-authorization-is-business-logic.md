# ADR-0103: Authorization is business logic, and it does not live in Keycloak

- **Status:** Accepted
- **Date:** 2026-08-28
- **Task:** T-0.3 (#3)

## Context

The product's authorization model is dynamic by requirement: a customer builds roles at runtime
by selecting permissions (T-2.2), assigns them at a scope (T-2.3), and expects a revocation to
mean something within seconds (T-2.5). Keycloak realm roles are declared in git and cannot do
any of that. Keycloak Authorization Services can — resources, scopes, policies, a policy
evaluation engine — which makes it the obvious candidate, and therefore the one that needs
arguing about rather than assuming.

The argument matters because this choice sits on the hot path. Every request to every service
asks "may this caller do this", and the answer's cost, freshness and debuggability are
properties of wherever the evaluation lives.

## Decision criteria

- Where the hot-path evaluation happens, counted in network hops per request
- Whether a report can join permissions to domain data
- What a permission change costs to make effective everywhere
- Who can debug it at 2am

## Decision

**Authorization is product data and product code. The permission catalog is an enum in the
codebase (T-2.1), roles and assignments are rows in `identity` (T-2.2, T-2.3), and the decision
is evaluated in-process by our evaluator (T-2.4) against a cached permission set (T-2.5).
Keycloak authenticates; it never authorizes.**

**The hop arithmetic, per request.** Keycloak Authorization Services evaluates on Keycloak: one
network round-trip per decision, to the same Keycloak whose availability already gates login —
now gating every request instead. The UMA flow is two or more. Token-embedded permissions (RPT)
remove the hop by freezing decisions into a token, which converts every revocation into "wait
for expiry" — exactly what T-1.4 and T-2.5 forbid. Ours: **zero hops steady-state** — the
evaluator reads a permission set cached in-process, falling back to one Valkey read on version
miss, one Postgres read on cold start. The measurement that keeps this honest arrives with
T-2.5; the shape is decided here.

**Cache invalidation is the failure mode of this design, named as such.** A permission set
cached for speed is a revocation delayed for the same reason. The mechanism, decided now so
T-2.5 implements rather than debates: **every grant-affecting write bumps a per-(tenant, user)
version in Valkey, and the evaluator validates its in-process set against that version before
trusting it.** Staleness is bounded by one version check, not by a TTL, and a revoked permission
is never served from a warm cache (T-2.5's title is this sentence). If Valkey is unreachable the
evaluator falls through to Postgres — authorization degrades to slower, never to wrong.

**Reports join.** Permissions, roles, assignments and the people they attach to live in
`identity`'s Postgres, one schema. "Which humans can export this report" (T-7.10's audit needs
exactly this) is a join. Under option 1 it is a Keycloak Admin API crawl stitched to our data —
across the module boundary this project forbids elsewhere on principle.

**2am.** The failing decision is our code in our service: one log line names the caller, the
permission, the scope, and the version of the set that decided. Under option 1 the same question
lands in Keycloak's policy evaluation logs, in Keycloak's format, correlated by hand, in a
system whose expert is not on this team.

**The privilege-escalation rule, decided here rather than left to implementation:** *nobody
grants what they do not hold.* A caller may place a permission into a role, or assign a role,
only where every permission involved is one the caller holds at an equal or wider scope, on the
same side. Enforced in the grant path itself (T-2.6), not in the UI — and the seeded system
roles (T-2.7) exist so there is a granted starting point that did not itself need granting.

**What Keycloak remains authoritative for**, exactly and only: credentials and their storage,
authentication flows and sessions, SSO and identity brokering (per-company IdPs, T-1.8), token
issuance and signing, and the three claims this platform trusts — `sub`, `tenant_id`, `side` —
plus the coarse realm roles (`platform`, `tenant`) that gate which side's permissions may even
be considered. Nothing else. A Keycloak group, realm role or authorization policy is never
consulted for a product decision.

## Consequences

### What this makes easy

Runtime role-building is CRUD in our schema. Revocation latency is our number to guarantee.
Authorization data joins domain data for reports and audits. Local development needs no
Keycloak feature configuration — the fake tokens in tests carry the same three claims.

### What this makes hard

We own an authorization engine: catalog discipline (T-2.1's "a permission nothing checks must
be impossible to ship"), scope semantics (T-2.3), cache correctness (T-2.5), and the audit trail
(E7) are all ours to get right, with tests rather than a vendor's word. The evaluator becomes
load-bearing shared code — a bug in it is a cross-cutting security bug, which is why T-2.4 pins
it behind a small, testable interface.

### What it commits us to

Keycloak stays replaceable-in-principle (it authenticates and brokers; ADR-0104 already made
`sub` a link), and conversely, no future feature may quietly move a product decision into a
Keycloak policy because it looked convenient in the admin console. The catalog-as-code rule
makes that visible: a permission that is not in the enum does not exist.

## Alternatives considered

### Keycloak Authorization Services — rejected

A network hop (or a frozen token) on the hot path; policies live outside git in Keycloak's
database, acquiring ADR-0010's durability problem for the entire access-control model; the
policy engine's decisions are the hardest part of the system to debug and the least joinable to
domain data. It is built for the case where many applications share one authorization authority.
This platform is one application whose authorization *is* its domain.

### Hybrid — coarse realm roles plus fine-grained permissions in our database — adopted only at the edge

This is not rejected so much as bounded: the `platform`/`tenant` realm roles and the `side`
claim are exactly the coarse half, and they stay — the gateway uses them to fail obviously
wrong requests early. But they are a pre-filter, not an authority: no product decision is made
on a realm role alone, because a rule split across two systems is a rule neither system can
state.

## Revisit if

- A second first-party application needs the same authorization decisions from outside these
  services — a shared authority is then a real requirement, and Keycloak AuthZ or a dedicated
  policy service should be re-weighed against extracting our evaluator behind an API.
- T-2.5's measurement shows the version-check design cannot hold revocation latency under one
  second at production load — the caching mechanism reopens, not the ownership decision.
