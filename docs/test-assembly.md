# Assembling a test: the instruction, and the record

**Task:** T-6.5 · **Service:** `assessment` · **Code:** `exam/`, `form/`

Two halves that only make sense together.

| | what it is | when it is read |
|---|---|---|
| `test_section` | the **instruction** — "five medium questions tagged fire-safety, shuffled" | every time somebody starts |
| `test_form` | the **record** — exactly which versions that produced, in the order shown | for ever afterwards |

**Random assembly without a recorded form is unreportable and undefendable.** You cannot answer what
a learner saw, you cannot compute item statistics, and you cannot rescore. The form is what turns
randomisation from a liability into a feature.

## The failure this exists to prevent

From the issue: *a pool query that matches fewer questions than the section asks for. **Silently
serving a short test is the worst outcome** — the learner's score is out of a different total and
nobody is told.*

It is refused **twice**, through **one predicate** (`QuestionPool.where`):

- **At authoring time.** `PUT /sections/{id}/pool` answers `409` with both numbers — *"asks for 5
  questions and its pool has 2"*. `GET /sections/{id}/pool` gives an editing screen the count to
  watch as the author narrows a filter.
- **At attempt start.** `FormAssembler` refuses the same way. This is not belt-and-braces: a pool
  that was large enough in March is not large enough in June if somebody retired half the bank, and
  nothing about editing the section notices.

One predicate, because two queries that were *meant* to agree are two queries that eventually do
not — and the day they disagree is the day somebody sits a shorter exam.

## Two kinds of section, one table

`FIXED` names its questions in order; `POOL` describes a population and draws `drawCount` of them
per learner. One table, because everything after selection — weight, shuffling, scoring, the form —
is identical, and two tables would duplicate all of it to express one branch.

### What a pool filters on

| filter | shape | why |
|---|---|---|
| bank | one id | the population has a name and an owner (T-6.1) |
| difficulty | a **rank range** | "medium or harder" is a range. A stored set of level ids silently stops including a level the company inserts in the middle of its scale |
| tags | **all** of them | narrowing can only make a pool smaller, and a small pool is refused loudly. "Any of these" widens silently, and is expressible as two sections |

A question with no difficulty is excluded the moment a section asks about difficulty at all. That is
the correct answer and an unwelcome one, which is why the count is on screen before publishing.

`ORDER BY random()` sorts the matching set and takes the first *n*. Right for a bank of a few
thousand; the replacement at a hundred thousand is a keyset sample rather than a cleverer sort. The
naive version is chosen, not stumbled into.

### Tags are a join table, difficulty is a column

V2 deferred both shapes to this task. T-6.1 built `bank_tag` because a mistyped tag does not produce
an error, it produces a shorter exam — silently, and only after it has been sat. **A `text[]` column
cannot have a foreign key**, so nothing would catch the typo and the vocabulary would be a list
nobody had to use. `question_tag` is what turns "checked at authoring time" into a constraint the
database keeps.

Difficulty is single-valued and ordered, so it is one reference to `bank_difficulty`, whose `rank`
carries the order.

Neither is versioned (ADR-0106): an author deciding a question is harder than they thought has not
changed what anybody was asked, so `PUT /questions/{id}/description` creates no version.

## Shuffling: the type decides what may move

`shuffle_questions` and `shuffle_options` are two flags because they are two decisions — a fixed
section that builds an argument question by question must not be reordered, and its multiple-choice
options still should be.

**Which option lists may be reordered is the question type's answer**
(`QuestionTypeDefinition.shufflableOptionFields()`, T-6.3's one dispatch point), not the assembler's:

| type | shuffled | |
|---|---|---|
| `single-choice`, `multiple-choice` | `choices` | the oldest reason forms are shuffled |
| `matching` | `left`, `right` | shuffling only one column leaves the other in authoring order, which is often the answer order |
| `ordering` | `items` | closer to required than optional — an ordering question in its authored order is one everybody gets right without reading |
| `true-false` | — | "false, true" is not a harder question, it is a confusing one |
| `fill-in` | — | the blanks are positions in the stem |
| `hotspot` | — | the regions are pinned to an image |
| `numeric`, `essay`, `file-upload` | — | nothing to reorder |

Default is **no shuffle**: a wrong shuffle is silent and a missing one is merely a shame.

## The order is stored, not re-derived

The seed is recorded, and so is the resulting order. That looks redundant until you ask what
re-deriving would couple: a review screen (T-6.9) has to render **exactly** what was seen, and
replaying a seed makes that depend on this shuffle algorithm never changing, in any language,
for ever. The order is a few strings; the coupling is not worth saving them.

The seed is there for the support question *"is this really what the assembler produces"*, which is
otherwise unanswerable.

## Reassembly is impossible

Three locks, and the criterion needs all three:

1. `test_form.attempt_id` is **unique** — a second assembly is refused by the database, not by
   whichever caller remembered to look. A check-then-insert has a window where two starts both find
   nothing and both write.
2. A trigger refuses `UPDATE` **and** `DELETE` on `test_form` and `test_form_item`. Enforced there
   rather than in a service for V2's reason: a support fix applied in SQL, a migration, or a future
   service in another language all reach this table.
3. `test_form_item.section_id` is `ON DELETE NO ACTION` — deleting a section a learner has sat is
   refused, because it would delete the record of what they were asked.

What a mutable form would mean, plainly: a learner sits a test, the form is edited, and the report
now describes an exam that was never taken. There is no log that recovers from that, and a disputed
certification is exactly the circumstance in which somebody looks.

## What is not here yet

- **The attempt** — done, in T-6.6 (#65) and [`attempts.md`](attempts.md).
  `AttemptService.startOrResume` is what calls `FormAssembler.assemble`, in the same transaction, so
  an insufficient pool leaves no attempt behind that could never be sat. `test_form.attempt_id`
  still carries no foreign key: adding one is a small migration T-6.7 or a follow-up can make now
  that `attempt` exists.
- **A review screen.** T-6.9 (#68) reconstructs what was served from the form, under a policy. The
  form already holds everything it needs.
- **Item analysis.** T-7.7 (#75) counts responses per served version; the form is the join it walks.
