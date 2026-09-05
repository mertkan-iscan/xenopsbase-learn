# Measured figures

Numbers taken from runs, with the conditions that produced them. **A figure without its
conditions is not a measurement**, so every entry here says what was running, on what, and what
the number does not mean.

The distinction this file exists to keep is the one [ADR-0109](adr/0109-eight-modules-and-how-many-processes.md)
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

## Not yet measured

- **Per-service memory under real load** — ADR-0109's process-count arithmetic is derived from
  declared configuration; T-9.15 (#101) replaces it with measurements.
- **Ingest under sustained load rather than a ten-second window** — what happens after an hour,
  and what the table's growth does to insert latency, needs the day partitions (T-7.2) to be a
  fair test.
