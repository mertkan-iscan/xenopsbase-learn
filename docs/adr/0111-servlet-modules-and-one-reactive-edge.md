# ADR-0111: Eight servlet modules on virtual threads, and one reactive edge

- **Status:** Proposed
- **Date:** 2026-09-06
- **Task:** T-9.16

**Proposed rather than Accepted, and the reason is the gateway.** Two of the three decisions below
are already implemented and building; the third names a process that does not exist yet. It moves
to Accepted when `gateway` runs and T-9.15 re-measures what it costs on our own cluster.

## Context

**This decision was never taken.** That is the first thing worth writing down, because a reader
five months from now will assume it was.

Every module in this repository is Spring MVC. Nothing chose that. `services/README.md` says the
code "follows xenopsbase-stemcell's conventions deliberately — same Spring Boot, same Java, same
plugins, same rules", the stemcell service this template was cut from is `core`, and `core` is
servlet. The stack arrived with the template and nobody ever wrote down why it should stay.

An inherited default is not wrong for being inherited. It is dangerous for being invisible: nobody
re-examines a decision they do not know was made, and the first person to notice usually notices
by hitting it.

Here is how it was noticed. `platform-common/pom.xml` declared `spring-boot-starter-web` and its
own comment named the consumer that made the Hibernate dependency optional:

> a service without JPA (the gateway) must not inherit Hibernate by depending on this library

and [ADR-0109](0109-eight-modules-six-processes.md)'s module table listed `gateway` as
**"yes — exists"**. Both statements are about the stemcell's gateway, because this repository has
no gateway module. And the stemcell's gateway is Spring Cloud Gateway, which exists only on
WebFlux.

So the two claims could not both hold:

- a library that brings `spring-boot-starter-web` puts Tomcat on the classpath, Boot then resolves
  the application type as `SERVLET`, and Spring Cloud Gateway does not start;
- every filter in that library — `CorrelationFilter`, `TenantFilter`, `StatusGateFilter`,
  `ServiceAuthenticationFilter` — is a `OncePerRequestFilter`, which never runs in a reactive chain
  even if it did.

Nothing was broken at runtime, because nothing had tried yet. What existed was a plan that could
not be executed, written in a pom comment where nobody would look for it.

### What the code actually says today

Measured rather than remembered, across both repositories:

| | this repository | xenopsbase-stemcell |
|---|---|---|
| reactive types in `services/` | **none** — no `Mono`, `Flux`, `WebClient`, `ServerWebExchange` | `gateway` only |
| servlet modules | `identity`, `catalog`, `reporting`, `streaming` | `core` |
| reactive modules | none | `gateway`, forced by `spring-cloud-starter-gateway-server-webflux` |

The stemcell is not "a reactive platform". It is a servlet platform with one reactive process at
the edge, and the reason that process is reactive is that Spring Cloud Gateway gave it no choice.
Which means this repository has been following the stemcell's actual convention all along — it
simply never had the edge.

### What is downstream of a gateway that does not exist

Four open tasks assume one, and none of them owns building it:

- **T-10.2 (#93)** — its entire premise is that a cookie-held session at the gateway keeps the
  access token out of a browser that also hosts uploaded SCORM packages (ADR-0105). Without a
  gateway the alternative is `localStorage`, which that issue exists to refuse.
- **T-8.7 (#85)** — rate limits per principal. Per-principal limiting behind no shared edge means
  four services each guessing at a global budget.
- **T-9.13 (#91)** — one request id across every hop. The first hop is the gateway.
- **T-1.4 (#20, closed)** — `StatusGateFilter`'s javadoc calls itself "a fast path and not the
  boundary", and `DeactivatedUserFilter`'s says "the gateway will front-run this for the whole
  platform". Both are written against an edge nobody has scheduled.

`ValkeyPermissions` states the position plainly: *"the gateway does not exist yet"*.

## Decision criteria

- Does the stack fit what the code actually does, rather than what a benchmark rewards?
- Does it survive the platform's existing invariants — the tenant, the permission set, the
  correlation id — without moving them to a new mechanism?
- Does a shared library remain shareable by **every** process, including one that cannot be servlet?
- Is the contract between the two stacks written down once, in a place both compile against?
- Does breaking any of the above fail a build rather than a deployment?

## Decision

**Three parts, and the third is the one that was missing.**

### 1. The eight modules are Spring MVC, on virtual threads

Not by inheritance any more — on these grounds:

- **Everything they do blocks.** JPA, JDBC with `ddl-auto: validate`, Flyway, a `RestClient` hop
  (T-9.11), a blocking Valkey read. Reactive over blocking persistence is
  `Mono.fromCallable(...).subscribeOn(boundedElastic())` — a thread pool with worse stack traces
  and no throughput gained.
- **The authorization design is `ThreadLocal` to its core.** `TenantContext`, `SecurityContextHolder`,
  T-2.4's request-scoped permission set, MDC correlation, `TenantTaskDecorator`. Porting that to
  Reactor Context is not a refactor with a compiler behind it; the failure mode of getting it
  wrong is an unbound tenant, and an unbound tenant on a permissive path is a cross-tenant read.
- **ADR-0109 is a memory decision, and reactive does not pay memory.** It saves threads. Threads
  were not the binding constraint; 4752Mi across six processes was.
- **The hot path already left the JVM.** ADR-0101 delivers video from Cloudflare's edge. What
  these processes serve is control-plane traffic: low concurrency, database-bound.

Virtual threads are what make that scale, and Java 25 is what makes them unconditional: JEP 491
removed the carrier pinning that a `synchronized` block used to cause, which is what Hikari and
the Postgres driver are full of. `spring.threads.virtual.enabled: true` is set in all four
services.

**The operational consequence is stated rather than discovered.** Tomcat's 200-thread pool was the
platform's real admission control and it is now gone — every accepted request gets a thread. The
queue moves to `maximum-pool-size`, which makes the connection pool a capacity decision rather
than a courtesy to Postgres. That is the better place to queue, because it is the resource that is
actually scarce, but it is a different place than yesterday.

### 2. The gateway is one reactive process, and it is ours

`gateway` becomes a module in **this** repository (T-9.17), forked from the stemcell's and adapted
to our realm, our routes and our tenancy — not a dependency on the stemcell's running instance.

The stemcell's gateway is a backend-for-frontend that already solves the hard parts: an OIDC
session in Valkey rather than a JVM heap, correlation ids propagated through the Reactor context,
and circuit breakers whose window was changed from count-based to time-based on measured evidence
(stemcell T-5.10, after 293 requests were discarded per real failure at 1200 req/s). Rewriting
that would discard the measurement; adopting it keeps ADR-0109's 533Mi figure meaningful, because
it is the same process.

Forked rather than shared for the reason [ADR-0109](0109-eight-modules-six-processes.md) already
gives about repository boundaries and the stemcell fork gives about versions (T-9.4): a shared
running gateway would put this product's edge on another product's release cycle, and its realm,
its routes and its rate limits are ours.

### 3. `platform-common` splits in two, along the line the stacks actually differ on

| module | holds | depended on by |
|---|---|---|
| `platform-common` | the contract and everything that does not touch a request: `TenantContext`, `AccountStatus`, `TenantStatusKeys`, `Correlation`, the outbox, the bus, mail | everything, including `gateway` |
| `platform-common-web` | everything that names a servlet request: the four filters, the `RestClient` hop, the internal probes, the blocking status lookup | the eight MVC modules |

**What is duplicated, deliberately:** two implementations of each cross-cutting filter, one per
stack. `TenantFilter` binds from a verified claim on MVC; a `WebFilter` will bind the same claim on
the gateway.

**What is not duplicated, and is the point:** the key `status:tenant:<id>`, the meaning of the
values under it, the `X-Correlation-Id` header, and the status enum. All of them now live in
`TenantStatusKeys`, `Correlation` and `AccountStatus` — in the module both stacks compile against
— and identity's publisher, the servlet lookup and the gateway's reader all name them from there.

That is not a tidiness argument. `PublishedStatusLookup`'s own javadoc records what happened the
last time this contract was implemented twice: a status gate that ran, found nothing under the key
it expected, logged a warning nobody read, and waved every request through, so **a suspension
stopped writes in `identity` and nowhere else**. It was found by T-3.4 reporting a suspended
company as ACTIVE. A gateway with its own private copy of that string is the same bug with a
larger blast radius.

**Enforced twice, because the two failures happen at different times.**
`PlatformCommonIsStackNeutralTest` fails when someone writes servlet code in the shared module.
The `enforce-no-web-stack` banned-dependencies rule fails when someone adds the starter *without*
writing any code — which is the case that would otherwise pass every test in this repository and
then stop the gateway from starting on a cluster.

## Consequences

### What this makes easy

A gateway at all. Everything in `platform-common` is available to it, so the edge can enforce
tenant status against the same key `identity` publishes, echo the same correlation id every
service logs, and refuse a suspended tenant one hop earlier than `StatusGateFilter` does today.

Keeping the modules boring. They stay imperative, debuggable and stack-trace-shaped, which is
what a codebase whose invariants are `ThreadLocal` should be.

### What this makes hard

Two filter chains to keep in agreement. The constants cannot drift, but the *behaviour* can: a rule
added to `TenantFilter` and not to its reactive twin is a real and unguarded risk. The mitigation
is that the gateway's checks are a superset applied earlier, never a different set — and T-9.12's
contract tests are where that stops being an intention.

One more artifact in the reactor, and one more decision when adding shared code: which half. The
question has a short answer — can it be written without naming a request — but it is a question
that did not exist yesterday.

### What it commits us to

The gateway being a process this repository builds, deploys and sizes. ADR-0109 budgeted
`gateway 2 × 533Mi` inside its six-process arithmetic, so this is accounted for rather than new —
but it is accounted for against a figure measured on the stemcell's cluster, and T-9.15 (#101) is
what turns it into ours.

Reversal is cheap in one direction and not the other. Merging the two library modules back is an
afternoon. Moving the gateway to a servlet stack after the routes, the session and the rate limits
are written is a rewrite of the part that is hardest to test.

## Alternatives considered

### Everything reactive, including the eight modules — rejected

The change that would have to be believed in is not WebFlux; it is dropping JPA. Reactive
persistence means R2DBC, which means no Hibernate, which means `TenantOwned`, `@TenantId`,
`TenantIdentifierResolver` and every repository in four services are rewritten — and the tenant
isolation that today is enforced by Hibernate becomes a predicate somebody has to remember to
apply. Keeping JPA and adding Reactor on top buys the complexity of both and the benefit of
neither.

### The gateway on Spring Cloud Gateway Server **WebMVC** — rejected, and this is the closest call

A servlet gateway on virtual threads is a real product and it would make this ADR two decisions
shorter: one stack, one shared library, one filter chain, no split.

It loses on what already exists. The stemcell's gateway is WebFlux, and its value is not the
routing — that part is configuration — but the parts around it: the OIDC session in Valkey, the
reactive refresh-token filter, the problem-detail entry point, and a circuit-breaker
configuration that was corrected against a measured 1200 req/s failure. On WebMVC that is a
rewrite of the security and resilience layers, which are the parts where a bug is a security
incident rather than a latency regression. It would also make ADR-0109's 533Mi a number about a
different process.

Worth reopening if the gateway turns out to need blocking work of its own, or if the two filter
chains prove harder to keep aligned than this ADR expects.

### Keep one `platform-common` and let the gateway re-declare what it needs — rejected

This is the option that requires no work, and it is the one with a failure already on the record.
The gateway would spell `status:tenant:` itself, and `PublishedStatusLookup`'s javadoc is a
description of what happens next, written by someone who had just finished cleaning it up.

### Depend on the stemcell's running gateway — rejected

It routes the stemcell's `core`, authenticates against the stemcell's realm, and belongs to
another product's release cycle. Commit `2fed6af` there already settled the shape of the
relationship: learn got its own namespace, its own databases, its own realm and its own Argo
application, and touched none of the gateway's routes. This repository operates none of that
infrastructure, but the edge in front of its own product is not infrastructure — it is the product.

## Revisit if

- T-9.15's measurement on our own cluster makes a ninth process not fit, in which case the merge
  of the gateway into another deployable is a sizing decision this ADR should not pre-empt.
- The two filter chains disagree in production once. That is the signal that the shared-constant
  mitigation is not enough and the behaviour needs a contract test (T-9.12), not a comment.
- Spring Cloud Gateway Server WebMVC reaches parity on the session and resilience pieces, which
  would make one stack cost nothing and remove the only reason this repository has two.
