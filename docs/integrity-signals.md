# Integrity signals — and why this is not proctoring

**Task:** T-6.8 · **Service:** `assessment` (`integrity/`)

> **If you are writing sales copy, read this page first.** This feature must not be described as
> proctoring, monitoring, or cheating detection. It is none of those, and the sections below are the
> reasons rather than lawyerly hedging.

## What it is

While a learner is sitting a test, their own browser may report six things:

| signal | innocent explanation, which is usually the true one |
|---|---|
| `FOCUS_LOST` / `FOCUS_REGAINED` | a screen reader moves focus; a notification steals it |
| `TAB_HIDDEN` / `TAB_VISIBLE` | a second monitor, a phone call, a laptop lid |
| `PASTE` | drafting a long answer in a text editor because the browser lost their work last time |
| `ADDRESS_CHANGED` | a phone dropping from wi-fi to cellular mid-sentence; a VPN rotating an egress node |

Each is timestamped, attached to the attempt, and shown to whoever reviews the result.

## Three reasons it is not proctoring

**It is self-reported.** These come from a page the learner controls. A learner who wants to hide a
focus loss does not report one. So the signals can only ever *corroborate* a human's existing
suspicion — and an **absence of signals means nothing at all**. Anybody reading a clean record as
evidence of honesty has misunderstood the feature.

**Nothing acts on them.** No signal can fail a learner, reduce a mark, or end an attempt. That is
not a policy somebody could quietly change: `TechnicalStructureTest` fails the build if the
`grading` or `scoring` packages so much as *depend on* the `integrity` package. A build that wanted
to auto-fail on a focus loss would have to delete that rule first, which is a thing a reviewer can
see in a diff.

**Auto-failing would be discriminatory, and invisibly so.** Every signal fires more often for people
using assistive technology, people on unstable connections, and people working from a phone.
Enforcing on any of them punishes those groups at a much higher rate than it catches anybody
cheating — and it does it silently, in a number nobody audits.

## What is never collected

- **The pasted text.** The length travels; the content does not. Collecting it would be collecting
  the learner's answer twice: once as an answer and once as surveillance.
- **Anything after the attempt ends.** A submitted attempt records nothing further. Everything after
  it is a person using the product, not sitting an exam.
- **Anything not on the list above.** The kinds are a closed enum, and each carries the sentence the
  learner is shown. Adding one without a sentence does not compile.

## What the learner is told, and when

`GET /api/v1/me/monitoring` returns the disclosure, and the same content rides on the response that
**starts** an attempt — so a player has been handed it before it can render a question.

The list is generated from the same enum the recorder accepts, so **a signal cannot be collected
without appearing in the disclosure**. Prose in a template stops matching the code the first time
somebody adds a kind.

A server cannot make a client display anything. What it can do is make the disclosure impossible to
miss. A client that throws it away has lied to its user, and no API shape prevents that.

## Retention: their own clock

**Ninety days**, and deleted **while the attempt survives**. That separation is the point: an
attempt is a record of somebody's training and is kept as long as the training matters; a log of
when their attention wandered is not, and merging the two clocks would quietly make the shorter one
the longer one.

Ninety days is long enough that a dispute about a result can be raised and reviewed, short enough
that this is not a permanent behavioural record of a person.

## Two implementation notes worth keeping

**One clock, not two.** `recorded_at` is written explicitly from the application's clock rather than
left to the column's `now()` default. Retention is measured against the application's clock, and
leaving the default would put two clocks either side of one rule — found by a test that advanced
ninety-one days and deleted nothing.

**Ordered by a sequence, not a timestamp.** A burst arrives inside one millisecond by design: focus
lost, tab hidden, tab visible, focus regained is four rows and one gesture. Ordering by a timestamp
resolves the tie to whichever uuid sorted higher, and a reviewer then reads somebody's attention
wandering in an order that never happened. `grade_event` learned the same lesson one migration
earlier (T-6.7).

## The cap

500 signals per attempt. This is a write endpoint the learner's own browser calls, so it is a place
somebody can push on. Past the cap, signals are dropped and the request still answers `202` —
refusing would make the player retry, which is the opposite of what a flood needs.

## What is not here

- **A suspicion score.** Deliberately never. A number would be acted on, and it would be acted on
  hardest against the learners whose innocent explanations are the most common.
- **A permission on the reviewer's read.** The same gap every endpoint in this module carries: the
  evaluator and its grants live in `identity` and a separate process cannot ask it anything (T-9.11,
  ADR-0109).
