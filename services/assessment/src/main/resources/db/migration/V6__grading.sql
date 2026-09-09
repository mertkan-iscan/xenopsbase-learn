-- V6 — marking, and the state between passed and failed (T-6.7).
--
-- THE STATE THAT MUST EXIST, in the issue's words: "an attempt containing one
-- essay is neither passed nor failed until a human looks at it. That third state
-- has to be modelled explicitly -- AWAITING_GRADING -- or a gate reads a null
-- score, treats it as a fail, and locks a learner out of a course they may well
-- have passed."
--
-- IT IS A SECOND COLUMN AND NOT A FIFTH VALUE OF `state`, and that is the one
-- modelling decision in this migration worth arguing about.
--
-- `state` says HOW AN ATTEMPT ENDED: submitted in time, or the clock ran out, or
-- nobody came back. T-6.6 kept EXPIRED and ABANDONED apart precisely because a
-- report that merged them could not tell "everybody runs out of time on question
-- forty" from "half of them never finish".
--
-- `grading` says WHERE ITS MARKING IS. An attempt that expired AND is waiting on
-- an essay is both of those things, and a single enum would have to lose one.
-- Merging the axes reads as tidier right up to the first report that needs both.
--
-- The value the issue names lives on the axis it belongs to.

ALTER TABLE attempt
    -- NOT_GRADED (never submitted, or abandoned), AWAITING_GRADING (a person has
    -- to look), GRADED (settled).
    --
    -- Default NOT_GRADED so every attempt already in flight when this migration
    -- runs says the true thing about itself: nobody has marked it.
    ADD COLUMN grading varchar(16) NOT NULL DEFAULT 'NOT_GRADED',

    -- BOTH NUMBERS, WHICH IS T-6.4'S CRITERION FINALLY HAVING SOMEWHERE TO LAND.
    -- score_raw explains a result -- "34 of 40" is what a learner asks about and
    -- what a rescore is checked against. score_scaled is what every rule
    -- downstream reads, because a pass rule written against raw points breaks
    -- silently the moment a section changes length.
    ADD COLUMN score_raw    numeric(12, 4),
    ADD COLUMN score_scaled numeric(11, 10),

    -- The scaled score floored to a whole percent. Stored rather than derived,
    -- so the number a learner was SHOWN is the number a dispute is argued from,
    -- even if the flooring rule is ever revisited. It is also the number the
    -- pass mark was compared with -- see TestScore for why those are one number
    -- used twice rather than two roundings that agree.
    ADD COLUMN score_percent smallint,

    -- NULLABLE, AND THE NULL IS THE POINT. Null does not mean failed; it means
    -- nobody has said. A gate that reads this as false is the bug this whole
    -- migration exists to prevent, which is why `grading` is beside it and why
    -- nothing downstream may read one without the other.
    ADD COLUMN passed boolean,

    ADD COLUMN graded_at timestamptz,

    ADD CONSTRAINT ck_attempt_grading
        CHECK (grading IN ('NOT_GRADED', 'AWAITING_GRADING', 'GRADED')),

    -- A settled attempt has a verdict; an unsettled one does not have one yet.
    -- Stated as a constraint because "graded but nobody wrote a score" is the
    -- state a gate would read as a fail, and it must not be reachable at all.
    ADD CONSTRAINT ck_attempt_graded_has_a_verdict CHECK (
        (grading = 'GRADED' AND passed IS NOT NULL AND score_scaled IS NOT NULL)
        OR (grading <> 'GRADED' AND passed IS NULL));

-- "What is waiting to be marked, and how long has it waited" -- the queue's own
-- read. Partial, because waiting attempts are a handful against every attempt
-- ever sat.
CREATE INDEX ix_attempt_awaiting ON attempt (tenant_id, submitted_at)
    WHERE grading = 'AWAITING_GRADING';


-- ---------------------------------------------------------------------------
-- PER-ANSWER MARKS
-- ---------------------------------------------------------------------------

ALTER TABLE attempt_response
    -- T-6.3's Correctness, stored as the two integers it is. "3 of 5 pairs
    -- matched" survives being reported, audited and argued about, where 0.6 has
    -- already lost what an analyst asks for next (T-7.7).
    ADD COLUMN credited  smallint,
    ADD COLUMN available smallint,

    -- What it earned, after the scoring mode and any penalty (T-6.4). May be
    -- negative under negative marking; the floor is applied to the section, not
    -- here, so a result screen can still show what a guess cost.
    ADD COLUMN awarded numeric(9, 4),

    -- WHO MARKED IT. Null means the machine did, and that is not the same as
    -- "unmarked" -- `graded_at` says which. Every export has to be able to say
    -- whether a person or a comparison produced a mark.
    ADD COLUMN graded_by uuid,
    ADD COLUMN graded_at timestamptz,

    -- What the grader wants the learner to read. Only ever set by a person: a
    -- machine has nothing to say that the score does not already say.
    ADD COLUMN grader_comment text;


-- ---------------------------------------------------------------------------
-- RUBRICS
-- ---------------------------------------------------------------------------
--
-- ON THE QUESTION, NOT ON THE VERSION, and ADR-0106 is the reason rather than
-- an exception to it. A rubric is marking guidance: an examiner improving how
-- they mark has not changed what anybody was asked, so it belongs with the
-- internal name and the difficulty on the non-versioned side.
--
-- What protects the record is the mark rows below, not the rubric: they say what
-- was awarded against which criterion at the time, so a rubric edited next term
-- cannot rewrite last term's marks.

CREATE TABLE rubric_criterion (
    id          uuid         PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    question_id uuid         NOT NULL REFERENCES question (id) ON DELETE CASCADE,

    name        varchar(256) NOT NULL,

    -- The most this criterion can earn. The criteria of a question do not have
    -- to sum to what the question is worth: a rubric is a way to think, and the
    -- item's points come from the form (T-6.5). A grader's total is scaled onto
    -- the item's points, which is stated in GradingService rather than assumed.
    max_points  numeric(9, 4) NOT NULL,

    ordinal     smallint     NOT NULL,
    created_at  timestamptz  NOT NULL,

    CONSTRAINT ck_rubric_criterion_points CHECK (max_points > 0)
);

CREATE INDEX ix_rubric_criterion_question
    ON rubric_criterion (tenant_id, question_id, ordinal);

CREATE TABLE response_criterion_mark (
    response_id  uuid NOT NULL REFERENCES attempt_response (id) ON DELETE CASCADE,

    -- NO ACTION: a criterion somebody has already marked against cannot be
    -- deleted out from under the mark. Editing a rubric must not silently change
    -- what an examiner recorded.
    criterion_id uuid NOT NULL REFERENCES rubric_criterion (id),

    tenant_id    varchar(64)   NOT NULL,
    awarded      numeric(9, 4) NOT NULL,

    PRIMARY KEY (response_id, criterion_id),
    CONSTRAINT ck_response_criterion_mark CHECK (awarded >= 0)
);


-- ---------------------------------------------------------------------------
-- THE AUDIT, AND WHY A REGRADE KEEPS THE PREVIOUS MARK
-- ---------------------------------------------------------------------------
--
-- The criterion asks for grading to be "audited with the grader, and a regrade
-- keeps the previous mark". Both fall out of one decision: this table is
-- APPEND-ONLY, and the columns on `attempt` are a cache of its latest row.
--
-- A regrade does not overwrite anything here. It adds a row, and the previous
-- verdict stays legible with the person and the moment attached to it -- which
-- is the only form in which "we changed this learner's result" is defensible
-- three months later.
CREATE TABLE grade_event (
    id         uuid        PRIMARY KEY,

    -- THE ORDER, AND IT IS NOT THE TIMESTAMP. Two verdicts reached in the same
    -- microsecond -- a mark and the recompute it triggers, or two graders
    -- working an appeal together -- tie on created_at, and "newest first" then
    -- resolves to whichever uuid sorted higher. That shows a superseded verdict
    -- as the current one, which is the single worst thing an audit can do.
    --
    -- A sequence is a total order and a clock is not. Found by a test that
    -- marked and regraded against a stopped clock, which is the same instant a
    -- fast machine produces on its own.
    seq        bigserial   NOT NULL,
    tenant_id  varchar(64) NOT NULL,
    -- NO ACTION rather than CASCADE, which reads as an oversight and is not: the
    -- trigger below refuses a DELETE from this table, so a cascade would fail
    -- anyway -- and it should. An attempt with a grading history is not a row
    -- anybody deletes.
    attempt_id uuid        NOT NULL REFERENCES attempt (id),

    -- Null means the machine graded it (on submit, or on a recompute after a
    -- person marked the last outstanding answer). app_user.id otherwise.
    graded_by  uuid,

    -- The verdict this event produced. Copied rather than joined, because the
    -- point of an audit row is to be readable without the rest of the schema
    -- agreeing with it.
    grading       varchar(16)   NOT NULL,
    score_raw     numeric(12, 4),
    score_scaled  numeric(11, 10),
    score_percent smallint,
    passed        boolean,

    -- Why, when a person did it. A regrade with no reason is a regrade nobody
    -- can defend, and the field being here is the prompt to write one.
    note       text,

    created_at timestamptz NOT NULL DEFAULT now()
);

-- An attempt's grading history, newest first -- what a dispute is read from.
-- Ordered by the sequence rather than the clock, for the reason above.
CREATE INDEX ix_grade_event_attempt ON grade_event (attempt_id, seq DESC);

-- APPEND-ONLY, in the database, for V2's reason: the repository layer is not the
-- only thing that ever writes here. An audit somebody can edit is not an audit.
CREATE FUNCTION grade_event_is_history() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'a grade event is the record of a verdict and cannot be changed (T-6.7): a regrade adds a row, so the previous mark stays legible with the person who made it'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER grade_event_no_edit
    BEFORE UPDATE OR DELETE ON grade_event
    FOR EACH ROW EXECUTE FUNCTION grade_event_is_history();
