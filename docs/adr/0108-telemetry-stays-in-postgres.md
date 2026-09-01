# ADR-0108: Telemetry stays in Postgres until a measurement says otherwise

- **Status:** Accepted
- **Date:** 2026-08-31
- **Task:** T-0.8 (#8)

## Context

Heartbeat ingest is the first workload in this project with a genuinely different shape from
anything the stemcell has run: it scales with **concurrent learners** rather than with users, it
is append-only, and its queries aggregate rather than look up. The reflex for that shape is a
column store.

The stemcell's ADR-0012 already set the standard for this class of decision — scaling work not
justified by a measurement adds permanent components and permanent failure modes in exchange for
capacity nobody needed. The same standard applies here, with one addition that makes it sharper:
the numbers that would justify the move do not exist yet, because nothing has run. A decision made
now against projected volume is a decision made against a number somebody invented.

## Decision criteria

- **Measured ingest rate, not projected** — which means option 1 has to run first, and the
  decision has to be falsifiable rather than defended
- **Whether the analytical store must survive a full environment rebuild**, and what it costs if
  it must (the stemcell's ADR-0002 cluster is disposable by design)
- **Rollup job duration against its window**

## Decision

**Raw telemetry lives in Postgres: daily partitions, retention by dropping partitions, and
pre-built rollups that make every product query a lookup. No column store, no second query engine,
until one of the measurements named below says so.**

### Partitioning and retention, with numbers

| Table | Partition | Retention | Why that number |
|---|---|---|---|
| `playback_heartbeat` | daily | **90 days** | Its product value is consumed within seconds of arrival — coverage is merged into state on ingest (ADR-0107). After that it is forensic, and 90 days covers the window in which anybody asks "why does this say 84%" |
| `page_view` | daily | **90 days** | Same shape, same argument |
| `xapi_statement` | daily | **400 days** | Not telemetry in the droppable sense: these are the evidence behind a `SELF_REPORTED` completion (ADR-0107, T-4.6). 400 days covers an annual compliance cycle plus the audit that follows it |

Daily rather than hourly: retention is stated in days, drops are instant at either granularity, and
hourly would multiply the partition count by 24 for no gain. Daily rather than weekly: a week of
heartbeats is the largest thing we would ever have to drop or rebuild in one step, and 90 daily
partitions per table is a number Postgres plans across without complaint.

Partitions are created **14 days ahead** by a scheduled job, and fewer than **7 future partitions**
raises an alert — before a missing partition becomes a failed insert rather than after (T-7.2).

**Storage, estimated and marked as such:** a heartbeat every 10 seconds is 360 rows per
learner-hour; at roughly 100 bytes a row with indexes that is **~36 MB per thousand learner-hours**.
T-7.2 owes the measured figure; this estimate exists so the order of magnitude is on record and
can be shown wrong.

### The retention rule that keeps ADR-0107 honest

ADR-0107 commits to keeping watched intervals for as long as a completion can be disputed, and
this ADR drops raw heartbeats at 90 days. Those are not in conflict, and the reason is worth
stating because it is the crux of the whole design:

**A heartbeat is a sample; the merged interval set is state.** Coverage is merged into the
learner's progress record on ingest and stays there for as long as the progress record does. What
is dropped at 90 days is the individual samples that produced it, not the measurement. "Prove this
person completed it" is answered from state, permanently. "Show me the exact heartbeats from
fourteen months ago" is not answerable, deliberately, and nothing in the product asks it.

### Rollups, because they are what makes option 1 last

| Rollup | Grain | The query it exists to serve | Maintained |
|---|---|---|---|
| `learner_item_progress` | learner × content item | "Is this person done with this item?" — and the gate's input (T-5.3) | incrementally, on ingest |
| `learner_assignment_state` | learner × assignment | The learner home screen (T-5.8); overdue evaluation (T-5.6) | incrementally |
| `assignment_compliance_daily` | tenant × assignment × day | "Show me compliance for 5,000 people" as a row read (T-7.6) | scheduled |
| `attempt_outcome` | attempt | Transcripts (T-7.5), and the input to item analysis | on submit / on grade |
| `question_version_stats` | question **version** | Difficulty and discrimination (T-7.7), per version by ADR-0106 | scheduled |
| `item_position_buckets` | item × position bucket | The retention curve — where people stop watching (T-7.4) | scheduled |
| `daily_activity` | tenant × day | Dashboards: active learners, minutes, attempts | scheduled |

The split between incremental and scheduled is T-7.3's to justify per table; the rule this ADR
fixes is that **no product query reads a raw event table**. A compliance view that aggregates
heartbeats is the query that takes the database down, and it is also the query that makes a column
store look necessary when what was actually missing was a rollup.

### Raw telemetry is droppable; rollups are not

Stated explicitly because the backup policy, the restore drill and the partition-drop ordering all
follow from it:

- **Raw events may be lost.** A restore that loses yesterday's heartbeats loses no product state:
  progress, completion, scores and their provenance were derived and stored before the partition
  was ever eligible to be dropped. Backups of raw partitions are a convenience, not a requirement.
- **Rollups may not be lost.** Recomputing them needs raw data that retention has already removed,
  so beyond the retention window they are irreplaceable. They are backed up on the schedule used
  for state, not for events (T-9.7).
- **A partition is not dropped until every rollup that reads it has advanced past it**, and that
  ordering is enforced rather than assumed (T-7.2). Dropping in the wrong order is the one way this
  design loses something it cannot rebuild.

### Durability across a rebuild — the second criterion, answered

The stemcell's cluster is disposable (ADR-0002). Postgres is already managed and already outside
that lifecycle, so telemetry inherits durability for nothing. A column store would need the same
treatment — either a stateful component in a cluster built to be thrown away, or a second managed
service with its own backups, credentials, upgrades and on-call surface. That is the cost option 2
is really asking for, and it is a permanent cost paid against a projected rate.

### One boundary this ADR does not decide, and one constraint it places on it

Where the authoritative progress row lives — the one a gate reads (T-5.3) — is T-9.7's and T-7.3's
decision, not this one. The constraint from here: **the learner's request path must never
synchronously read the analytics store.** Whatever the split, reporting being slow or absent may
not stop a gate evaluating or a video playing.

## The measurements that would falsify this

Named up front, so moving is a threshold being crossed rather than an argument being won. Each is
a metric this platform already plans to export; when `docs/slos.md` exists (T-3.6, T-7.1) they
belong there with their measured values beside them.

| Signal | Trigger | Where measured |
|---|---|---|
| Sustained ingest | p95 insert latency > 50 ms at the observed rate, for 7 consecutive days | analytics ingest metrics (T-7.1) |
| Rollup window | p95 rollup job duration > 50% of its schedule interval, for 7 consecutive days | job duration metric (T-7.3) |
| Report latency | p95 report query > 2 s over rollups, at 5,000 learners and 12 months of history | reporting query metrics |
| Table maintenance | autovacuum falling behind on an event table, or partition count becoming an operational burden | database metrics |
| Retained size | raw event storage after retention exceeding the instance's comfortable working set | storage metrics (T-7.2) |

**None of these is a projection, and that is the point:** option 1 has to run first. If the
triggers fire, the move is option 2 for events only — Postgres keeps state and rollups, because
the argument above about durability and blast radius does not change.

## Consequences

### What this makes easy

- One database technology, one backup story, one set of credentials, one thing to operate.
- Rollups are ordinary SQL against ordinary tables, so a reconciliation job can recompute and
  report drift (T-7.3) instead of comparing across two engines.
- Retention is a `DROP`, which is instant, rather than a `DELETE` at volume — a long transaction, a
  bloated table and an autovacuum problem, run at the worst possible time.

### What this makes hard

- Ad-hoc analytical queries over raw events stay slow, on purpose. The answer is a rollup, and if
  a question is asked often enough to matter it earns one.
- The partition machinery is real work that has to keep working: a job that silently falls behind
  produces a failed insert days later, which is why it alerts on the gap rather than on the failure.

### What it commits us to

Discipline rather than infrastructure: every new report question is a rollup design decision.
Reversal is cheap in the direction that matters — events are append-only and can be replayed or
back-filled into a second store — which is exactly why deferring the decision costs so little.

## Alternatives considered

### Option 2: Postgres for state plus ClickHouse or Timescale for events — rejected, for now

The right answer at some volume, and the wrong answer at zero. It adds a stateful component to a
disposable cluster or a second managed service to the bill, a second query language to reports, a
second backup and restore drill, and a consistency question between two stores that a single
database does not have. The triggers above are what would justify it; none of them can be
evaluated today, which is precisely the argument ADR-0012 makes.

### Option 3: object storage plus a query engine — rejected as the primary store, likely later for cold data

No always-on component is genuinely attractive, and ADR-0101 already puts large objects in R2, so
the storage is there. It loses on query latency for the interactive reports the product actually
serves, and on being a second data model for the same facts. It is, however, the natural home for
raw events **past** their retention window if anybody ever wants them — archiving a dropped
partition as Parquet is a cheap addition to this decision rather than a replacement for it.

## Revisit if

- Any trigger in the table above fires. That is the whole intent: this decision is designed to be
  falsifiable by a metric rather than defended by an opinion.
- A customer contract requires raw event retention beyond 400 days, which changes the storage
  arithmetic rather than the engine — and probably makes option 3's archive path the answer.
- The platform gains a workload that queries raw events interactively, which would mean the rollup
  discipline had failed somewhere upstream and is worth fixing there first.
