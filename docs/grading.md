# Grading: the state between passed and failed

**Task:** T-6.7 · **Service:** `assessment` (`grading/`), with catalog's `AttemptGradedHandler`

From the issue: *an attempt containing one essay is neither passed nor failed until a human looks at
it. That third state has to be modelled explicitly — `AWAITING_GRADING` — or a gate reads a null
score, treats it as a fail, and locks a learner out of a course they may well have passed.*

## Two axes, not one enum

`AWAITING_GRADING` is a **second column**, not a fifth `state`.

| column | says | values |
|---|---|---|
| `state` | how the attempt **ended** | `IN_PROGRESS`, `SUBMITTED`, `EXPIRED`, `ABANDONED` |
| `grading` | where its **marking** is | `NOT_GRADED`, `AWAITING_GRADING`, `GRADED` |

An attempt that expired *and* is waiting on an essay is both of those things, and one enum would
have to lose one. T-6.6 kept `EXPIRED` and `ABANDONED` apart for the same reason.

The database holds the invariant: `ck_attempt_graded_has_a_verdict` makes "graded but nobody wrote a
score" — the state a gate would read as a fail — unreachable at all.

## Which questions a machine can mark is the type's answer

Not a list in `GradingService`. `grade` returns empty for an essay and a file upload (T-6.3), and
empty means *a human has to look*. T-6.3 built that seam saying so in as many words; this is the
caller it was built for, so an eleventh human-marked type needs no change here.

Everything arithmetic was already decided in T-6.4 and is not repeated: an unmarked question is in
neither total, so a ten-question attempt with one essay is scored **out of nine** and comes back
`provisional`. This task turns that into a stored state and a verdict a gate can read.

## How a gate handles AWAITING_GRADING: by there being nothing to read

`AttemptGradedHandler` in catalog records a `PASSED` against every node pointing at the test. Two
absences carry the whole criterion:

- **An attempt awaiting a person announces nothing at all** — assessment only publishes settled
  attempts, so a gate cannot open on a provisional score.
- **A failed attempt records nothing either**, which is not the same thing and does not need to be:
  a gate reads the *absence* of a `PASSED` row, and nothing can write a negative for it to confuse
  with "not yet".

So the evaluator needs no code for this state. `RequiredState.PASSED` has been sayable since T-5.3
and unreachable until now — "pass the safety test" was a rule nothing could satisfy.

## The audit is ordered by a sequence, not by the clock

`grade_event` is **append-only**; the score columns on `attempt` are a cache of its newest row. A
regrade adds a row and the previous verdict stays legible with the person and the moment on it.

**Ordered by `seq`, a `bigserial`.** Two verdicts reached in the same microsecond — a mark and the
recompute it triggers — tie on a timestamp, and "newest first" then resolves to whichever id sorted
higher. That can show a **superseded verdict as the current one**, which is the single worst thing
an audit can do. A sequence is a total order; a clock is not.

Found by a test that marked and regraded against a stopped clock, which is the same instant a fast
machine produces on its own.

## Rubrics

Criteria live **on the question, not the version** — ADR-0106 is the reason rather than an exception
to it: an examiner improving how they mark has not changed what anybody was asked, so it sits with
the internal name and the difficulty on the non-versioned side. What protects the record is
`response_criterion_mark`, which says what was awarded per criterion at the time; a criterion
somebody has marked against cannot be deleted.

Three rules, each a way a mark can be *meaningless* rather than merely wrong:

- **A rubric makes the breakdown required.** A single number against a question with three criteria
  tells a learner nothing about which part they lost, which is the only thing a rubric is for.
- **Every criterion, and no others.** A missing one is a grader who stopped halfway; an unknown one
  is a mark against something nobody wrote.
- **None over its maximum.** Five out of three is not a generous grader, it is a number nobody can
  interpret.

The criteria do **not** have to sum to what the question is worth — a rubric is a way to think, and
how much the question counts for comes from the form (T-6.5) and the section's weighting (T-6.4).

## Marking settles the attempt

Marking the last outstanding answer recomputes the whole attempt and settles it. A separate "finish
grading" call would be a step somebody forgets, leaving an attempt marked in every part and settled
in none.

A person's mark **survives a later recompute**: a grader who overrules the machine on one answer is
not quietly undone when somebody else marks the essay.

## The learner is told by catalog, not by assessment

Catalog already holds the learner's address (a projection of identity's, T-5.6) and already owns
telling people about their training. Sending it from assessment would put a **third copy of every
learner's email address in a third database**, fed by the same event, to say one sentence.

The event carries `wasAwaitingAPerson`, and only that kind produces a letter: an exam marked the
instant it was submitted needs none — the learner is looking at the result. A failed attempt
somebody waited days for still gets one; "you did not pass" is the answer they were waiting for.

A mail failure is logged and swallowed. An exam result does not stop existing because a letter did
not arrive.

## The permission this is not scoped by

The criterion asks for a queue "scoped by permission". It is scoped by **tenant and nothing else** —
the same gap every endpoint in this module carries, for the reason `BankService` states: the
evaluator and its grants live inside `identity` and a separate process cannot ask it anything
(T-9.11, ADR-0109). A local "is this person a grader" check written here would be exactly the
special case ADR-0103 refuses, and it would be the thing an endpoint later trusts.

The shape is right and the check is absent, deliberately and visibly.

## What is not here yet

- **A permission on the queue**, above.
- **Review.** T-6.9 (#68) renders what was served from the form, under a policy, and per-question
  feedback belongs on the question version there.
- **Item analysis.** T-7.7 (#75) reads `credited`/`available` per served version — which is why a
  person's mark deliberately does *not* invent that pair for an essay.
