# ADR-0107: Completion is derived by the server, never reported by the client

- **Status:** Accepted
- **Date:** 2026-08-31
- **Task:** T-0.7 (#7)

## Context

A compliance report is worth exactly what its weakest completion signal is worth. If a browser can
`POST` "finished", the report says nothing — and a report that says nothing is worse than no
report, because somebody will rely on it. That is the whole of the problem, and it is a design
decision rather than a feature: by the time a regulator, an auditor or an employment tribunal asks
how completion was established, the mechanism is whatever it has been since the first release.

Two things make it a genuine decision rather than an obvious one. The signal has to come from a
browser — there is no server-side observation of a person watching a video — so the question is
not *whether* to trust the client but *what* to trust it for. And the standard formats invert the
rule: a SCORM package reports its own completion status, and that is the contract, not a
loophole.

## Decision criteria

- **What a determined learner with developer tools can achieve**, stated as what it costs them
- **Write volume per concurrent learner**, and which database pays it
- **Whether the same data is useful for something else**, or is pure overhead bought for one
  boolean
- **Whether a report can tell the difference** between a completion this platform measured and one
  it was told about

## Decision

**The client reports position; the server derives completion.** The player sends batched
positional heartbeats (T-3.6), the server accumulates the set of distinct seconds actually
presented, and completion is that set's coverage crossing a threshold. No endpoint in this
platform accepts "this item is complete" from a learner's browser.

### The threat model, stated plainly

**This defends against a bored learner with developer tools. It does not defend against a paid
attacker, and it is not trying to.**

What it makes impossible: dragging the scrubber to the end, `POST`ing a completion flag, calling
an API with `completed: true`, or finishing a sixty-minute video in thirty seconds. Each of those
is one action, needs no skill beyond opening a browser console, and each is defeated by measuring
coverage server-side and rejecting rates wall-clock cannot support.

What it does not stop, and nobody should claim otherwise: a script that posts honest-looking
heartbeats at real-time pace, or the oldest attack of all — leaving the video playing in a
background tab. **The cheapest remaining attack costs the learner as much wall-clock time as
watching would.** That is the bar this decision sets, deliberately: raising it further means
webcam attention checks and interaction prompts, which cost every honest learner something real
to inconvenience a dishonest one who has already decided to spend the hour.

Attention is not measurable from a browser. Presentation is. This platform reports what it can
measure and does not imply the other.

### The interval representation, and its bound

Coverage is a set of whole seconds per `(app_user_id, content_item_id)`, stored as a Postgres
`int4multirange`. The type is chosen for one property: **union is native and the result is always
normalised**, so merging a heartbeat is one SQL operation that cannot leave overlapping or
adjacent fragments behind.

```
covered = covered + int4multirange(int4range(from_second, to_second))
```

A learner who watches straight through has exactly one fragment. Fragments accumulate only from
seeking, so the bound is stated in terms of that:

- Heartbeats carry the interval covered since the last one, so a contiguous run of playback is one
  fragment however long it is.
- Gaps of **two seconds or less are coalesced**, because that is inside the sampling error of a
  ten-second heartbeat and refusing to coalesce would grow the structure with noise. Two seconds
  is also the largest inflation this can add per gap, which is the honest way to state it: the
  coalescing rounds *in the learner's favour*, by an amount smaller than one heartbeat.
- Fragments are **capped per record**. At the cap, the two fragments separated by the smallest gap
  are merged and the record is flagged as approximate, so a pathological scrubber degrades a
  bounded amount rather than growing an unbounded value that is rewritten on every heartbeat.

The cap's value and the fragment distribution real learners produce are **not measured** — there
are no learners yet. T-3.7 owns both, and owes a number.

### Rate sanity, using the credential that already exists

A heartbeat batch claiming more content than wall clock allows for its reported playback rate is
rejected and counted (T-3.7). Option 3 proposed a signed per-session nonce for this; the platform
already mints a short-lived signed playback token per session (T-3.4, ADR-0101), so heartbeats
carry **that**, and there is no second signed credential to design, rotate or leak. A rejected
batch is a metric, not an error to the learner: the honest cause is a clock skew or a resumed
laptop far more often than an attack.

### SCORM and cmi5 are the exception, and reports say so

A SCORM package reports `cmi.completion_status` (or `lesson_status` in 1.2) and cmi5 sends
statements with the defined verbs. That is the standard's contract, and the package's interior is
an opaque iframe on another origin (ADR-0105) — there is nothing to measure coverage of. So those
items are **self-reported**, and the platform records that fact rather than hiding it:

```
progress.completion_source ∈ { DERIVED, SELF_REPORTED, MANUAL }
```

- `DERIVED` — coverage measured by this platform.
- `SELF_REPORTED` — a package or a cmi5 statement said so (T-4.4, T-4.6).
- `MANUAL` — a person with the permission marked it complete, recorded with the actor and a
  reason, audited like every other administrative act.

**Every export and every compliance view carries the source.** A report that mixes measured and
self-reported completions without distinguishing them is exactly the report this ADR exists to
prevent — it would be a claim about a package's honesty presented as a claim about a person's
attendance. Time spent in a package is still recorded and still reported, as corroboration; it
never overrides what the package said, because overriding the standard's contract would break
conformant content.

### The threshold

Completion is coverage ≥ a threshold of the item's measurable extent, **per item, defaulting to
90%**. The extent comes from the provider (the encoded duration, T-3.1), never from the client.

90% rather than 100%, because the last few seconds of a video are credits and a dropped final
heartbeat is ordinary; 90% rather than 75%, because a learner who skipped the last quarter did not
do the training. An item that genuinely needs all of it sets its own threshold, which is why the
number is per item and not a constant.

For item types whose extent is not time — slides and documents (T-4.7) — the same rule applies to
that type's own units, declared by the type. The principle is coverage of what was presented, not
"seconds" specifically.

### Derived once, stored, reproducible

The derivation runs server-side on ingest and is **persisted** as a progress row with the inputs
that produced it (covered seconds, extent, threshold, source). A compliance query is then a row
read (T-7.3), and the row can be recomputed from the raw intervals and compared — which is what
makes the reconciliation job in T-7.3 possible rather than aspirational.

## Consequences

### What this makes easy

- The intervals are not overhead bought for one boolean: they are the resume position, the
  drop-off curve and the retention analysis (T-7.4), from the same rows.
- "Prove this person completed it" is answerable with the measurement, the threshold that applied,
  and the source — three columns, not a narrative.
- A gate (T-5.3) reads a server-derived state, so the client cannot unlock its own content.

### What this makes hard

- The player must send honest positional heartbeats and handle their loss (T-3.6), which is more
  work than firing one completion call.
- Write volume is real: one batched post per learner per ten seconds. At 5,000 concurrent learners
  that is ~500 posts/second, on **analytics**, never on the core database — the split T-3.6 exists
  to hold. **Estimated, not measured**; T-3.6 owes the load test and the figure in `docs/slos.md`.
- Completion becomes eventually consistent by a heartbeat interval. A learner who finishes and
  immediately looks for their certificate may wait ten seconds, and the UI has to say so rather
  than appearing broken.

### What it commits us to

Keeping raw intervals for as long as a completion may be disputed, which is a retention decision
E7 inherits (ADR-0108). And to never adding the convenient endpoint — the one somebody will ask
for during an integration, where a customer's own system reports completions inward. That request
is legitimate and its answer is a **separate, explicitly attested source** (`MANUAL`, or a fourth
value with the integration named), never `DERIVED`.

## Alternatives considered

### Option 1: client reports completion, server records it — rejected

One line of JavaScript in a console produces a completed compliance record for training nobody
took. It is not a weak signal; it is no signal, dressed as data, and the product's central promise
is a report somebody can rely on. The only argument for it is implementation cost, and that cost
reappears immediately as the first customer question this platform cannot answer.

### Option 3: as chosen, plus a separate signed per-session nonce — adopted in substance, not in mechanism

The rate check is kept. The separate nonce is not, because the playback token (T-3.4) is already a
short-lived signed per-session credential and a second one would be a second thing to rotate,
expire and leak, buying no property the first does not already have.

## Revisit if

- A customer's regulator requires attention verification rather than presentation — that is a
  different product decision with a real cost to honest learners, and it should be made
  deliberately rather than by extending this.
- Heartbeat write volume forces intervals out of Postgres, which is ADR-0108's trigger, not this
  one's — the derivation would not change, only where its inputs live.
- A content type arrives whose extent cannot be measured at all, in which case it joins the
  self-reported category with the same visible provenance rather than quietly counting as derived.
