# ADR-0109: Eight modules, six processes

- **Status:** Proposed
- **Date:** 2026-09-05
- **Task:** T-0.9

**Proposed, not Accepted, and the distinction is load-bearing.** Every figure below was measured on
the *stemcell's* dev cluster, because ours did not exist when the measurement was taken. What
transfers from another cluster of identical sizing is what one Spring Boot service costs and what
the platform underneath consumes; the absolute free figure does not, and is used here as a proxy.
T-9.15 (#101) re-measures on our own cluster and is what moves this to Accepted. ADRs here are
append-only, so this one stays editable precisely until then.

## Context

The platform is being built as microservices deliberately, before it runs on any shared
infrastructure. That makes the service map the first decision: every other task assumes an answer,
and it is expensive to change once code exists.

The counter-argument stays on the record rather than being forgotten. Boundaries drawn before a
domain is understood tend to be drawn along the wrong lines, and each extra service costs a network
hop, a contract, a deployment and a failure mode before it returns anything.

Since this decision was first framed the argument has acquired numbers, and then better numbers.
What changed is not *whether* to draw eight boundaries — it is **how many processes to run them
in**, and the measured answer is stricter than the estimated one.

## Decision criteria

- Does each module own its data outright, with no other module reading its tables?
- Does a boundary follow a real difference — scaling profile, availability, untrusted input —
  rather than a noun?
- Does the process count fit dev's **measured free memory**, not its declared requests, with enough
  margin that the autoscaler still has somewhere to go?
- Can a boundary be enforced without a network hop, which is what makes splitting later cheap?

## The measurement

Taken on the stemcell's running dev cluster, 2026-08-27, across three readings an hour apart.
Read-only: `get nodes`, `get pods -A`, `top node`, `top pods`.

**Capacity.** The control plane is tainted (`allow_scheduling_on_control_plane` deliberately
unset), so only the workers count: **7153Mi allocatable each, 14306Mi total**.

**Usage, and it is not a constant.**

| | 22:05Z | ~22:40Z | 23:00Z |
|---|---|---|---|
| `worker-0` | 5007Mi (70%) | 5837Mi (81%) | 5825Mi (81%) |
| `worker-1` | 5400Mi (75%) | 5348Mi (74%) | 5392Mi (75%) |
| **free of allocatable** | **3899Mi** | **3121Mi** | **3089Mi** |

The middle column is another session's independent re-measurement. It is here because without it a
single reading would have been written down as a constant, and it is not one.

### A claim that was in an earlier draft and was wrong

That draft read the Argo CD controller pod at 861, 871 and 944Mi across the hour and called the
climb monotonic — evidence of growth. A fourth reading taken one minute after the third came back
at 901Mi. **43Mi of spread inside one minute, against 83Mi across the whole hour**: the "trend" was
noise the sampling was too sparse to see. A Go RSS wandering between roughly 860 and 950 on an idle
cluster is what unhurried garbage collection looks like, and four points cannot separate that from
slow growth.

What survives is not ambiguous, and is worse than the drift would have been:

```
resources: {"requests":{"cpu":"100m","memory":"256Mi"}}
```

**No limits at all** on `argo-cd-argocd-application-controller-0` — not too low, absent — against
860–950Mi of actual use. The scheduler places it at a third of its size and then nothing bounds it.
Upstream's to fix ([stemcell#306](https://github.com/mertkan-iscan/xenopsbase-stemcell/issues/306));
ours to account for, because it means the platform overhead under our services has no ceiling.

**What that does to every number here.** If one pod's one-minute noise is 43Mi, then 3899 / 3121 /
3089 are three samples of a moving quantity, and the honest statement is a band rather than a
figure: **free memory on this sizing sits near 3.1–3.9GB and is not stable.** The arithmetic below
uses the worst sample, which is a floor rather than a measurement, and any decision resting on a
margin smaller than the observed spread is resting on nothing.

**What one service costs.** Requests are the scheduling floor; these are what the processes use at
idle, and they are the figures that transfer:

| | request | limit | **actual** |
|---|---|---|---|
| `core` | 832Mi | 1Gi | **605Mi** |
| `gateway` | 640Mi | 768Mi | **533Mi / 525Mi** |

## The two things the estimate got wrong

**1. Argo CD is under-requested by roughly 2×.** An earlier estimate put it at ~1024Mi of requests.
It requests **576Mi** and uses **1090Mi** — 861Mi of that in the application controller alone.

**2. Total actual usage exceeds total requests by 2.6GB.** 7766Mi requested against 10407Mi used.
Argo CD and Grafana are most of the gap.

That second line changes the decision, and it is invisible to any calculation done from manifests.
**Kubernetes schedules on requests; the node dies on usage.**

## What that does to the arithmetic

Two different answers, and only one of them matters.

**By requests** — what the scheduler accepts — six more JVMs at their 832Mi request is 4992Mi
against 8652Mi of unrequested allocatable. It fits comfortably. This is the calculation the earlier
draft did, and it is the wrong one.

**By actual usage** — what the node survives. The stemcell's `core` and `gateway` are the
equivalent of ours, so they are subtracted rather than added to — equivalent in *cost*, which is
all this arithmetic needs. Whose gateway it is was left ambiguous here and is settled by
[ADR-0111](0111-servlet-modules-and-one-reactive-edge.md): ours, built in this repository as
T-9.17, forked from the stemcell's. The 533Mi below is a measurement of that same process, so
the figure survives the correction; the module table's "exists" did not:

```
allocatable                              14306Mi
actual usage, worst of three readings    11217Mi
                                        ─────────
free                                      3089Mi
+ the apps namespace we would replace     1663Mi
                                        ─────────
available to our services                 4752Mi
```

Against that:

| | arithmetic | verdict |
|---|---|---|
| **eight processes** | gateway 2 × 533 + six JVMs × 605 = **4696Mi** | 56Mi margin. **Does not fit.** |
| **six processes** | gateway 2 × 533 + four JVMs × 605 = **3486Mi** | 1266Mi margin. Workable. |

`frontend` is zero in both rows: a static build served from the edge costs the cluster nothing,
which makes that particular separation free.

**Eight processes do not fit.** Not "fit tightly" — a 56Mi margin on a cluster whose free memory
moved 810Mi during the hour it was measured is not a margin at all.

The failure at the far side of that is documented in the stemcell's own `dev.tfvars`: at 93% memory
Argo's repo-server lost its probes and committed changes silently stopped arriving — a fix not
working, three layers from the cause. `worker-0` is at 81% today with two services on it.

## Decision

**Eight modules. Six processes.** `identity`, `catalog` and `assessment` start inside one
deployable called `core`; the other five modules are their own.

| Module | Owns | Separate process at dev sizing? |
|---|---|---|
| `gateway` | Edge routing, sign-in, session, rate limiting, tenant status | **yes** — ours to build, T-9.17 |
| `frontend` | Learner app, admin console, authoring, embeddable player | **yes** — static, free |
| `streaming` | Video assets, upload targets, encode state, playback tokens | **yes** — the learner hot path |
| `packaging` | Archive extraction, manifest parsing, rasterisation | **yes** — security before capacity: it runs untrusted uploaded code and must not share a heap with a session (ADR-0105) |
| `reporting` | Telemetry ingest, rollups, reports, exports | **yes** — must fail without stopping playback |
| `identity` | Tenants, users, groups, roles, permissions | inside `core` |
| `catalog` | Content items, courses, modules, gates, assignments | inside `core` |
| `assessment` | Banks, questions, tests, forms, attempts, grading | inside `core` |

### What the merge actually costs, measured

**This ADR priced the merge at nothing, and that was wrong.** The claim above — "the expensive
part of splitting a service is untangling a schema, and there is nothing to untangle" — is true,
and it is about *splitting*. Merging costs something different, and none of it is schema work.
Recorded here from an attempt at it (T-9.17), because the estimate is what the plan rests on:

| Cost | Size | Status |
|---|---|---|
| **Migration namespace.** Every module numbers from `V1`, so `classpath:db/migration` in one process resolves across both jars and Flyway refuses to start on duplicate versions. identity and catalog share `V1`–`V8`. | 21 files relocated to `db/migration/<module>` | done |
| **Two transaction managers.** One process, two databases. An unqualified `@Transactional` binds to whichever is primary, and a repository from the other module then opens its own session *outside* that transaction — no error, no failing test, no atomicity. | 78 annotations qualified; neither manager primary, so the mistake throws | done |
| **The outbox is per-database.** A transactional outbox only guarantees anything inside one database, so a merged process needs two outboxes, two relays and two sets of backlog gauges. `MessagingConfiguration` injected one `DataSource` and one `PlatformTransactionManager` by type. Its meters were untagged, so the second module's backlog would have registered as the first's and been invisible. | per-module wiring extracted to `ModuleMessaging`; meters tagged | done |
| **Shared vocabulary collides in bean names.** `identity.authz.AssignmentService` and `catalog.assign.AssignmentService` both want to be `assignmentService`. Same clash as `/api/v1/assignments` at the gateway, one layer down. | `FullyQualifiedAnnotationBeanNameGenerator` | done |
| **24 `DataSource` injections by type.** Twelve classes in each module take a `DataSource` or build a `JdbcTemplate` from one. With a primary DataSource every one of catalog's would silently read identity's database. | qualification of all 24 | **not done** |
| **`EntityManagerFactoryBuilder` is unavailable.** Boot's JPA auto-configuration is `@ConditionalOnSingleCandidate(DataSource.class)`, so two DataSources with no primary make it back off entirely — and a primary is what the row above rules out. The builder has to be constructed explicitly, replicating the vendor adapter and naming strategies `HibernateJpaConfiguration` supplies. | explicit construction in `core` | **not done** |

**One service identity, and it is visible.** `identity` presented `svc-identity` and `catalog`
presented `svc-catalog` (T-9.11). One process presents one, so calls that were attributable to a
module are attributable to `core`. Audit rows and `whoami` say `svc-core`, and a Keycloak client
for it has to exist before this starts against a real realm.

**None of this changes the decision** — six processes still fit and eight still do not, and the
first four rows are done and are improvements whether or not the merge lands. What changes is the
estimate: this is a multi-session piece of work with two silent-failure modes in it, not a
packaging change.

### Data ownership, stated once

**A module owns its tables outright, and no other module reads them.** Not by convention: each
module has its own database and its own role, so a cross-module query fails to connect rather than
returning the wrong answer. That is already true in the local stack for the three services that
exist.

The three modules merged into `core` keep **separate databases and separate migration histories**
even while sharing a process. This is what makes the deferral a deferral rather than a retreat: the
expensive part of splitting a service is untangling a schema, and there is nothing to untangle.

The version of this that fails is the one where "we'll split it later" means one schema and
free-form calls between packages. `TechnicalStructureTest` therefore fails a build in which one
module reaches into another's internals rather than its published interface — written now, while it
is vacuous, because the day it stops being vacuous is the day it is too late to add.

### Where learner progress lives

**The module that observes the evidence owns the record of it; `catalog` owns what a node's
progress means.**

`streaming` owns watched intervals and the completion it derives from them (ADR-0107, T-3.7),
because it is the only module that sees playback. `packaging` and `assessment` own theirs for the
same reason. None of them knows what a *course node* is.

`catalog` folds those into node state, because a node is its concept and completing one may require
evidence from several modules. It learns by **event**, never by reading another module's tables, and
never by a synchronous call on the hot path — a gate evaluated by calling three services is a gate
that fails when any of them is slow, on the screen a learner looks at most.

`reporting` aggregates from the same events and calls nobody, which
[`docs/reporting-inputs.md`](../reporting-inputs.md) already states in full.

The cost is stated rather than hidden: a learner who finishes a video sees the next node unlock a
moment later rather than instantly, bounded by event delivery. That is the price of not putting
three network calls on a gate evaluation, and it is the right side of the trade.

### Whether `catalog` calls `identity` per request

**Neither, today — they are in the same process, and the call goes through `identity`'s published
interface anyway.**

Resolving a group's members is the chattiest cross-module interaction in the product and it is on
the hot path (T-5.5 targets an assignment at a person, a group or a company; T-1.3 owns the tree).
The merge makes it an in-process call today, which is why it costs nothing to defer the question.

What must not happen is `catalog` reading `user_group` directly, because that is the one thing that
would make the later split expensive. Going through the interface means the answer changes from
"call it" to "subscribe to it" without changing a caller.

**When they split, `catalog` subscribes** and keeps a projection, accepting the staleness that
`reporting-inputs.md` already documents for the same data.

### Whether learners reach `assessment` directly

**Directly for the attempt; `catalog` decides entitlement first.** The same shape as playback
(T-3.4): the assignment and gate check happens once, produces a short-lived grant, and the learner
then talks to `assessment` without a proxy in the middle.

Routing every answer submission through `catalog` would double the hop count on the most
latency-sensitive interaction in the product, and an attempt has a server-side clock (T-6.5) that a
proxy's variance shows up in directly. Proxying also puts `catalog` on the critical path of an exam,
where its availability becomes the exam's.

## Consequences

### What this makes easy

Fitting dev with a margin the autoscaler can move inside. Splitting later: separate databases,
separate migrations and an enforced boundary mean an extraction is a deployment change and a client
swap.

### What this makes hard

`identity`, `catalog` and `assessment` cannot be deployed, scaled or restarted independently while
merged. A memory leak in one takes the other two with it, and `core` becomes the largest blast
radius in the platform.

### What it commits us to

Enforcing the boundary while nothing forces it. A merged process makes a shortcut *compile*, which
is exactly why the ArchUnit rule exists rather than a paragraph asking people not to.

## Alternatives considered

**Eight processes — rejected on measurement, not on principle.** 56Mi of margin against 810Mi of
observed movement is not a margin. It would have been chosen on the request-vs-request calculation,
which is the specific error this ADR exists to correct.

**One process for everything — rejected.** `packaging` runs untrusted uploaded code and must not
share a heap with a session (ADR-0105); `reporting` must be able to fail without stopping playback.
Both are security or availability boundaries, and neither is negotiable for memory.

**Bigger dev workers (cx33 → cx43) — rejected for now.** It buys 16GB and roughly doubles the hourly
rate. Since the cluster is destroyed between sessions the real figure is per-day rather than per-
month, so this stays available; it is simply not needed at six.

**Native images, or a non-JVM `packaging` — deferred.** ~600Mi to ~120Mi each is the largest single
gain here, at the cost of build complexity, reflection configuration and a second toolchain. Worth
revisiting when the process count is the binding constraint rather than the platform's own
under-requests.

**Fixing the platform's under-requests first** is not an alternative but a prerequisite for any of
the above being decided on arithmetic: up to ~2600Mi of the numbers here are wrong until it is done.
It belongs upstream in the stemcell.

## Revisit if

**Extract a module from `core` when** it needs to scale on a different axis than the other two, or
when its restart frequency starts costing the others availability — whichever is observed first,
measured rather than anticipated.

**Merge two separate services back when** the pair is never deployed independently for a full
quarter *and* their combined actual usage would leave dev with more margin than the merge costs in
blast radius.

Both are falsifiable in both directions, which is the point: a decision that can only be revisited
in the direction of more services is not a decision.

**Re-measure whenever the platform's own requests change.** Every figure here moves with them, and
the arithmetic above is only as good as its worst sample.
