-- V4 — what a test asks for, and what one learner was actually given (T-6.5).
--
-- Two halves that only make sense together.
--
--   test_section  the INSTRUCTION: "five medium questions tagged fire-safety,
--                 shuffled". It is a query, and it is answered afresh per
--                 learner.
--   test_form     the RECORD: exactly which question versions that produced, in
--                 the order they were shown, with the option order they were
--                 shown in.
--
-- RANDOM ASSEMBLY WITHOUT A RECORDED FORM IS UNREPORTABLE AND UNDEFENDABLE. You
-- cannot answer what a learner saw, you cannot compute item statistics, and you
-- cannot rescore. The form is what turns randomisation from a liability into a
-- feature -- and it is why the two halves land in one migration rather than the
-- instruction first and the record when somebody needs it.

-- ---------------------------------------------------------------------------
-- FIRST: THE COLUMNS A DRAW FILTERS ON, WHICH V2 LEFT FOR THIS ISSUE
-- ---------------------------------------------------------------------------
--
-- V2 says so in as many words: tags and difficulty belong on `question`, and
-- their SHAPE is decided by the draw, so choosing then would have been choosing
-- for this issue from the outside. Here is the draw, so here is the shape.
--
-- DIFFICULTY IS A COLUMN, TAGS ARE A JOIN TABLE, and neither is arbitrary.
--
-- Difficulty is single-valued and ordered: a question is medium, and a section
-- says "medium or harder". The order lives in bank_difficulty.rank (T-6.1), so
-- what a question needs is one reference to it.
--
-- Tags are many and are the thing the vocabulary was built to police. V1 states
-- the failure exactly: `fire-safety` and `fire safety` are two populations, and
-- an author who mistypes one gets a shorter exam rather than an error. A
-- `text[]` column cannot have a foreign key, so nothing would catch the typo --
-- which would make bank_tag a list nobody had to use. The join table is what
-- turns "the vocabulary is checked at authoring time" from a convention into a
-- constraint.

ALTER TABLE question
    -- Nullable: a bank whose company has not defined a difficulty vocabulary is
    -- an ordinary bank, and a draw that does not filter on difficulty does not
    -- care. A section that DOES filter simply will not find these -- which is
    -- the loud outcome, because an insufficient pool is refused.
    ADD COLUMN difficulty_id uuid REFERENCES bank_difficulty (id);

CREATE TABLE question_tag (
    question_id uuid NOT NULL REFERENCES question (id) ON DELETE CASCADE,
    -- NO ACTION, not CASCADE. Removing a tag from the vocabulary while questions
    -- carry it would silently change what every section drawing on it selects,
    -- which V1's "no remove" paragraph is already guarding against. This is the
    -- referential half of that promise.
    tag_id      uuid NOT NULL REFERENCES bank_tag (id),
    tenant_id   varchar(64) NOT NULL,
    PRIMARY KEY (question_id, tag_id)
);

-- The draw's direction of travel: from a tag to the questions carrying it.
CREATE INDEX ix_question_tag_tag ON question_tag (tenant_id, tag_id, question_id);

-- "Which questions are medium or harder" -- the other half of the same draw.
CREATE INDEX ix_question_difficulty ON question (tenant_id, difficulty_id)
    WHERE retired_at IS NULL;


-- ---------------------------------------------------------------------------
-- THE INSTRUCTION
-- ---------------------------------------------------------------------------

CREATE TABLE test_section (
    id        uuid         PRIMARY KEY,
    tenant_id varchar(64)  NOT NULL,
    test_id   uuid         NOT NULL REFERENCES test (id) ON DELETE CASCADE,

    title     varchar(512) NOT NULL,

    -- The rational-midpoint scheme catalog's course_module uses, and for the
    -- same reason: moving a section is a single-row UPDATE whatever the test's
    -- size, and two authors reordering different parts do not collide. The full
    -- reasoning, including the cost, is in catalog's V2__course_structure.sql.
    ordinal   numeric      NOT NULL,

    -- What this section counts for against the others (T-6.4). Zero is
    -- legitimate and means scored and shown but not counted.
    weight    smallint     NOT NULL DEFAULT 1,

    -- FIXED or POOL.
    --
    --   FIXED  the author named the questions; test_section_question holds them
    --          in order.
    --   POOL   the author described a population; draw_count of them are chosen
    --          per learner.
    --
    -- One column rather than two tables, because everything after selection --
    -- weight, shuffling, scoring, the form -- is identical, and two tables would
    -- duplicate all of it to express one branch.
    selection varchar(8)   NOT NULL,

    -- POOL only. How many to draw.
    draw_count smallint,

    -- POOL only, all optional. The population, narrowed.
    --
    -- Difficulty as a RANK RANGE rather than a set of ids, because that is what
    -- "medium or harder" means and because a range survives a company adding a
    -- level in the middle of its scale. A set of ids would silently stop
    -- including the new level, and nobody would be told.
    bank_id             uuid REFERENCES question_bank (id),
    min_difficulty_rank smallint,
    max_difficulty_rank smallint,

    -- Per-section scoring, overriding the test's defaults (T-6.4). Null means
    -- "whatever the test says", which is the common case and is not the same as
    -- a value that happens to match today.
    points    numeric(9, 4),
    mode      varchar(24),

    -- Whether the questions are presented in a different order per learner, and
    -- whether the options within them are. Two flags, because they are two
    -- decisions: a fixed section that builds an argument question by question
    -- must not be reordered, and its multiple-choice options still should be.
    shuffle_questions boolean NOT NULL DEFAULT false,
    shuffle_options   boolean NOT NULL DEFAULT false,

    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT ck_test_section_selection CHECK (selection IN ('FIXED', 'POOL')),
    CONSTRAINT ck_test_section_weight CHECK (weight >= 0),
    CONSTRAINT ck_test_section_points CHECK (points IS NULL OR points > 0),
    CONSTRAINT ck_test_section_mode
        CHECK (mode IS NULL OR mode IN ('ALL_OR_NOTHING', 'PARTIAL_CREDIT')),

    -- A pool section draws a positive number; a fixed one draws nothing. Stated
    -- as a constraint rather than left to the service, because a POOL row with a
    -- null count is a section that serves an empty form -- which is the exact
    -- failure this issue names as the worst outcome.
    CONSTRAINT ck_test_section_draw_count CHECK (
        (selection = 'POOL' AND draw_count IS NOT NULL AND draw_count > 0)
        OR (selection = 'FIXED' AND draw_count IS NULL)),

    CONSTRAINT ck_test_section_difficulty_range CHECK (
        min_difficulty_rank IS NULL OR max_difficulty_rank IS NULL
        OR min_difficulty_rank <= max_difficulty_rank)
);

CREATE INDEX ix_test_section_order ON test_section (test_id, ordinal, id);

-- FIXED sections: the questions, in the author's order.
CREATE TABLE test_section_question (
    section_id  uuid NOT NULL REFERENCES test_section (id) ON DELETE CASCADE,
    -- The QUESTION, not a version. Which version a learner gets is decided when
    -- the form is assembled, so a test built in March serves the current wording
    -- in June -- and the form records which one that was, which is the whole
    -- point of ADR-0106 having an identity and a version.
    question_id uuid NOT NULL REFERENCES question (id),
    tenant_id   varchar(64) NOT NULL,
    ordinal     numeric NOT NULL,
    PRIMARY KEY (section_id, question_id)
);

CREATE INDEX ix_test_section_question_order
    ON test_section_question (section_id, ordinal, question_id);

-- POOL sections: the tags a drawn question must carry.
--
-- ALL OF THEM, not any. Two tags means questions that are both, which is the
-- narrower reading and the predictable one: it can only ever make a pool
-- smaller, and an insufficient pool is refused loudly. "Any of these" widens
-- silently, and it is expressible as two sections.
CREATE TABLE test_section_tag (
    section_id uuid NOT NULL REFERENCES test_section (id) ON DELETE CASCADE,
    tag_id     uuid NOT NULL REFERENCES bank_tag (id),
    tenant_id  varchar(64) NOT NULL,
    PRIMARY KEY (section_id, tag_id)
);


-- ---------------------------------------------------------------------------
-- THE RECORD
-- ---------------------------------------------------------------------------

CREATE TABLE test_form (
    id        uuid        PRIMARY KEY,
    tenant_id varchar(64) NOT NULL,
    test_id   uuid        NOT NULL REFERENCES test (id),

    -- THE ATTEMPT, WITH NO FOREIGN KEY YET. The attempt table is T-6.6's (#65)
    -- and this table has to exist before it, because assembling the form IS what
    -- starting an attempt does. The key lands with that migration.
    --
    -- UNIQUE, which is half of "reassembly is impossible": there is one form per
    -- attempt and a second insert is refused by the database rather than by
    -- whichever service remembered to look.
    attempt_id uuid       NOT NULL,

    -- The seed the shuffle used. Recorded so a form can be re-derived and
    -- checked, and NOT so it can be regenerated -- the items below are the
    -- record, and they are frozen. It is here for the support question "is this
    -- really what the assembler produces", which is otherwise unanswerable.
    seed       bigint     NOT NULL,

    created_at timestamptz NOT NULL,

    CONSTRAINT uq_test_form_attempt UNIQUE (attempt_id)
);

CREATE INDEX ix_test_form_test ON test_form (tenant_id, test_id, created_at DESC);

CREATE TABLE test_form_item (
    id         uuid        PRIMARY KEY,
    tenant_id  varchar(64) NOT NULL,
    form_id    uuid        NOT NULL REFERENCES test_form (id) ON DELETE CASCADE,

    -- Which section it came from, so a per-section score (T-6.4) can be composed
    -- from the form alone. Not ON DELETE CASCADE: deleting a section must not
    -- delete the record of what somebody was asked.
    section_id uuid        NOT NULL REFERENCES test_section (id) ON DELETE NO ACTION,

    -- THE SERVED ORDER, across the whole form. Not per section, because that is
    -- the order the learner met them in and it is what a review screen renders.
    position   smallint    NOT NULL,

    -- THE VERSION, WHICH IS THE POINT OF THE WHOLE TABLE (ADR-0106). Not the
    -- question: three months later "what exactly did this person see" has to be
    -- answerable from this row, and a question id answers "what do we ask now".
    --
    -- RESTRICT rather than CASCADE, which ADR-0106 asks for by name: a served
    -- version is history and the trigger in V2 already refuses to delete one.
    -- This is the second lock on the same door.
    question_version_id uuid NOT NULL REFERENCES question_version (id) ON DELETE RESTRICT,

    -- THE OPTION ORDER, AS SERVED. A map from the option list's field name to the
    -- ids in the order they were shown: {"choices":["c","a","b"]}. Null when this
    -- type has nothing shufflable or the section did not ask for it.
    --
    -- Why this and not a boolean plus the seed: a review screen has to render
    -- exactly what was seen, and re-deriving it from a seed makes that depend on
    -- the shuffle algorithm never changing, in any language, forever. The order
    -- is three strings; the coupling is not worth saving them.
    option_order jsonb,

    -- The scoring in force for this item when it was served (T-6.4), copied
    -- rather than looked up. An author raising a question's weight next month
    -- must not silently rescore an exam somebody already sat -- rescoring is a
    -- deliberate, audited act, and it cannot be one if the inputs move on their
    -- own.
    points     numeric(9, 4) NOT NULL,
    mode       varchar(24)   NOT NULL,

    CONSTRAINT uq_test_form_item_position UNIQUE (form_id, position),
    CONSTRAINT ck_test_form_item_points CHECK (points > 0),
    CONSTRAINT ck_test_form_item_mode CHECK (mode IN ('ALL_OR_NOTHING', 'PARTIAL_CREDIT'))
);

CREATE INDEX ix_test_form_item_order ON test_form_item (form_id, position);


-- ---------------------------------------------------------------------------
-- REASSEMBLY IS IMPOSSIBLE
-- ---------------------------------------------------------------------------
--
-- The criterion says "an attempt's form never changes after it is written", and
-- it is enforced the way V2 enforces a served version: in the database, because
-- the repository layer is not the only thing that ever writes here.
--
-- The unique key on attempt_id already refuses a SECOND form. This refuses
-- editing the first -- and refuses deleting it, which is the same damage by
-- another verb and the half that is easy to leave out.
--
-- WHAT A FORM BEING MUTABLE WOULD MEAN, stated plainly, because the rule looks
-- like paranoia until you write it down: a learner sits a test, the form is
-- edited, and the report now describes an exam that was never taken. There is no
-- log that recovers from that, and a disputed certification is exactly the
-- circumstance in which somebody looks.
CREATE FUNCTION test_form_is_the_record() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a test form is the record of what one learner was asked and cannot be deleted (T-6.5): every response and every report reads it'
            USING ERRCODE = 'restrict_violation';
    END IF;

    RAISE EXCEPTION 'a test form is the record of what one learner was asked and cannot be edited (T-6.5): reassembling it would make the report describe an exam nobody sat'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER test_form_no_edit
    BEFORE UPDATE OR DELETE ON test_form
    FOR EACH ROW EXECUTE FUNCTION test_form_is_the_record();

CREATE TRIGGER test_form_item_no_edit
    BEFORE UPDATE OR DELETE ON test_form_item
    FOR EACH ROW EXECUTE FUNCTION test_form_is_the_record();
