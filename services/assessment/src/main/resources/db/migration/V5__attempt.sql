-- V5 — sitting a test, with the clock on the server (T-6.6).
--
-- WHERE THE NAIVE VERSION LEAKS, in the issue's own words: "a client-side
-- countdown is a courtesy. If the server does not compute expires_at at start
-- and enforce it at submit, the time limit is advisory and everyone will
-- eventually find out."
--
-- So expires_at is a stored column, written once, and there is no endpoint that
-- moves it. Not a duration the client counts down from; not a value recomputed
-- per request from something a client sends.

-- ---------------------------------------------------------------------------
-- FIRST: THE POLICY, WHICH BELONGS TO THE TEST
-- ---------------------------------------------------------------------------

ALTER TABLE test
    -- How many times one learner may sit it. NULL is unlimited, which is the
    -- right default for a practice quiz and the wrong one for a certification
    -- exam -- so it is a value an author sets rather than a number this schema
    -- guesses at.
    ADD COLUMN attempts_allowed smallint,

    -- The time limit, in seconds. NULL means untimed, and an untimed attempt
    -- gets no expires_at at all rather than a very distant one: "no deadline"
    -- and "a deadline in the year 3000" are different facts, and only the first
    -- one is true.
    ADD COLUMN time_limit_seconds integer,

    ADD CONSTRAINT ck_test_attempts_allowed
        CHECK (attempts_allowed IS NULL OR attempts_allowed > 0),
    ADD CONSTRAINT ck_test_time_limit
        CHECK (time_limit_seconds IS NULL OR time_limit_seconds > 0);


-- ---------------------------------------------------------------------------
-- THE ATTEMPT
-- ---------------------------------------------------------------------------

CREATE TABLE attempt (
    id         uuid        PRIMARY KEY,
    tenant_id  varchar(64) NOT NULL,
    test_id    uuid        NOT NULL REFERENCES test (id),

    -- app_user.id (ADR-0104), never an IdP subject. A sub is a link identity may
    -- repair (T-1.7); an exam result that quietly stops pointing at anybody is
    -- worse than none.
    learner_id uuid        NOT NULL,

    -- 1-based, per learner per test. It exists so "your second attempt" is a
    -- fact and not a row count that changes when something is reaped -- and,
    -- with the unique key below, it is what makes two tabs unable to both begin
    -- the last attempt.
    attempt_number smallint NOT NULL,

    -- IN_PROGRESS, SUBMITTED, EXPIRED or ABANDONED.
    --
    -- EXPIRED IS NOT "NOT GRADED". It means the clock ran out, and the attempt
    -- is still marked: answers were saved as they were given and the save path
    -- refuses anything after the deadline, so by construction nothing in it was
    -- written late. Throwing the work away because the SUBMIT request was late
    -- would punish a slow network for a rule about time to think.
    --
    -- ABANDONED is for an untimed attempt nobody came back to. Without it, an
    -- untimed attempt stays open for ever, which is the state this issue asks to
    -- be reached "on a schedule rather than staying open".
    state      varchar(16) NOT NULL,

    started_at timestamptz NOT NULL,

    -- THE COLUMN THE WHOLE TASK IS ABOUT. Computed once, at start, from the
    -- test's limit and the server's clock. NULL when the test is untimed.
    --
    -- NEVER EXTENDED BY THE CLIENT, and there is deliberately no endpoint that
    -- updates it. That is also why THE CLOCK KEEPS RUNNING ACROSS A RESUME (see
    -- AttemptService): a pausing clock has to move this column, and the only
    -- thing that could tell us when to pause is the browser saying it went away
    -- -- which is exactly the client honesty this design refuses to depend on.
    expires_at timestamptz,

    -- When the learner (or the reaper) ended it. Distinct from state: an attempt
    -- can be EXPIRED and still have been submitted, a minute late.
    submitted_at timestamptz,

    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT ck_attempt_state
        CHECK (state IN ('IN_PROGRESS', 'SUBMITTED', 'EXPIRED', 'ABANDONED')),

    -- TWO TABS CANNOT BOTH BEGIN THE LAST ATTEMPT, and this is the half that
    -- makes it true without a lock: both tabs compute the same next number, both
    -- insert, and one of them loses here. The loser then reads the winner's row
    -- and resumes it, which is what the person in front of both tabs wanted.
    CONSTRAINT uq_attempt_number UNIQUE (learner_id, test_id, attempt_number)
);

-- AND ONLY ONE AT A TIME. Without this, a learner with two attempts remaining
-- could open two tabs and start both -- burning an attempt they never sat and
-- leaving two live clocks running against one person.
--
-- A partial unique index rather than a constraint, because the rule is only
-- about live rows: a learner may have any number of finished attempts.
CREATE UNIQUE INDEX ux_attempt_one_in_progress
    ON attempt (learner_id, test_id) WHERE state = 'IN_PROGRESS';

-- "What am I part-way through" -- the read every start makes.
CREATE INDEX ix_attempt_learner ON attempt (tenant_id, learner_id, test_id, started_at DESC);

-- What the reaper claims: live attempts whose time is up, oldest first. Partial,
-- because it is a sweep over a handful of rows in a table that will mostly hold
-- finished ones.
CREATE INDEX ix_attempt_live ON attempt (expires_at, started_at)
    WHERE state = 'IN_PROGRESS';


-- ---------------------------------------------------------------------------
-- THE ANSWERS
-- ---------------------------------------------------------------------------
--
-- SAVED AS THEY ARE GIVEN, not at submit. The issue says why: "a browser crash
-- forty minutes into a timed exam is a support conversation nobody wins."
--
-- IDEMPOTENT BY THE SHAPE OF THE ROW rather than by an idempotency key, and this
-- is a deliberate departure from the criterion's wording. One answer to one item
-- is one row; saving it again writes the same row. That is stronger than a
-- replayed response, and it is right in the case a key gets wrong: a learner who
-- changes their answer and whose request is then retried must end up with the
-- NEW answer, where a cached response would hand back the old one and hide it.
CREATE TABLE attempt_response (
    id           uuid        PRIMARY KEY,
    tenant_id    varchar(64) NOT NULL,
    attempt_id   uuid        NOT NULL REFERENCES attempt (id) ON DELETE CASCADE,

    -- The FORM ITEM, not the question: an answer belongs to a position in one
    -- learner's form (T-6.5), and that is what a review screen renders it beside.
    form_item_id uuid        NOT NULL REFERENCES test_form_item (id),

    -- The version, again, denormalised from the form item. It is what grading
    -- compares against and what item analysis groups by (T-7.7), and RESTRICT
    -- here is ADR-0106's third lock on the same door.
    question_version_id uuid NOT NULL REFERENCES question_version (id) ON DELETE RESTRICT,

    -- What the learner sent, shaped by the question's type (T-6.3) and validated
    -- against the options they were served before it is written.
    response     jsonb       NOT NULL,

    answered_at  timestamptz NOT NULL,

    -- One answer per item per attempt. The upsert target, and the reason a retry
    -- costs nothing.
    CONSTRAINT uq_attempt_response UNIQUE (attempt_id, form_item_id)
);

CREATE INDEX ix_attempt_response_attempt ON attempt_response (attempt_id, form_item_id);

-- "Every response to this version", which is what item analysis walks (T-7.7).
CREATE INDEX ix_attempt_response_version
    ON attempt_response (tenant_id, question_version_id);


-- ---------------------------------------------------------------------------
-- WHAT IS DELIBERATELY NOT HERE
-- ---------------------------------------------------------------------------
--
--   score_raw, score_scaled and a grading state. T-6.7's (#66): grading happens
--     on the transition this table records, and T-6.4 already computes both
--     numbers. Adding the columns now would be guessing at the shape of
--     AWAITING_GRADING, which is the state T-6.7 exists to model.
--   attempt_event. T-6.8's (#67), and behavioural data about a person with its
--     own retention story -- which is a reason to keep it out of this table
--     rather than a reason to defer it.
