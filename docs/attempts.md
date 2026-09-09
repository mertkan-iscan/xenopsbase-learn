# Sitting a test: the clock is the server's

**Task:** T-6.6 · **Service:** `assessment` · **Code:** `attempt/`

From the issue: *a client-side countdown is a courtesy. If the server does not compute
`expires_at` at start and enforce it at submit, the time limit is advisory and everyone will
eventually find out.*

So `expires_at` is a stored column, written once, and **there is no request shape anywhere that
could move it**. The countdown a learner sees is drawn from `secondsRemaining`, which goes out and
never comes back in.

## The one rule about resuming

**Resume is allowed and the clock keeps running.** One rule, chosen rather than configured.

A pausing clock has to move `expires_at`, and the only thing that could tell us when to pause is
the browser saying it went away — which is the client honesty this whole design refuses to depend
on. "Sixty minutes from when you start" is also a rule a person can hold in their head.

The cost, stated: a learner whose laptop dies loses that time. What they do not lose is their work,
because answers are saved as they are given.

## Three races, none settled by looking first

Every one of these has a window between "check" and "act", and the window is exactly where two tabs
live. So none of them is a read followed by a write.

| race | what settles it |
|---|---|
| two tabs starting the last attempt | a **partial unique index** — one `IN_PROGRESS` attempt per learner per test. The loser reads the winner's row and resumes it, which is what the person in front of both tabs wanted |
| two saves of one answer | an **upsert** on `(attempt, form item)` |
| a double-clicked submit | one **conditional UPDATE** off `IN_PROGRESS`. The row count says who won |

## Answers are idempotent by shape, not by key

A deliberate departure from the criterion's wording, worth stating rather than leaving to be
noticed.

An `Idempotency-Key` makes a retry **replay a stored response**. That is right for "create a
company", where a second execution creates a second company. It is wrong here, in the case that
actually happens: a learner changes their answer, the request is retried, and a replayed response
hands back the old one and hides the change. `PUT /me/attempts/{id}/answers/{itemId}` has no such
case — saving the same thing twice writes the same row, and saving something different writes the
new one, which is what the learner meant both times.

(The platform's `IdempotencyFilter` also lives in `identity` rather than in the shared web module,
so it is not available here at all. That is a real gap with its own fix; it is not the reason for
this decision, which would stand either way.)

## The deadline is enforced in two different ways

**Saving is refused after it. Submitting is not.**

Nothing may be written after the clock runs out — that is the rule the task exists for. But a
submit that arrives a minute late is accepted, and the attempt is marked `EXPIRED` rather than
discarded: by construction nothing in it was written late, so refusing would throw away work done
in time to punish a slow network for a rule about time to think.

**`EXPIRED` does not mean ungraded.** It means the clock ran out.

## The four states

| state | means | reached by |
|---|---|---|
| `IN_PROGRESS` | live | starting |
| `SUBMITTED` | the learner finished in time | submitting |
| `EXPIRED` | the time given ran out | submitting late, opening a lapsed attempt, or the sweep |
| `ABANDONED` | walked away from something with no deadline | the sweep only |

`EXPIRED` is a fact about the **test**; `ABANDONED` is a fact about the **learner**. A report that
merged them could not tell "everybody runs out of time on question forty" from "half of them never
finish", and those have opposite fixes.

## Nothing stays open

`AttemptReaper` runs every five minutes. A timed attempt past its deadline becomes `EXPIRED`; an
untimed one untouched for **seven days** becomes `ABANDONED`.

Seven days is a compromise worth stating: short enough that somebody who walked away in March is not
still blocking their own next attempt in June, long enough that somebody working through an untimed
course over a fortnight of evenings is not cut off mid-way.

### The bug this class already had

The first version used the JPA repository, and **every scheduled run threw**:

```
SessionFactory configured for multi-tenancy, but no tenant identifier specified
```

Every test passed, because a test calls the sweep inside a tenant the way a request would. The
production path — the only path the criterion is about — was broken from the first commit and said
so once every five minutes into a log nobody was reading.

A reaper genuinely belongs to no tenant: it is one sweep across every company, and which company
owns a lapsed attempt is something only the row knows. It now uses streaming's
`TenantlessTransactionConfiguration` pattern — plain JDBC, two statements, explicit and named so
that stepping outside the discriminator is a decision somebody made. `worksFromTheSchedulerWithNoTenantBound`
is the test that would have caught it.

## Everything is under `/me/` and nothing takes a learner id

The reason the playback token and the home screen give: the answer is only ever about the caller,
and an endpoint that took an id would be one refactor away from being one that answers about
somebody else. Another learner's attempt gets the **same 404** as a missing one — "it exists but is
not yours" is a fact about somebody else's exam (T-2.4's disclosure rule).

One verb opens a test: `POST /me/tests/{id}/attempts` starts a new attempt or resumes the one in
progress. A client that had to ask which first would have a race of its own, and the answer could be
stale by the time it acted.

## What is not here yet

- **Grading** — done, in T-6.7 (#66) and [`grading.md`](grading.md). It hangs off the terminal
  transition, whether the learner made it or the reaper did, and it runs inside the submitting
  transaction. `AWAITING_GRADING` turned out to be a second column rather than a fifth state, for
  the same reason `EXPIRED` and `ABANDONED` are two: an attempt can be both.
- **`attempt_event`** — done, in T-6.8 (#67) and [`integrity-signals.md`](integrity-signals.md).
  Behavioural data about a person, on its own retention clock (ninety days, deleted while the
  attempt survives), and structurally unreachable from the grading path.
- **Review.** T-6.9 (#68) renders from the form (T-6.5) under a policy.
