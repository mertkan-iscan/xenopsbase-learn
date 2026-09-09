-- V8 — integrity signals: recorded, never used to auto-fail (T-6.8).
--
-- WHAT THIS IS, IN THE ISSUE'S OWN WORDS: "browser telemetry from a page the
-- learner controls. It is a signal for a human reviewing a suspicious result,
-- not a proctoring system, and the product documentation must not imply
-- otherwise."
--
-- WHY RECORDING AND NOT ENFORCING. Every one of these signals has an innocent
-- explanation. A screen reader moves focus. A second monitor takes it. A phone
-- drops to cellular and the address changes. Auto-failing on any of them
-- punishes disabled learners and people with bad connections at a much higher
-- rate than it catches anybody cheating, and it does so invisibly.
--
-- AND IT IS SELF-REPORTED, which is the fact that decides how much weight any of
-- it can carry. A learner who wants to hide a focus loss does not report one.
-- These rows corroborate a human's suspicion; they are not evidence, and nothing
-- that reads them may treat an ABSENCE as innocence.

CREATE TABLE attempt_event (
    id         uuid        PRIMARY KEY,

    -- THE ORDER, AND IT IS NOT THE TIMESTAMP -- the same lesson grade_event
    -- learned one migration ago (T-6.7). A burst of signals arrives inside one
    -- millisecond by design: focus lost, tab hidden, tab visible, focus regained
    -- is four rows and one gesture. Ordering those by a timestamp resolves ties
    -- to whichever uuid sorted higher, and a reviewer then reads somebody's
    -- attention wandering in an order that never happened.
    --
    -- A sequence is a total order. A clock is not, and a clock shared by four
    -- rows is not even a tiebreaker.
    seq        bigserial   NOT NULL,
    tenant_id  varchar(64) NOT NULL,

    -- CASCADE: an event has no meaning away from the attempt it describes, and
    -- an attempt deleted for a data-subject request must take its telemetry with
    -- it. The reverse -- deleting events while the attempt survives -- is the
    -- point of the separate retention below, and is done by the reaper.
    attempt_id uuid        NOT NULL REFERENCES attempt (id) ON DELETE CASCADE,

    -- FOCUS_LOST, FOCUS_REGAINED, TAB_HIDDEN, TAB_VISIBLE, PASTE,
    -- ADDRESS_CHANGED. A closed set, and deliberately a short one: every kind
    -- added here is a new thing the product collects about a person, which is a
    -- decision somebody should have to make on purpose rather than by widening
    -- a free-text column.
    kind       varchar(32) NOT NULL,

    -- TWO TIMES, AND THEY ARE NOT THE SAME KIND OF FACT.
    --
    -- `reported_at` is the browser's clock, which belongs to the learner and can
    -- say anything at all. It is kept because the ORDER of a burst within one
    -- second is sometimes what a reviewer is looking at, and nothing else has
    -- that resolution.
    --
    -- `recorded_at` is ours. It is what everything sorts by and what retention
    -- is measured from, because a client that reports 1970 must not become
    -- undeletable and a client that reports 2099 must not sort to the top of
    -- somebody's review screen.
    reported_at  timestamptz,
    recorded_at  timestamptz NOT NULL DEFAULT now(),

    -- Whatever the signal carries: the pasted length, the address family, how
    -- long focus was away. NEVER the pasted TEXT -- see the check below.
    detail     jsonb,

    CONSTRAINT ck_attempt_event_kind CHECK (kind IN (
        'FOCUS_LOST', 'FOCUS_REGAINED', 'TAB_HIDDEN', 'TAB_VISIBLE', 'PASTE',
        'ADDRESS_CHANGED'))
);

-- The read a reviewer makes: this attempt's events, in the order they happened.
-- By the sequence rather than the clock, for the reason above.
CREATE INDEX ix_attempt_event_attempt ON attempt_event (attempt_id, seq);

-- What the reaper claims. Tenant-wide and time-ordered, because retention is a
-- sweep across every company rather than a question about one attempt.
CREATE INDEX ix_attempt_event_recorded ON attempt_event (recorded_at);

COMMENT ON TABLE attempt_event IS
    'Integrity signals (T-6.8). BEHAVIOURAL DATA ABOUT A PERSON, self-reported by their own '
    'browser, kept on its own retention clock and deleted while the attempt survives. Nothing in '
    'the grading path reads this table, and an ArchUnit rule keeps it that way.';
