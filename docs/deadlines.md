# Deadlines, cycles and reminders

**Task:** T-5.6 · **Depends on:** T-5.5 (assignment), [ADR-0104](adr/0104-user-identity-is-ours.md)
(identity owns the person), [ADR-0109](adr/0109-eight-modules-six-processes.md) (no module reads
another's schema)

A deadline is the point where a compliance report stops being a list and becomes a claim about
people. Three questions decide what that claim means, and the whole of this task is answering them
on purpose rather than by accident.

## The rule, in one paragraph

**A deadline is a calendar date. It expires when that date ends in the learner's own timezone.
Before that instant the obligation is upcoming; from it, overdue. Overdue changes nothing else.**
It does not revoke the assignment, does not close the course, and does not stop somebody doing the
training. A platform whose response to being late with mandatory training is to prevent the
training turns a fixable gap into a permanent one, and an auditor finds that rather than we do.

## The three questions

### 1. Does overdue block access, or only mark a state?

**It marks a state, and there is no column for it.** Overdue is computed from the due date and the
clock every time it is asked for.

A stored flag would need a job to flip it, so "overdue" would mean *overdue as of whenever that job
last ran* — a compliance report describing the health of a cron entry rather than the state of a
company. The computed answer is the same whether anything ran or not.

### 2. What is the due date for somebody who joins the group after the assignment was made?

**Whatever the person who made the assignment said it was.** Both answers are right for different
training and neither is right for both, so `due_basis` is `NOT NULL` for every relative deadline:
somebody chose.

| Kind | Basis | What it means |
|---|---|---|
| `ABSOLUTE` | — | One date for everybody. "Everyone by the audit on 31 March." A late joiner may be overdue on their first day, and that is the true state of a company that hired somebody a week before an audit |
| `RELATIVE` | `ASSIGNED` | So many days from the assignment. One date for everybody, computed rather than typed |
| `RELATIVE` | `REACHED` | So many days from when *this learner* came into scope. The onboarding answer: every joiner gets their own clock |

`REACHED` counts from the **later** of "the assignment was made" and "this learner was reached", so
a new course assigned to an existing department does not land on it already overdue.

### 3. Does annual training create a new assignment, or reopen the old one?

**Neither: it opens a new cycle of the same assignment.** Reopening destroys the only thing a
compliance report is for — "did they do it in 2025" stops being answerable the moment 2026 reuses
the row. A fresh assignment each year would split one standing obligation into a pile of them and
collide with the index that stops the same course being assigned twice.

Every assignment with a deadline has **at least one cycle**, recurring or not, so reminders,
overdue and history have one thing to hang off rather than two nearly identical paths of which the
rare one is wrong. Cycles are opened **on demand** rather than by a nightly job: they are derived
from the assignment's own dates, so a job that never ran cannot make the answer wrong.

## Timezone is per learner, and here is where each part of it lives

- **The zone is on the person**, in `identity` (`app_user.time_zone`), because identity owns the
  person. `PUT /api/v1/users/me/timezone` sets the **caller's own** and takes no id: moving to
  Berlin is not an administrative act, and an endpoint that let one person set another's would be
  one that moved somebody else's compliance deadline.
- **It reaches `catalog` as an event** (`identity.user.profile`), never as a query — `catalog` must
  not read identity's schema, and the deadline is computed on the screen a learner opens first.
- **Null means "they have not told us"**, which is a different state from UTC. The fallback is
  applied where the deadline is computed and named there, so the people who have never set one stay
  findable. It is never guessed from an address, a browser or an IP: a guess is wrong silently, for
  years, and follows somebody who travels.
- **A deadline is stored as a date, never as an instant.** "By the 31st" is a different moment for
  everybody, and the learner's own is the only one they can act on. Two people with the same due
  date are late at different moments, and a learner has all of the due day — the deadline is the
  start of the following day locally, so finishing at 23:50 is on time.

## Reminders

An assignment carries a set of offsets in **days added to the due date**: `-14` is a fortnight
before, `0` is the day itself, `+7` is a nudge a week after it passed. The sign matches a timeline.
Mail goes out at 09:00 **in the learner's own timezone** — a server-side "09:00" reaches a third of
a global company in the middle of the night, which is how reminders get filtered into a folder
nobody opens.

**Idempotency is a primary key, not a piece of reasoning.** The pass claims a row in
`reminder_sent` and commits *before* handing anything to a mail provider, so a second replica, a
restart or a cluster rebuild finds the row and does nothing.

**The cost of that ordering, stated: reminder mail is at-most-once.** A crash between the claim
committing and the provider accepting the message loses that reminder and leaves a row saying
`CLAIMED`. The other order — send, then record — re-sends the entire backlog after any crash, which
is the failure T-5.6 names by its symptom: a week of mail arriving at once. A reminder that did not
arrive is visible in `GET /api/v1/reminders/unsent`; a week of duplicates is visible to the
customer.

**A window missed by more than the catch-up (two days) is recorded as missed rather than sent.** A
service that has been down for a week must not deliver a week of nudges when it returns, and must
not pretend the window never existed either.

**A mail failure never blocks the assignment.** Nothing in the pass writes to `assignment`; a
refusal is recorded against the reminder and the pass moves to the next person, because one bad
address must not stop a department's mail. Somebody who was not reminded still owes the training,
and somebody whose provider bounced has not become compliant.

**Nobody is reminded about training they have already finished** — within the cycle's window, not
ever, which is what makes the cycle worth having.

## The mail provider

There was not one. Invitations (T-1.9) hand their token back to the caller and are explicitly never
mailed by us, so `Mailer` in `platform-common` is the first: a port with an SMTP implementation and
a `LoggingMailer` that delivers nothing and says so at startup. Two tasks need it — reminders here,
scheduled reports in T-7.9 — which is why it lives in the shared module rather than inside either.

**Locally there is no mail server**, and that is the same choice the bus makes with an empty
`nats-url`: `make up` gives a working platform, every reminder is claimed and recorded, and a WARN
at startup says nothing was delivered. Set `spring.mail.host` and `platform.mail.from` to send.

## What this does not do yet

- **A recurring obligation cannot yet be satisfied twice.** Completion is recorded per
  `(learner, node)` (T-3.7), so a learner who completed the 2026 cycle and does the training again
  in 2027 has no second completion to find, and the reminder pass reads them as already finished.
  Fixing it needs the writer and the reader changed together — the completion record has to carry
  which cycle it belongs to — and it belongs with whoever makes completion cycle-aware, not here.
- **Nothing schedules a report of who is overdue.** The state is computable per learner
  (`GET /api/v1/assignments/of/{learner}` carries `dueOn`, `overdue` and `cycleNumber`); the
  company-wide view is E7's rollup (T-7.6).
- **No screen shows any of this.** The learner home screen is T-5.8.
