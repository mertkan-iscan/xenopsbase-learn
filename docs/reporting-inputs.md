# What reporting consumes

**Task:** T-9.7

`reporting` owns its data outright. It does not read `catalog_db`, `assessment_db`,
`identity_db` or `streaming_db` to build a report, however convenient that would be — it
receives what it needs and keeps its own copy.

The rule exists because the failure is invisible while it is happening. The first time a report
joins across another service's tables it will work, it will be faster than the alternative, and
nobody will notice that the decomposition is over. By the time it matters — a schema change in
`catalog` breaking a report, or `reporting` being unable to start without `assessment` — the
join will be six months old and load-bearing.

## The list

Every input, and how it arrives. **A report may use nothing that is not on this list.** Adding a
row is a deliberate act with a review attached; that is the whole point of writing it down.

| Input | Arrives by | Owner | Status |
|---|---|---|---|
| Playback heartbeats (position samples) | Direct ingest from the player | `streaming` domain, posted by the client | T-7.1 |
| Watched intervals, derived completion | Event | `streaming` | T-3.7 publishes `streaming.node.completed`; a consumer here is T-7.3's |
| Video asset facts (id, duration, title) | Event | `streaming` | T-3.3 publishes the READY transition |
| Course, module and node structure | Event | `catalog` | T-5.2 |
| Assignment made, revoked, due date changed | Event | `catalog` | T-5.5 |
| Attempt submitted, scored, graded | Event | `assessment` | T-6.6 |
| Person's display name and email | Event | `identity` | T-1.9's lifecycle changes |
| Group membership and the group tree | Event | `identity` | T-1.3 |
| Company name and status | Event | `identity` | T-1.5, T-1.4 |

Nothing on that list arrives by a query into another service's database, and nothing on it is
fetched synchronously while a learner waits.

**The first two rows are one measurement arriving twice, and that is deliberate.** The player posts
the same batch of intervals here (raw, append-only, droppable at ninety days) and to `streaming`,
which merges them into the coverage completion is derived from (T-3.7, ADR-0107). The rule below —
progress recording must complete with `reporting` stopped — is what forbids the tidier-looking
alternative of deriving completion from the rows in this store. It would work, it would be one post
instead of two, and the first outage would produce reports that render perfectly with fewer
completions in them.

## Copies go stale, and that is the trade

A person renames themselves and a report built ten minutes later still says the old name. That is
accepted: the alternative is a join that makes `reporting` unable to answer when `identity` is
down, which is the failure this separation exists to prevent. Reports state the time they
describe; a name in a report is the name as at that time.

## What "no synchronous call" means concretely

- No service calls `reporting` on a request path a learner is waiting on. Playback, progress
  recording and attempt submission must all complete with `reporting` stopped.
- `reporting` calls no service on the path of ingesting a sample. What it needs to interpret a
  sample later arrives as an event before or after; a sample it cannot yet interpret is kept and
  interpreted when the event lands.
- The enforcement is credentials (`reporting_db` has one role, and it is not any other service's)
  and an ArchUnit rule keeping the module free of the others' packages. Both are tested.

## Backups

Rollups are expensive to recompute; raw events are droppable. The backup schedule should follow
the rollups rather than the raw table, and it belongs to the infrastructure this platform does
not yet run on — the stemcell's ADR-0007 and its CloudNativePG backup configuration. Recorded
here so that whoever forks into it does not have to rediscover the distinction; there is nothing
to implement in this repository today.
