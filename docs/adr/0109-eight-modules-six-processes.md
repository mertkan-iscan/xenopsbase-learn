# ADR-0109: Eight modules, six processes

- **Status:** Accepted
- **Date:** 2026-09-05
- **Accepted:** 2026-09-08, on the measurement T-9.15 ([#101](https://github.com/mertkan-iscan/xenopsbase-learn/issues/101)) owed it
- **Task:** T-0.9

**This was Proposed on borrowed numbers, and accepting it replaced nearly all of them.** The
decision below is unchanged — eight modules, six processes — and almost every figure that argued
for it is different, including the one the previous draft called decisive. That is worth saying at
the top rather than burying: a decision that survives its own re-measurement for new reasons has
not been confirmed, it has been re-derived, and the old reasoning is not available to quote.

What made the re-measurement possible is that "our cluster" and "the stemcell's dev cluster"
stopped being different things. T-9.3 put `identity`, `streaming` and `reporting` in a `learn`
namespace on that cluster, so the free-memory figure this ADR previously had to borrow as a proxy
is now simply ours to read.

ADRs here are append-only. This one was editable while Proposed and is not any more; what comes
after this goes in a new ADR that supersedes it.

## Context

The platform is being built as microservices deliberately, before it runs on any shared
infrastructure. That makes the service map the first decision: every other task assumes an answer,
and it is expensive to change once code exists.

The counter-argument stays on the record rather than being forgotten. Boundaries drawn before a
domain is understood tend to be drawn along the wrong lines, and each extra service costs a network
hop, a contract, a deployment and a failure mode before it returns anything.

Since this decision was first framed the argument has acquired numbers, then better numbers, and
then — on the third pass — numbers taken with the right instrument. What never changed is *whether*
to draw eight boundaries. What changed three times is the reason for running them in six processes.

## Decision criteria

- Does each module own its data outright, with no other module reading its tables?
- Does a boundary follow a real difference — scaling profile, availability, untrusted input —
  rather than a noun?
- Does the process count fit dev's **measured** capacity — on both of the two constraints below,
  which are different questions and give different answers?
- Does it leave the cluster-autoscaler somewhere to go, given that its pool is capped at two nodes
  and the gateway's one-replica-per-node rule already claims all of them?
- Can a boundary be enforced without a network hop, which is what makes splitting later cheap?

## The two constraints, which are not the same question

Every draft of this ADR has answered one of these and believed it had answered both.

**Scheduling.** The sum of pod **requests** against **allocatable**. This is the only arithmetic
the scheduler does. A pod that does not fit here goes `Pending`, and `Pending` is the one signal
the cluster-autoscaler listens to.

**Survival.** **Committed** memory against **physical** memory. A node that runs out here does not
go `Pending`, it degrades — which is the stemcell's T-1.12, where a worker filled up, Argo's
repo-server started failing its probes, and committed changes silently stopped arriving. A fix
not working, three layers from the cause.

On this cluster they give opposite answers, and by a wide margin. Quoting either alone is how both
previous drafts went wrong, in opposite directions.

## The measurement

Taken on the running dev cluster, **2026-09-08**, by
[`scripts/capacity_reading.py`](../../scripts/capacity_reading.py). Read-only: `get nodes`,
`get pods -A`, `top`, and one Prometheus query through the API server's service proxy.

### None of the 2026-08-27 figures could be updated in place

Two were read with the wrong instrument and the third had moved underneath them. Correcting them
one at a time would have produced a table whose rows came from different methods, so the whole
measurement was retaken.

**1. `kubectl top` was the wrong instrument, and it exaggerates in one direction only.** It counts
reclaimable page cache as memory spent, and divides by an allocatable that moves. At the instant
this reading was taken it reported `worker-0` at 90% and `worker-1` at 92%; the same two nodes,
at the same instant, were at **53%** and **49%** of physical memory actually committed. The
previous draft's entire argument — "actual usage, worst of three readings, 11217Mi" — is that
column. The stemcell had already found this and written it down in
`infra/scripts/check-node-memory.sh` (its T-2.27, #341), where the two denominators disagreed by
thirty points; this repository went on using the discredited one for two more weeks because
nothing connected the two.

**2. Allocatable fell by 1250Mi per worker, and nothing here noticed.** This ADR recorded
**7153Mi** per cx33 worker. It is **5903Mi** — the stemcell raised the kubelet's reservations in
its T-2.28 (#367). That is 2500Mi off the pair, taken out of the exact quantity every line of the
old arithmetic divided into. Nothing in this repository was watching, which is why
[`scripts/verify_capacity.py`](../../scripts/verify_capacity.py) now is.

**3. The per-service floor was `core`'s, and `core` is not one of ours.** 605Mi was measured on the
stemcell's own service and used here for ours because there was nothing else to use. Ours are
smaller, and not by accident: a JVM sizes its heap from the container **limit**, and ours is 896Mi
where `core`'s is 1Gi. A "what a Spring Boot process costs" figure is not a property of Spring
Boot. It is a property of the limit somebody chose.

### Capacity, and what is actually on it

Per fixed cx33 worker: **7753Mi physical, 5903Mi allocatable**. The 1850Mi difference is the
kubelet's reservations, and it is the number that moved.

Three readings, spread across the evening, because the previous draft learned that one is a
snapshot: it recorded a single sample as a constant and the quantity moved 810Mi within the hour.
**The figure this ADR uses is the worst of them.**

| | 18:24Z | 19:09Z |
|---|---|---|
| `worker-0`, committed | 4226Mi (55%) | 4220Mi (54%) |
| `worker-1`, committed | 3922Mi (51%) | 4005Mi (52%) |
| fixed pair, committed | 8149Mi | 8225Mi |
| **actually free** | 7357Mi | **7281Mi** |
| fixed pair, booked | 9862Mi | 9862Mi |
| **free to schedule into** | 1944Mi | 1944Mi |
| *the same nodes by `kubectl top`* | *89% and 93%* | *92% and 97%* |

The last row is the instrument the previous draft ran on, kept here as a control rather than as a
measurement. It reads 89–97% on nodes that are half full.

The booked figure does not move at all, because requests are declared and nothing was deployed
between readings. The committed figure moves by 76Mi, which is what a settled cluster's noise looks
like — much smaller than the 810Mi the earlier ADR saw, and the difference is that the pod
responsible for that swing is not the one being measured here.

The control plane is tainted and carries nothing of ours, so it is excluded throughout.

### The two headrooms, at floor

| | fixed pair |
|---|---|
| allocatable | 11806Mi |
| booked (requests) | 9862Mi |
| **free to schedule into** | **1944Mi** |
| physical | 15506Mi |
| committed | ~7900Mi |
| **actually free** | **~7600Mi** |

**Four times as much memory is free as the scheduler will let anything use.** That single line is
the finding, and it inverts the previous draft: there is no shortage of memory on these workers.
There is a shortage of *bookable* memory, and booking is something we choose.

### What the platform underneath books

On the two fixed workers, everything that is neither the stemcell's `apps` nor our `learn`:

| namespace | booked |
|---|---|
| `observability` | 3572Mi |
| `argocd` | 1472Mi |
| `keycloak` | 1346Mi |
| `database` | 512Mi |
| `cert-manager` | 240Mi |
| `cache` | 224Mi |
| `cnpg-system` | 192Mi |
| `ingress-nginx` | 192Mi |
| `cosign-system` | 128Mi |
| `messaging` | 64Mi |
| **total** | **7942Mi** |

**Observability alone books more than every application on the cluster put together.** The platform
takes 67% of the fixed pair's allocatable before the first service of either product is scheduled.
This is the term that decides the process count, and no previous draft of this ADR contained it —
both earlier versions reasoned about what a JVM costs, which turns out to be the small number.

### What one of our processes costs

Measured on the deployed pods, not borrowed:

| | `identity` | `streaming` | `reporting` |
|---|---|---|---|
| request, as measured on | 640Mi | 640Mi | 640Mi |
| limit | 896Mi | 896Mi | 896Mi |
| **cold, just started** | — | — | **278Mi** |
| **warm, idle** | **370Mi** | **342Mi** | **339Mi** |
| **under load** | — | — | **345 → 367Mi, but see below** |

**A JVM does not give heap back**, so the warm figures are the honest floor for a process that has
served traffic, and the 278Mi a freshly started one shows is not.

### Under load is NOT measured, and the reason is a defect rather than a shortage of effort

The criterion T-9.15 states is "a real per-service floor measured for one JVM at rest and one under
load". The first half is above. The second half could not be taken, because **this service cannot
currently serve load on this cluster**, and finding out why was worth more than the number would
have been.

Three runs against the deployed `reporting`: 16 and 64 concurrent through a `kubectl port-forward`,
then 12 concurrent from a pod inside the cluster, which removes the port-forward from the argument
entirely. All three behaved the same way:

| | 12 concurrent, in-cluster |
|---|---|
| Offered | 436 batches over 420s, **436 accepted, 0 refused** |
| Throughput | **~1 request/second** — about 12 seconds per request |
| Container CPU | **20–60m** against a 100m request |
| Resident set | 345Mi, climbing steadily to **367Mi** |

**Twenty millicores is not a busy process.** It is a blocked one, and every request was still
accepted — 436 of 436 — which is why nothing downstream noticed either. Every request logs
`Could not read the status entry for tenant acme; this service is permissive until Valkey returns`,
and from a pod in `learn` a TCP connection to `valkey-cache.cache.svc.cluster.local:6379` is
**refused in 12ms**, while Postgres, NATS and this service's own port all connect from the same pod.
Valkey itself has been `Running` for five hours, has never restarted, and its log contains nothing
but its startup banner.

So the per-request tenant-status lookup fails on every request, the failures serialise, and latency
grows with concurrency until — at 64 — even the liveness probe missed its deadline and the kubelet
restarted the container. The degradation the service was built for did fire and did log; what it did
not do was keep the process alive.

Nothing reported any of this. `/management/health` has been answering **503 DOWN** the whole time,
while the `liveness` and `readiness` groups — which exclude the cache — answer 200 in 7ms, so the
pod is `Ready`, Argo is green, and the permission cache T-2.5 exists for has never once been read on
this cluster.

**What this ADR takes from it:** the 367Mi figure is recorded as "under load" in the loosest sense
and is not leaned on. The right-sized request below uses **406Mi**, the worst sample ever taken,
precisely because the honest under-load number does not exist yet. Raised as a defect against the
platform; this ADR's arithmetic does not depend on its resolution, and the criterion stays open.

**We booked 640Mi for a process that uses 370Mi**, and the manifests cited the borrowed 605Mi as
the reason. Since the scheduler sees only the request, that 270Mi × 3 of over-booking was not
caution — it was 810Mi of the fixed pair's 1944Mi of bookable memory, spent on nothing. The
request is now **512Mi**: above every sample ever taken including the 406Mi worst, and below the
896Mi limit by enough to keep a migration or a burst inside it.

## What that does to the arithmetic

### Scheduling: what will fit on the two fixed workers

```
allocatable, two fixed cx33 workers            11806Mi
the platform underneath                       - 7942Mi
the stemcell's own services at their floor    - 2112Mi   gateway 2x640 + core 832
kept free so an ordinary platform pod can land - 512Mi   256Mi/worker, stemcell #368
                                              ─────────
left for us                                     1240Mi
```

Against that, six processes is **six pods** — `gateway` at two replicas, plus `core`, `streaming`,
`packaging` and `reporting`; `frontend` is a static build served from the edge and costs the
cluster nothing.

| | at the 640Mi request this was measured on | at the right-sized 512Mi request |
|---|---|---|
| **six processes** | 3840Mi — **short by 2600Mi** | 3072Mi — **short by 1832Mi** |
| **eight processes** | 5120Mi — short by 3880Mi | 4096Mi — short by 2856Mi |

**Six processes do not fit the two fixed workers, and no achievable request makes them fit.** Even
a request set exactly at the warm idle figure — 370Mi, which would be an under-request the moment
the service did any work, and is listed only to bound the argument — leaves six processes 980Mi
short. Right-sizing to 512Mi closes 768Mi of a 2600Mi gap: worth doing on its own terms, and not
enough to change the answer. Eight is further out still.

That is not a hypothetical. `core` and two of our three services are running on an **autoscaled**
node as this is written, and that node has been up since the cluster was built and cannot drain,
because it is not empty. Dev is a three-node cluster at floor, today, and the stemcell's
`dev.tfvars` still describes that pool as one that "is empty and costs nothing until the HPAs ask
for replicas". Raised upstream; ours to account for, not to fix.

### Survival: what the machines will actually carry

The same six processes, at their measured 370Mi rather than at whatever they book, are 2220Mi of
real memory against roughly 7600Mi genuinely free on the fixed pair. **Eight would fit too.** On this
axis there is no argument for six over eight, and the previous draft's "56Mi margin" — the figure
it called decisive — was an artifact of the instrument.

### So the constraint is the node budget, not the memory

Both constraints have now been answered honestly, and neither of them rejects eight processes on
memory. What rejects it is the third node, and the fact that there is only one more.

The autoscaler pool is `max_nodes = 2`. One of those two is already consumed at floor. The
gateway's one-replica-per-node anti-affinity means four gateway replicas need four untainted nodes
— two fixed plus both autoscaled — so at the HPA ceiling the pool is fully claimed with nothing
left over.

Splitting `identity`, `catalog` and `assessment` into three processes adds two more pods. At the
measured floor that is ~740Mi, which the *machines* have and the *booking* does not, so it would be
absorbed by the second autoscaled node — the one the gateway needs. The failure that produces is
not an out-of-memory: it is a pod stuck `Pending` while the autoscaler asks for a node it is not
allowed to create, which reads as a scheduling problem and not as a capacity decision made
eighteen months earlier.

**Six, therefore, because the node budget is spoken for — not because a JVM is expensive.**

## Decision

**Eight modules. Six processes.** `identity`, `catalog` and `assessment` start inside one
deployable called `core`; the other five modules are their own.

| Module | Owns | Separate process at dev sizing? |
|---|---|---|
| `gateway` | Edge routing, sign-in, session, rate limiting, tenant status | **yes** — exists |
| `frontend` | Learner app, admin console, authoring, embeddable player | **yes** — static, free |
| `streaming` | Video assets, upload targets, encode state, playback tokens | **yes** — the learner hot path |
| `packaging` | Archive extraction, manifest parsing, rasterisation | **yes** — security before capacity: it runs untrusted uploaded code and must not share a heap with a session (ADR-0105) |
| `reporting` | Telemetry ingest, rollups, reports, exports | **yes** — must fail without stopping playback |
| `identity` | Tenants, users, groups, roles, permissions | inside `core` |
| `catalog` | Content items, courses, modules, gates, assignments | inside `core` |
| `assessment` | Banks, questions, tests, forms, attempts, grading | inside `core` |

`packaging` and `reporting` are separate for reasons that were never about memory and are not
affected by any of the above: one runs untrusted uploaded code (ADR-0105), the other must be able
to fail without stopping playback. Those two would survive a cluster of any size.

### Data ownership, stated once

**A module owns its tables outright, and no other module reads them.** Not by convention: each
module has its own database and its own role, so a cross-module query fails to connect rather than
returning the wrong answer. That is already true in the local stack and on the cluster for the
three services that exist.

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

Fitting dev inside a node budget that is already fully claimed at the HPA ceiling. Splitting later:
separate databases, separate migrations and an enforced boundary mean an extraction is a deployment
change and a client swap.

### What this makes hard

`identity`, `catalog` and `assessment` cannot be deployed, scaled or restarted independently while
merged. A memory leak in one takes the other two with it, and `core` becomes the largest blast
radius in the platform.

### What it commits us to

Enforcing the boundary while nothing forces it. A merged process makes a shortcut *compile*, which
is exactly why the ArchUnit rule exists rather than a paragraph asking people not to.

And keeping these numbers honest. Every figure above has a date because every one of them has
already moved once. `scripts/verify_capacity.py` fails the build when the manifests drift from the
measurement this decision was made on, and `--cluster` fails when the cluster does — because the
1250Mi that went missing from allocatable was in no diff, no review and no alert.

## Alternatives considered

**Eight processes — rejected on the node budget, and no longer on memory.** The previous draft
rejected it on a 56Mi margin, and that margin was an artifact of `kubectl top`. On measured memory
eight processes fit comfortably. What they do not fit is `max_nodes = 2` with one autoscaled node
already consumed at floor and the gateway's anti-affinity claiming both at its ceiling. If that
budget changes, this alternative comes back, and it comes back as the front-runner rather than as a
distant second.

**One process for everything — rejected.** `packaging` runs untrusted uploaded code and must not
share a heap with a session (ADR-0105); `reporting` must be able to fail without stopping playback.
Both are security or availability boundaries, and neither is negotiable for memory.

**Bigger dev workers (cx33 → cx43) — still available, and now the cheapest lever.** It buys 16GB
per worker and roughly doubles the hourly rate. The case for it is stronger than it was: dev is
already running a third node continuously, which is being paid for anyway, and the binding term is
the platform's 7942Mi rather than anything of ours. It stays rejected only because the constraint
is node *count* and not memory, and a bigger node does not create one — the gateway needs four
nodes for four replicas whatever they are made of.

**Native images, or a non-JVM `packaging` — deferred.** ~370Mi to ~120Mi each is a real gain, at the
cost of build complexity, reflection configuration and a second toolchain. The previous draft put
this at ~600Mi to ~120Mi; the real saving is smaller than it looked, and it is still the largest
single one available. Worth revisiting when the process count binds on memory, which it does not.

**Right-sizing the requests to the measured floor — done, and it does not change this decision.**
It returns 810Mi of bookable memory on the fixed pair and makes the manifests say something true.
It closes 1620Mi of a 2600Mi gap. It is worth doing and it is not an alternative to anything.

## Revisit if

**Extract a module from `core` when** it needs to scale on a different axis than the other two, or
when its restart frequency starts costing the others availability — whichever is observed first,
measured rather than anticipated.

**Merge two separate services back when** the pair is never deployed independently for a full
quarter *and* their combined actual usage would leave dev with more margin than the merge costs in
blast radius.

Both are falsifiable in both directions, which is the point: a decision that can only be revisited
in the direction of more services is not a decision.

**Re-measure whenever the node budget or the platform's own requests change**, which is a narrower
trigger than the previous draft's and a better one. The platform's 7942Mi is the term that decides
this; a JVM's 370Mi is not. `scripts/verify_capacity.py --cluster` is what notices, and it is meant
to be run rather than trusted to have been.
