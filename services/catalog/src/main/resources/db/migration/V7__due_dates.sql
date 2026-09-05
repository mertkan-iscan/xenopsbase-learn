-- V7 — due dates, recurrence cycles and reminders (T-5.6).
--
-- THE THREE QUESTIONS THE ISSUE SAID TO ANSWER BEFORE WRITING CODE, ANSWERED
-- HERE BECAUSE THE SCHEMA IS WHERE AN IMPLICIT ANSWER DOES ITS DAMAGE.
--
-- 1. DOES OVERDUE BLOCK ACCESS, OR ONLY MARK A STATE?
--    It marks a state, and there is no column for it -- overdue is computed
--    from the due date and the clock, every time it is asked for. Two reasons,
--    and the second is the one that matters. A stored flag needs a job to flip
--    it, so "overdue" would mean "overdue as of whenever that job last ran", and
--    a compliance report would be describing the job's health rather than the
--    company's. And blocking access on it would mean the platform's response to
--    somebody being late with mandatory training is to prevent them doing it --
--    which turns a fixable gap into a permanent one, and would be discovered by
--    an auditor rather than by us.
--
-- 2. WHAT IS THE DUE DATE FOR SOMEBODY WHO JOINS THE GROUP AFTERWARDS?
--    Whatever the person who made the assignment said it was, because both
--    answers are right for different training and neither is right for both.
--    An absolute date ("everyone by the audit on 31 March") does not move for a
--    late joiner; they may be overdue on their first day, and that is the true
--    state of a company that hired somebody a week before an audit. A relative
--    date reckoned from REACHED ("within 30 days of joining") is the onboarding
--    answer and gives every joiner their own clock. due_basis is NOT NULL for
--    every relative due date, so this is a decision somebody made rather than a
--    default nobody saw.
--
-- 3. DOES ANNUAL TRAINING CREATE A NEW ASSIGNMENT, OR REOPEN THE OLD ONE?
--    Neither: it opens a new CYCLE of the same assignment. Reopening destroys
--    the thing a compliance report is for -- "did they do it in 2025" stops
--    being answerable the moment 2026 reuses the row. A new assignment each
--    year splits one standing obligation into a pile of them, collides with the
--    partial unique index that stops the same course being assigned twice, and
--    makes "why do I have this" a list that grows forever. A cycle is a row
--    with a window; last year's row is still there, still says what it said.

-- ---------------------------------------------------------------------------
-- The deadline, on the assignment that carries it.
-- ---------------------------------------------------------------------------

-- NONE, ABSOLUTE or RELATIVE. Defaulted to NONE for the rows that already
-- exist, which is the honest value: an assignment made before this migration
-- was made without a deadline, and inventing one retroactively would put dates
-- on obligations nobody agreed to.
ALTER TABLE assignment ADD COLUMN due_kind varchar(16) NOT NULL DEFAULT 'NONE';

-- ABSOLUTE only: the calendar date it is due on.
--
-- A DATE, NOT AN INSTANT, AND THAT IS THE WHOLE TIMEZONE ANSWER (T-5.6's last
-- criterion). A deadline as people state it is "by the 31st", which is not a
-- moment -- it is a different moment for everybody, and the learner's own is
-- the only one they can act on. Storing an instant would force a zone to be
-- picked here, at write time, by whoever happened to be filling the form, and
-- somebody in Auckland who finished at 23:00 on the 31st would be filed as
-- late by a server reckoning in UTC. The date is stored; the moment it expires
-- is computed per learner, in their zone, every time it is asked for.
ALTER TABLE assignment ADD COLUMN due_on date;

-- RELATIVE only: how many days from the anchor. Days, not an interval, for the
-- same reason: the result is a DATE, and a sub-day interval has no meaning
-- against a deadline that ends when a day does.
ALTER TABLE assignment ADD COLUMN due_after_days integer;

-- RELATIVE only: ASSIGNED or REACHED -- question 2 above.
ALTER TABLE assignment ADD COLUMN due_basis varchar(16);

-- Recurrence, in months. 12 is the annual mandatory training this exists for;
-- null is the ordinary case of an obligation that is due once.
ALTER TABLE assignment ADD COLUMN recurrence_months integer;

ALTER TABLE assignment ADD CONSTRAINT ck_assignment_due_kind
    CHECK (due_kind IN ('NONE', 'ABSOLUTE', 'RELATIVE'));

-- Each kind carries exactly the columns it means, and none of the others. The
-- alternative -- letting a RELATIVE assignment also hold a stale due_on -- is
-- how two readers of the same row disagree about the deadline.
ALTER TABLE assignment ADD CONSTRAINT ck_assignment_due_shape CHECK (
    (due_kind = 'NONE'
        AND due_on IS NULL AND due_after_days IS NULL AND due_basis IS NULL)
 OR (due_kind = 'ABSOLUTE'
        AND due_on IS NOT NULL AND due_after_days IS NULL AND due_basis IS NULL)
 OR (due_kind = 'RELATIVE'
        AND due_on IS NULL AND due_after_days IS NOT NULL AND due_basis IS NOT NULL));

ALTER TABLE assignment ADD CONSTRAINT ck_assignment_due_basis
    CHECK (due_basis IS NULL OR due_basis IN ('ASSIGNED', 'REACHED'));

ALTER TABLE assignment ADD CONSTRAINT ck_assignment_due_after_days
    CHECK (due_after_days IS NULL OR due_after_days > 0);

-- Recurrence needs a deadline to recur against. "Every year, no due date" is a
-- sentence with no meaning, and the row that held it would produce cycles that
-- never end and reminders that never fire.
ALTER TABLE assignment ADD CONSTRAINT ck_assignment_recurrence CHECK (
    recurrence_months IS NULL
 OR (recurrence_months > 0 AND due_kind <> 'NONE'));

-- ---------------------------------------------------------------------------
-- Cycles: one row per period of a recurring obligation.
-- ---------------------------------------------------------------------------
--
-- EVERY assignment with a deadline has at least one cycle, recurring or not.
-- A non-recurring obligation is the one-cycle case rather than a separate
-- shape, so reminders, overdue and history have ONE thing to hang off instead
-- of two nearly-identical paths of which the rare one is wrong.
CREATE TABLE assignment_cycle (
    id            uuid        PRIMARY KEY,
    tenant_id     varchar(64) NOT NULL,
    assignment_id uuid        NOT NULL REFERENCES assignment (id) ON DELETE CASCADE,

    -- 1, 2, 3... The 2026 run of annual training is cycle 2, and cycle 1 is
    -- still sitting next to it saying what 2025 was.
    cycle_number  integer     NOT NULL,

    -- When this cycle's obligation started. For cycle 1 that is when the
    -- assignment was made; afterwards it is the previous cycle's due date,
    -- so the periods tile the timeline with no gap and no overlap and every
    -- completion falls in exactly one of them.
    opens_at      timestamptz NOT NULL,

    -- The shared deadline DATE, WHERE THERE IS ONE. Null for a relative
    -- assignment reckoned from REACHED, whose date is different for every
    -- learner and therefore cannot be a column here. That one is computed;
    -- writing a row per learner is the materialisation T-5.5 exists to avoid.
    --
    -- Shared date, not shared moment: two people with this same date are late
    -- at different instants, because they are late when the day ends where
    -- they are.
    due_on        date,

    created_at    timestamptz NOT NULL,

    CONSTRAINT uq_assignment_cycle UNIQUE (assignment_id, cycle_number),
    CONSTRAINT ck_assignment_cycle_number CHECK (cycle_number > 0)

    -- AND NOTHING SAYING due_on >= opens_at, WHICH AN EARLIER DRAFT HAD.
    -- A deadline that has already passed when the assignment is made is a real
    -- thing a company does: the audit is on Friday, somebody notices on Monday
    -- that a department was never assigned the training, and the honest record
    -- is an obligation that is overdue from its first moment. Refusing that row
    -- would not make anybody less late; it would make the platform unable to
    -- say so, which is the failure mode this whole task exists to avoid.
);

-- "What is open now", which is the read every reminder pass and every learner
-- screen makes.
CREATE INDEX ix_assignment_cycle_open ON assignment_cycle (tenant_id, due_on);

-- ---------------------------------------------------------------------------
-- Which reminders an assignment wants.
-- ---------------------------------------------------------------------------
--
-- A child table rather than an array column, so "remind twice fourteen days
-- before" is refused by the database instead of producing two identical mails.
CREATE TABLE assignment_reminder (
    assignment_id uuid    NOT NULL REFERENCES assignment (id) ON DELETE CASCADE,
    tenant_id     varchar(64) NOT NULL,

    -- DAYS ADDED TO THE DUE DATE, so the sign matches a timeline: -14 is a
    -- fortnight before, 0 is the day itself, +7 is a week overdue. An earlier
    -- draft called this days_before, which made the overdue nudge a negative
    -- number of days before the deadline -- correct, and unreadable.
    offset_days   integer NOT NULL,

    PRIMARY KEY (assignment_id, offset_days)
);

-- ---------------------------------------------------------------------------
-- What has actually been sent. THE IDEMPOTENCY RECORD.
-- ---------------------------------------------------------------------------
--
-- This table is the answer to "a cluster rebuild does not re-send a week of
-- mail" (T-5.6's third criterion), and it works by being written FIRST.
--
-- The pass claims a row inside a transaction and commits before any mail is
-- handed to the provider. A second instance of the service, or the same
-- instance after a restart, finds the row and does nothing. The primary key is
-- the claim -- concurrency is settled by the database rather than by a lock
-- somebody has to remember to take.
--
-- WHAT THAT COSTS, STATED: claiming before sending makes reminder mail
-- AT-MOST-once. A crash in the window between the commit and the provider
-- accepting the message loses that reminder, and the row is left saying
-- CLAIMED forever. That is deliberate. The other order -- send, then record --
-- re-sends the entire backlog after any crash, which is the failure the
-- criterion names, and the one a customer notices. A reminder that did not
-- arrive is visible here as a CLAIMED row that never became SENT.
CREATE TABLE reminder_sent (
    tenant_id   varchar(64) NOT NULL,
    cycle_id    uuid        NOT NULL REFERENCES assignment_cycle (id) ON DELETE CASCADE,

    -- app_user.id (ADR-0104). Per learner, because a group assignment's
    -- reminder is one mail each, not one mail.
    learner_id  uuid        NOT NULL,
    offset_days integer     NOT NULL,

    claimed_at  timestamptz NOT NULL,
    -- CLAIMED, SENT or FAILED. FAILED is kept rather than deleted: a reminder
    -- that bounced is something the administrator who set it should be able to
    -- see, and deleting the row would silently arrange for a retry.
    outcome     varchar(16) NOT NULL,
    detail      text,

    PRIMARY KEY (cycle_id, learner_id, offset_days),
    CONSTRAINT ck_reminder_outcome CHECK (outcome IN ('CLAIMED', 'SENT', 'FAILED'))
);

-- "Which reminders failed", for the administrator who has to care.
CREATE INDEX ix_reminder_sent_failed ON reminder_sent (tenant_id, claimed_at)
    WHERE outcome <> 'SENT';

-- ---------------------------------------------------------------------------
-- The learner's timezone, projected from identity.
-- ---------------------------------------------------------------------------
--
-- T-5.6's last criterion is that timezone is per learner and not per server,
-- and a deadline is the place where getting that wrong is visible: "end of
-- 31 March" is nine hours apart in Auckland and Los Angeles, and a learner in
-- either who submitted at 23:00 local on the day would be marked late by a
-- server reckoning in UTC.
--
-- A PROJECTION, not a join. Identity owns the person (ADR-0104) and catalog
-- must not read identity's schema (ADR-0109); the timezone arrives as an event
-- and lands here, the same shape as learner_group_reach next to it. Reading it
-- must also not fail when identity is down, because this is on the screen a
-- learner opens first.
CREATE TABLE learner_profile (
    tenant_id  varchar(64) NOT NULL,
    learner_id uuid        NOT NULL,

    -- An IANA zone id, e.g. Europe/Istanbul. Null means the person has not
    -- told us, which is a real state and not the same as UTC -- the fallback
    -- is applied when the deadline is computed, and named there.
    time_zone  varchar(64),
    email      varchar(320),
    display_name varchar(255),

    -- WHEN CATALOG FIRST HEARD OF THIS PERSON, which is the best available
    -- answer to "when did they join" for a company-wide assignment reckoned
    -- from REACHED. Named for what it is rather than joined_at: identity owns
    -- when somebody joined the company, and a column here claiming to know
    -- that would be believed.
    first_seen_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    PRIMARY KEY (tenant_id, learner_id)
);

-- ---------------------------------------------------------------------------
-- When a group started reaching a learner.
-- ---------------------------------------------------------------------------
--
-- This is what makes "within 30 days of joining" computable. Without it a
-- relative deadline could only be reckoned from the assignment, so somebody
-- who joined the company eleven months after the onboarding course was
-- assigned would be ten months overdue on their first morning.
--
-- DEFAULT now() for the rows already there: they were reached at some point
-- nobody recorded, and the least wrong answer available is "when we started
-- recording". Backdating them to the assignment would make every existing
-- learner instantly overdue, which is a worse lie in the same shape.
ALTER TABLE learner_group_reach ADD COLUMN reached_at timestamptz NOT NULL DEFAULT now();
