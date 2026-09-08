# Measured figures

Numbers taken from runs, with the conditions that produced them. **A figure without its
conditions is not a measurement**, so every entry here says what was running, on what, and what
the number does not mean.

The distinction this file exists to keep is the one [ADR-0109](adr/0109-eight-modules-six-processes.md)
had to learn: a figure derived from configuration is arithmetic, and a figure taken from a running
system is a measurement. Only the second kind belongs here.

## Telemetry ingest — playback heartbeats

**Task:** T-3.6 · **Replaces:** [ADR-0107](adr/0107-completion-is-derived-by-the-server.md)'s
estimate of ~500 posts/second at 5,000 concurrent learners, which the ADR marks *estimated, not
measured*.

**The load.** 5,000 concurrent learners, each posting one batch of 10 samples every ten seconds —
500 posts/second and 5,000 samples/second, offered continuously for ten seconds. Paced, not fired
at once: 5,000 simultaneous connections is a thundering herd nobody is claiming to support, and
measuring it would say nothing about the load that was specified.

**Run it:** `mvn -f services/pom.xml -pl reporting test -Dexcluded.test.groups= -Dtest=IngestLoadTest`

### What was measured, 2026-09-04

| | |
|---|---|
| Offered | 500 posts/second for 10s |
| Accepted | 5,000 of 5,000, **0 refused** |
| Rows written | 50,000 |
| Achieved | 500 posts/second (≈5,000 samples/second) |
| Request latency p99 | 1.6 – 2.5 s |
| Request latency p50 | **25 ms – 1,039 ms** (see below) |

**Conditions.** A developer laptop running, at the same time: the load generator, the service
under test, and the Postgres it writes to (Testcontainers, in Docker Desktop on Windows). All
three compete for the same cores.

**What this shows.** The ingest path sustains the specified rate and drops nothing. Every post was
accepted, every sample was written, and no batch was shed or errored. That is the property the
task exists for — losing a heartbeat is survivable, losing all of them silently is not.

**What this does not show.** Anything about capacity. It is a floor, not a ceiling, on hardware
that is not the target and with the generator stealing from the thing it is measuring. "The design
is not obviously wrong at this rate" is the honest reading; "the platform supports 5,000 learners"
is not.

### The connection pool, and why it is still 10

The service's shape argues for a larger pool than the template's 10 — every request is a short
write and there are hundreds per second, so the pool is a throughput limit rather than a safety
margin. The obvious experiment was run: three runs at 10, three at 32.

| Pool | p50 latency across runs | p99 |
|---|---|---|
| 10 | 426 ms, 485 ms, 651 ms | ≈2.2 s |
| 32 | 36 ms, 25 ms, 286 ms | ≈1.7 s |

That looks decisive, and it is not. A later run at **pool 32** measured **p50 1,039 ms** — worse
than every run at 10. Median latency at a *fixed* pool size ranges from 25 ms to 1,039 ms on this
machine, which is wider than the difference being looked for.

So the pool stays at 10. Raising it on evidence this weak would be a guess wearing a
measurement's clothes, and the reasoning would then be quoted later as though it had been
established. The experiment is worth repeating on hardware that is not also running the load
generator and the database; until then the number is unresolved rather than validated.

Every post was accepted at both settings. The pool was affecting latency, not correctness.

## Watched intervals — how many fragments, and what a merge costs

**Task:** T-3.7 · **Answers:** [ADR-0107](adr/0107-completion-is-derived-by-the-server.md)'s two
open numbers, the fragment cap and the distribution real learners produce.

**What this is, and what it is not.** A seeded simulation, not an observation: there are no
learners yet, so what is measured here is the *structure* — how many fragments a viewing produces
under behaviours chosen to bracket what a person can do to a video, and what a merge costs against
a set already at its cap. It is not evidence about people, and the cap should be revisited against
`progress.coverage.fragments` (published as percentiles from real viewings) rather than against
this, once there is enough of it.

**Run it:** `mvn -f services/pom.xml -pl streaming test -Dtest=FragmentDistributionTest`

### What was measured, 2026-09-05

1,000 simulated viewings of a 1,800-second video in ten-second heartbeats. 70% watch it through,
20% rewind three to eight times, 10% scrub deliberately (20–60 seeks).

| | |
|---|---|
| Fragments, median | **1** — watching a video is one run |
| Fragments, p95 | 13 |
| Fragments, p99 | 18 |
| Fragments, worst of 1,000 | 20 |
| **Cap chosen** | **64**, about three times the worst simulated scrubber |
| Merge cost at the cap | **13µs**, over 2,000 merges against a full set |

The merge figure is the one that matters for the write path: it is flat. A learner who has been
scrubbing a four-hour recording all afternoon costs the same per heartbeat as one who started a
minute ago, which is what "amortised" has to mean for a write that happens every ten seconds per
learner.

**What the cap costs when it does bite:** the two fragments separated by the smallest gap are
merged, the record is flagged `approximate`, and the coverage credited grows by that gap. It
rounds in the learner's favour and the row says it happened.

**Conditions.** In-process, no database, Java 25, one machine. The database side is not what this
measures — the merged set is written as one `int4multirange` literal against a row already locked
by the same statement.

## The learner home screen — what it costs to assemble

**Task:** T-5.8 · **The criterion:** one endpoint, and no N+1 across assignments, gates or progress.

**Why it is measured rather than argued.** The screen touches assignments, group reach, course
structure, gates, completions, progress and due dates at once. Every naive assembly of it is a
query per assignment — or per module, or per node — and every one of those is invisible on a demo
tenant with three courses. The number that matters is not how fast it is on this laptop; it is
whether it changes when a customer's content does.

**Run it:** `mvn -f services/pom.xml -pl catalog test -Dtest=HomeQueryBudgetTest`

### What was measured, 2026-09-05

| | |
|---|---|
| Shape | 20 courses × 5 modules × 5 nodes = **500 nodes**, 20 assignments, one learner |
| The learner | a third of the nodes complete, a third part-watched — so the completion and progress reads answer about a real set rather than an empty one |
| **Statements, 1 assignment** | **7** |
| **Statements, 20 assignments** | **7** — the same, which is the whole point |
| Assembly, median of 10 | **62ms** |
| Assembly, worst of 10 | 74ms |

A query per assignment would have been 27 statements at this shape; a query per module, 107. The
count is pinned by an assertion, because the property is one line away from being lost and no test
of a three-course fixture would notice.

**Conditions.** Testcontainers Postgres 17 on one laptop, JVM warmed with three assemblies first,
cache bypassed — a cached screen measures Valkey rather than this. Statements counted with
Hibernate's own JDBC counter, which sees the JPA reads; the four plain-JDBC reads (group reach,
profile, completions, progress) are one each per assembly by construction and are inside the
wall-clock figure.

**What this does not measure.** Concurrency: this is one learner at a time against an idle
database. The screen is also cached for a minute per learner (T-5.8), so the rate that reaches this
path in production is lower than the request rate by however often a person reloads.

## Backend down — does playback actually survive it (T-3.10)

**Why this one has to be measured against the real account.** `FakeMediaProvider` mints a manifest
on `fake-media.invalid`, which is unroutable by construction (T-3.1): a green run against it proves
every path up to the edge and nothing about the edge itself. `web/e2e/backend-down.mjs` is the
first thing in this repository that runs against the real Cloudflare Stream account (T-9.14) rather
than the fake one, for exactly that reason — it is real spend, and T-9.14 (#100) left "spend
visibility before any bulk upload" open, so it is a manual/`workflow_dispatch` check
(`.github/workflows/backend-down.yml`), never a job on every push.

**Run it:** `make up && mvn -f services/pom.xml -DskipTests install && node web/e2e/backend-down.mjs`
(needs `CF_STREAM_*` in the environment — `scripts/cloudflare-check.sh` proves they work).

### What was measured, 2026-09-05

| | |
|---|---|
| Asset | a 45-second synthetic clip (`web/e2e/fixtures/backend-down-clip.mp4`), uploaded and encoded by the real account for each run |
| Outage | identity, catalog, reporting and streaming all killed (`taskkill /T /F`), **30 seconds**, while a real Cloudflare Stream manifest played in a real Chromium |
| Playback during the outage | continuous — `currentTime` advanced at real-time rate for the whole window, no stall, no error state shown |
| Renewal attempts observed while down | 3, all answered `502` by vite's own dev proxy (nothing was listening on the target port) — the player retried quietly and the held token, still valid, kept the video playing |
| Heartbeats after recovery | 7-14 `playback_heartbeat` rows landed in reporting once it was reachable again — the client-side buffer was not lost |
| Coverage after recovery | streaming credited a merged interval spanning the outage (e.g. `[37,44)`), proving the buffered progress flushed and merged correctly, not just "arrived" |

**Why a shortened token lifetime.** `PLAYBACK_TOKEN_TTL=PT3M` / `PLAYBACK_RENEW_AFTER=PT20S` for
this run only (the production defaults are 5 minutes / 3 minutes): long enough that restarting
four JVMs (measured: 15-20s to pass health) and the recovery wait never race a real token expiry,
short enough that a 45-second clip crosses a renewal attempt without needing a multi-minute video
and the Stream minutes that would cost. The first version of this test set both too tight and the
held token expired mid-recovery — which the player correctly, and confusingly for a test not
expecting it, turned into a terminal refusal.

**What this does not prove.** Concurrency (one learner, one video); a real production deployment
rather than the local stack; recovery after an outage longer than the token's own TTL, which is a
different property (T-1.9 and T-3.4 already bound that one — a suspended account or revoked
assignment stops within one token lifetime, and this is the same lifetime working the other way).

## Dev cluster capacity — what a process costs, and what the cluster will book

**Task:** T-9.15 · **Replaces:** [ADR-0109](adr/0109-eight-modules-six-processes.md)'s figures of
2026-08-27, which were taken on the stemcell's cluster before ours existed and are superseded
rather than updated — see that ADR for why they could not be corrected one at a time.

**Run it:** `KUBECONFIG=<stemcell>/infra/terraform/cluster/kubeconfig python scripts/capacity_reading.py`

**Conditions.** The Hetzner dev cluster, two fixed cx33 workers plus one autoscaled cx33, carrying
the stemcell's platform and `apps` alongside our `learn` namespace. Read-only. The control plane is
tainted and excluded throughout.

### What was measured, 2026-09-08

**Three readings, and the worst of the three is what is written down.** The previous measurement
was taken once and quoted as a constant; it moved 810Mi within the hour. Anything here that came
from a single sample says so.

| | 18:24Z | 19:09Z | 19:54Z |
|---|---|---|---|
| fixed pair, committed | 8149Mi | 8225Mi | 8302Mi |
| **actually free** | 7357Mi | 7281Mi | **7204Mi** |
| **free to schedule into** | 1944Mi | 1944Mi | 1944Mi |
| *the same nodes by `kubectl top`* | *89% and 93%* | *92% and 97%* | *91% and 97%* |

**7204Mi is what gets recorded**, being the worst of the three.

The last row is the instrument ADR-0109's earlier drafts ran on, kept as a control. It reads 89–97%
on nodes that are half full. The booked figure does not move between readings because requests are
declared; committed drifts up 153Mi across ninety minutes — three points, deliberately not called a
trend, because the previous measurement called a three-point climb monotonic and a fourth sample a
minute later disproved it.

**Capacity per fixed worker:** 7753Mi physical, **5903Mi allocatable**. The 1850Mi gap is the
kubelet's reservations. ADR-0109 recorded 7153Mi allocatable; the stemcell raised the reservations
in its T-2.28 (#367) and nothing here noticed, which is what
`scripts/verify_capacity.py --cluster` now exists to catch.

### One of our JVMs, measured rather than borrowed

| | `reporting` |
|---|---|
| request / limit, as measured on | 640Mi / 896Mi — since right-sized to 512Mi on these figures |
| cold, seconds after start | **278Mi** |
| warm, idle | **339Mi** |
| under load | **367Mi** — but see below, this is not a load figure |
| worst sample ever taken | **406Mi** |

`identity` and `streaming` idle at **370Mi** and **342Mi**. ADR-0109 previously used **605Mi**,
measured on the stemcell's `core` — a different service with a 1Gi limit. A JVM sizes its heap from
the container limit, so "what a Spring Boot process costs" is a property of the limit somebody
chose and does not transfer between services.

### Under load is not measured, because the service cannot currently serve load

Three runs against the deployed `reporting`, posting real heartbeat batches with a real token:
16 and 64 concurrent through a `kubectl port-forward`, then **12 concurrent from a pod inside the
cluster**, which takes the port-forward out of the argument entirely. All three behaved alike.

| | 12 concurrent, in-cluster, 2026-09-08 |
|---|---|
| Offered | 436 batches over 420s |
| Accepted | **436 of 436, `202`, none refused** |
| Throughput | **~1 request/second** — about 12 seconds per request |
| Container CPU | **20–60m** against a 100m request |
| Resident set | 345Mi, climbing steadily to **367Mi** |

**Twenty millicores is not a busy process, it is a blocked one** — and every batch was still
accepted, which is why nothing downstream noticed either. Every request logs `Could not read
the status entry for tenant acme; this service is permissive until Valkey returns`, and from a pod
in the `learn` namespace a TCP connection to `valkey-cache.cache.svc.cluster.local:6379` is
**refused in 12ms** — while Postgres, NATS and this service's own port all connect from that same
pod. Valkey has been `Running` for five hours, has never restarted, and its log holds nothing but
its startup banner.

The failures serialise, so latency grows with concurrency: at 64 concurrent even the liveness probe
missed its deadline and the kubelet restarted the container. The "permissive until Valkey returns"
degradation fired and logged on every request; what it did not do was keep the process alive.

**Nothing reported any of it.** `/management/health` answers **503 DOWN** and has done throughout,
while the `liveness` and `readiness` groups — which exclude the cache — answer 200 in 7ms. So the
pod is `Ready`, Argo is green, and the permission cache T-2.5 exists for has never been read on this
cluster once.

Raised as a defect. Until it is fixed there is no honest under-load figure for one of our processes,
which is why the request was right-sized against **406Mi**, the worst sample ever taken, rather than
against the 367Mi this run produced.

**A JVM does not give heap back**, so the warm figures are the floor for a process that has served
traffic, and the 278Mi cold figure is not.

### The two headrooms, at floor

| | fixed pair |
|---|---|
| allocatable | 11806Mi |
| booked (sum of requests) | 9862Mi |
| **free to schedule into** | **1944Mi** |
| physical | 15506Mi |
| committed, worst of three | 8302Mi |
| **actually free, worst of three** | **7204Mi** |

The scheduler adds up requests; the machine spends committed memory. Four times more memory is free
than the scheduler will let anything book, and it is the booking that binds. At the same instant
`kubectl top` reported these two workers at 90% and 92%, against 53% and 49% of physical memory
actually committed — the disagreement the stemcell's `check-node-memory.sh` was written for.

### What the platform books before either product is scheduled

7942Mi of the fixed pair's 11806Mi, of which `observability` alone is **3572Mi** — more than every
application on the cluster put together. This is the term that decides how many processes fit, and
neither earlier draft of ADR-0109 contained it.

## Not yet measured

- **Ingest under sustained load rather than a ten-second window** — what happens after an hour,
  and what the table's growth does to insert latency, needs the day partitions (T-7.2) to be a
  fair test.
