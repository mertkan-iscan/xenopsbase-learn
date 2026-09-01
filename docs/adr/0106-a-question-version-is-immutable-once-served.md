# ADR-0106: A question version is immutable once it has been served

- **Status:** Accepted
- **Date:** 2026-08-31
- **Task:** T-0.6 (#6)

## Context

Authors edit questions, and learners have already answered them. Those two facts are both
permanent, and every design that lets an edit reach the row an attempt points at turns last
quarter's scores into claims about a question that no longer exists.

The failure is quiet, which is what makes it worth deciding before there is any code. Nothing
breaks at edit time. The report still renders, the score is still a number, the gate still opens
or does not. The discrepancy surfaces the first time somebody disputes a result — a certification
denied, a compliance record challenged, an auditor asking what was actually covered — which is
precisely the moment at which "we think it said something like this" is worth nothing.

The product this constrains is E6: banks, tests drawn randomly per learner (T-6.5), attempts with
review policies (T-6.9), item analysis (T-7.7), and rescoring after an answer key turns out to be
wrong (T-6.4). Every one of those needs a stable answer to **what exactly did this person see**.

## Decision criteria

- **Can "what exactly did this person see" be answered three months later, from the database
  alone** — without an audit log, without a backup, without asking the author what they remember
- **Storage and query cost of item analysis across versions**
- **What an author expects to happen when they fix a typo** — a model that surprises them is a
  model they will work around
- **Whether the immutability is enforced by the database or by the code that remembers to**

## Decision

**A `question` is an identity. A `question_version` is what was asked. An attempt references the
version, and a version becomes immutable the moment it is first served.**

The foreign key, stated exactly, because the whole decision reduces to which row this points at:

```
attempt_response.question_version_id  uuid NOT NULL REFERENCES question_version (id)
```

`ON DELETE RESTRICT`, never `CASCADE`. A cascade here is a delete that silently removes results.

An attempt holds **only** the version id. It does not also carry `question_id`: the question is
reachable through `question_version.question_id`, and storing both invites the day they disagree.
Arrangement — which questions were drawn, in what order, with options shuffled which way — is not
a property of the version and is pinned separately by `test_form` (T-6.5), so a review screen can
reconstruct the page rather than approximate it.

### Immutability begins at first service, and that is the whole answer to the typo

The tension the acceptance criteria name — always versioning is noisy, never versioning is wrong
— dissolves once the rule is tied to service rather than to editing:

- A version **nobody has been served** is a draft. Editing it updates that row. No new version,
  no noise, no archaeology of a question that never reached a learner.
- A version **that has been served** is history. Editing it creates a new version and moves
  `question.current_version_id`; the old row stays exactly as it was, reachable forever.

So an author who fixes a typo before anyone sits the test sees nothing at all — the same row,
corrected. An author who fixes it afterwards gets a new version, and every attempt already
recorded still renders the sentence its learner actually read. That matches what authors expect
in both cases, which is the third criterion, and it is the reason this ADR's title says *served*
rather than *created*.

**Enforced by the database, not by the service.** `question_version` carries `first_served_at`,
set when a version is first put in front of a learner, and a trigger refuses any `UPDATE` that
changes a content or scoring column while it is non-null. T-6.2 implements this. The reason it is
a trigger rather than a rule in the repository layer is that the repository layer is not the only
thing that ever writes to a database: a support fix applied in SQL, a migration, a future service
in another language. An invariant this important is worth stating where every writer meets it.

Content and scoring columns are: the stem, its media references, the options and their text, the
answer key, per-option weights, the scoring mode and any tolerance. Not versioned, because they
are neither served nor scored: the internal name, tags, difficulty metadata, author notes and
bank membership. Moving a question between banks is not an edit to what was asked.

### Item analysis is per version, and rolling up is a deliberate act

Difficulty and discrimination (T-7.7) are computed per `question_version_id` and reported with
that version's `n`. This is not a limitation to work around; it is the arithmetic being honest.
Pooling responses across two versions is pooling answers to two different questions, and the
difference between them may be exactly the thing that changed the difficulty.

Rolling up across versions is available and explicit: an analyst asks for the question's lifetime
figures and is shown the version boundaries inside them. The default never silently averages
across an edit.

### Deleting a question

A question with any served version is never hard-deleted. "Delete" in the authoring UI sets
`question.retired_at`: it disappears from authoring and from every future draw, and every existing
attempt still renders. The versions stay; the results stay; the reports stay correct.

Hard deletion exists for exactly one case — a question whose versions have never been served, i.e.
a mistake being cleaned up before it reached anybody. That is a real case and it costs nothing to
allow, because by construction nothing references it.

### Correcting a wrong answer key

The version that was served is never repaired, because it is the record of what was asked and
marked. The correction is a new version, and rescoring is a separate, audited act that records
which attempts were rescored and against which version's key, keeping the original mark (T-6.4).
The learner's score changes; the history of how it changed does not disappear.

## Consequences

### What this makes easy

- A disputed result is answerable from the database in one join, three months or three years
  later.
- Review mode (T-6.9) reconstructs what was served rather than re-deriving it from the current
  question, which would show the learner a paper nobody sat.
- An answer-key error becomes a bounded, auditable operation instead of a data-fix with no record.

### What this makes hard

- Every learner-facing read goes through a version. "Load the question" is never
  `SELECT * FROM question`, and two ids travel together through authoring, delivery and reporting.
- Authoring has to show the author which version they are editing and what will happen when they
  save, or the model is honest and the UI is not.
- Reporting queries carry a version dimension they cannot drop.

### What it commits us to

Versions are never garbage-collected — an attempt from four years ago still points at one. The
storage is a row of text per edit-after-service; **estimated, not measured**: at 100,000 questions
averaging ten served versions each, `question_version` is on the order of a million small rows,
which is small beside the attempt and response tables it exists to keep meaningful. If that
estimate is ever wrong it will be wrong because of embedded media, and media is a `content_item`
reference rather than bytes in the row (T-6.3).

Reversal is expensive in one direction only: collapsing versions later means deciding which
historical attempts to lie about.

## Alternatives considered

### Option 1: mutable questions with an audit log of edits — rejected

Reconstructing what a learner saw means replaying edits backwards from now, which is archaeology
rather than a query, and it is only as complete as the log. The log is written by application
code, so any writer that bypasses it — a support fix in SQL, a bulk import, a future service —
leaves a gap that is invisible until somebody depends on it. And item analysis has no key to
group by: every response is attributed to "the question", including responses to a question that
has since had an option removed.

The first decision criterion asks whether the answer comes from the database alone. This option's
answer is "from the database plus a log we hope is complete", which is a different sentence.

### Option 2 was chosen; option 3 was partly adopted

### Option 3: copy the whole question into the attempt at serve time — rejected as the model, kept for arrangement

Copying answers "what did they see" perfectly, and loses everything else. Storage grows with
attempts rather than with edits. Item analysis across a bank becomes text comparison between
copies instead of a group-by on an id. A rescore after a key correction has no shared row to
correct — every copy would have to be found and updated, which is mutation of history wearing a
different hat.

What it is right about is **arrangement**, which genuinely is per attempt and not per version:
the draw, the order, the shuffle. That is snapshotted, in `test_form`. The distinction is worth
keeping precise — the version records *what was asked*, the form records *how it was laid out*.

## Revisit if

- A question type appears whose content cannot live in a row — an externally hosted interactive
  item, say — in which case a version pins a content hash and an immutable object key instead,
  and the same rule applies to that pin.
- A customer requires a question's text to be purged (not a right-to-erasure case, which concerns
  personal data, but a licensing one). The answer would be redaction that preserves the row and
  the score, and it should be decided deliberately rather than by whoever is on call.
- Item analysis across versions turns out to be what everybody actually wants and the per-version
  default is fought rather than used — that would mean the model is right and the reporting
  default is wrong, which is a change to T-7.7 and not to this.
