# Scoring: what a right answer is worth, and what the number means

**Task:** T-6.4 · **Service:** `assessment` · **Code:** `scoring/`, `exam/`

T-6.3 answers *how much of this was right* — `Correctness`, two integers, "3 of 5 pairs matched".
This page is what that is worth, and why the two are separate: correctness belongs to the question
type and never changes, while its value belongs to the test and an author may edit it and rescore.

The rule the whole design serves, from the issue: **gates and reports read `score_scaled`, a 0–1
value.** A pass rule written against raw points breaks the moment a section changes length or a
question's weight is edited, and it breaks quietly — the test still runs, the pass mark just means
something different than it did last month.

## The arithmetic, in three steps

```
question   award   = mode(correctness, points)      … minus a penalty, if it was a wrong guess
section    scaled  = max(0, Σ award) / Σ points     … over the questions a machine could mark
test       scaled  = Σ(sectionᵢ.scaled × weightᵢ) / Σ(weightᵢ)
           percent = floor(scaled × 100)
           passed  = percent ≥ passMarkPercent
```

**A weighted mean of fractions, not a sum of marks.** With a sum, a forty-question section
outweighs a ten-question one four to one whatever the author configured, and adding a question
silently changes what every other section is worth. Here the author's weights are the only thing
that decides.

## The modes, and what is available where

| type | `ALL_OR_NOTHING` | `PARTIAL_CREDIT` | negative marking |
|---|---|---|---|
| `single-choice` | ✔ | ≡ all-or-nothing (`available` is 1) | ✔ |
| `multiple-choice` | ✔ | ✔ right picks − wrong, floored | ✔ |
| `true-false` | ✔ | ≡ all-or-nothing | ✔ |
| `matching` | ✔ | ✔ per pair | — |
| `ordering` | ✔ | ✔ per absolute position | — |
| `fill-in` | ✔ | ✔ per blank | — |
| `numeric` | ✔ | ≡ all-or-nothing | — |
| `hotspot` | ✔ | ✔ right regions − wrong, floored | ✔ |
| `essay` | — | — | — |
| `file-upload` | — | — | — |

`≡ all-or-nothing` is arithmetic, not a special case: a type whose `available` is 1 gives the same
answer under either mode. An author who turns partial credit on for a whole test has not quietly
changed what those questions are worth.

The last two are marked by a person (T-6.3's `grade` returns empty for them), so no mode applies
until T-6.7's queue exists.

### Negative marking is only for guessing

The column is on the test, but **the question type has the last word** —
`QuestionTypeDefinition.guessable()`, default false. Four choices means a quarter of blind guesses
score, and a penalty is how a test stops rewarding that. A typed answer has no such odds; nobody
guesses "Ankara". A penalty there does not deter a guesser, it charges somebody for a typo or for a
spelling their author did not think of.

**An unanswered question is never penalised.** Penalising a blank converts "I do not know" into a
worse outcome than a guess, which is the opposite of the point. `Correctness` cannot tell blank from
wrong — it floors `credited` at zero for both, deliberately — so `Responses.wasAnswered` is what
separates them.

### Per-option weights are deliberately not a mode

They are in this task's scope and they are not implemented, for a reason worth stating rather than
leaving as a gap. Every scheme beyond the two modes — per-option weights, graduated bands, "half
marks for close" — needs `grade` to report *which* parts were right rather than how many, which is a
different contract from `Correctness`. And the natural place to put those weights is the answer key,
which is **immutable once served** (ADR-0106). A weight that cannot be corrected without a new
version of the question, and therefore without orphaning every attempt's recorded form, is a weight
nobody can fix — and correcting a mistaken weight and rescoring is something this product must be
able to do.

The way to make one part of a question worth more is to make it its own question.

## The floor is per section, which is stronger than the criterion

T-6.4 requires that negative marking cannot take a **test** score below zero. The floor is applied
to each **section**, and that is deliberate:

- A section is a reported unit. "You scored −20% on Fire safety" is not something a learner or their
  manager can act on, and a report that can print it will print it.
- Flooring only at the end lets one disastrous section eat into another's marks. An author who
  weighted two sections equally meant each is worth half; a negative one silently makes the other
  worth more than all of it.

Inside a section, a wrong guess still cancels a right one. It just cannot reach across a boundary
the author drew. The unfloored `raw` is kept on the section so a result screen can still show what
the guessing cost — which is the only way anybody learns not to.

## The pass boundary: 79.5% against a pass mark of 80

**It fails, and the learner is shown 79%.**

The second half is the part that matters. The failure mode this rule exists to prevent is a screen
reading "80%" next to "failed", which is what happens when the score is 0.795 and the display rounds
half up. There is no explaining that to anybody.

So the displayed percentage and the verdict are **not two roundings that agree — they are one number
used twice**:

```java
int percent = floor(scaled × 100);
boolean passed = percent >= passMarkPercent;
```

Flooring is the only rounding that cannot put a percentage at or above the pass mark beside a fail.
Keeping `pass_mark_percent` a **whole percent** is what makes that exact rather than nearly exact —
it is the reason the column is a `smallint` and not a `numeric`.

Every intermediate fraction is truncated (`RoundingMode.DOWN`, ten places) for the same direction of
safety: no intermediate may round *up* across the boundary and pass somebody the rule failed. A
single question's award rounds `HALF_UP` at six places, where the error is at most 5×10⁻⁷ per
question — a section would need about ten thousand questions before that could move a percentage
point.

## An unmarked question is not a wrong one

A question waiting for a person contributes to **neither** total, and the test score comes back
`provisional`. A ten-question section with one essay is scored out of nine until somebody reads it.

This is the arithmetic under T-6.7's `AWAITING_GRADING`, and getting it wrong is how a gate reads a
missing score as a fail and locks a learner out of a course they may well have passed. A test where
*nothing* could be marked returns `nothingToScore` — never passed, and never a fail. Zero would be a
lie in the direction that costs somebody a course.

An empty section is excluded the same way, and is **not** provisional: there is nothing in it for
anybody to mark.

## What is not here yet

- **Storing a score.** `score_raw` and `score_scaled` both have to be written, and the only thing
  they can be written on is an attempt — T-6.6 (#65). Both are computed here and the `TestScore`
  record carries both, so the attempt stores what it is handed.
- **Rescoring after an answer-key correction**, audited and keeping the original. Also T-6.6's, for
  the same reason: there is nothing to rescore. Editing a test's scoring policy today deliberately
  does **not** rescore anything already sat — a policy edit that silently rewrote a compliance
  record nobody was told about is the failure that criterion is guarding against.
- **Sections themselves.** T-6.5 (#64) defines them; `SectionMark` is the shape their result takes,
  written now because a test score assembled in one place and a section score assembled in another
  is two answers to the same question.
