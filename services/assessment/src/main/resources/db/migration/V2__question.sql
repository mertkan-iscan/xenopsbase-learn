-- V2 — a question is an identity; a question version is what was asked (T-6.2).
--
-- ADR-0106 decides this model. What the schema adds to it is where the line
-- falls, because the ADR names two SETS OF COLUMNS and this file makes them two
-- TABLES:
--
--   question          everything that is NOT what was asked -- which bank it is
--                     in, what the author calls it, whether it is retired
--   question_version  everything that WAS asked, and how it was marked
--
-- That split is what lets the immutability trigger below need no column list. A
-- trigger naming "the content and scoring columns" would be a list somebody has
-- to extend every time T-6.3 adds a question type, and the failure of forgetting
-- is silent: an edit reaches a served version, and last quarter's results
-- quietly become claims about a question that no longer exists. Here the rule is
-- the whole row, and a new content column cannot escape it.
--
-- THE PROPERTY, STATED ONCE: after this migration, "what exactly did this person
-- see" is answerable from the database alone, three months later, without an
-- audit log and without a backup.

CREATE TABLE question (
    id           uuid         PRIMARY KEY,
    tenant_id    varchar(64)  NOT NULL,

    -- Bank membership belongs to the QUESTION, not to a version: ADR-0106 says
    -- moving a question between banks is not an edit to what was asked. So a
    -- move leaves every recorded attempt rendering exactly as it did.
    bank_id      uuid         NOT NULL REFERENCES question_bank (id),

    -- The version an author is editing and a draw would pick up. Nullable for
    -- the width of one transaction only -- a question is inserted, then its
    -- first version, then this is pointed at it -- because the two tables
    -- reference each other and something has to be written first.
    current_version_id uuid,

    -- WHAT THE AUTHOR CALLS IT, WHICH IS NOT WHAT THE LEARNER READS.
    --
    -- ADR-0106 lists the internal name among the columns that are not versioned,
    -- and this is the table that makes that true. It earns its place twice: with
    -- the stem living inside the version body, an authoring list has nothing else
    -- to show, and a name that changed with every edit would make that list
    -- re-sort itself for a typo fix.
    internal_name varchar(256) NOT NULL,

    -- "DELETE" IN THE AUTHORING UI, FOR ANY QUESTION THAT HAS EVER BEEN SERVED.
    --
    -- It disappears from authoring and from every future draw; every existing
    -- attempt still renders and every report stays correct. A question whose
    -- versions have never been served is deleted outright instead -- see
    -- QuestionService -- because by construction nothing references it.
    retired_at   timestamptz,

    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL
);

CREATE TABLE question_version (
    id           uuid         PRIMARY KEY,
    tenant_id    varchar(64)  NOT NULL,
    question_id  uuid         NOT NULL REFERENCES question (id),

    -- Monotonic per question, the shape catalog's course_version already uses.
    -- The id is what an attempt points at; this is what a person says out loud.
    version      int          NOT NULL,

    -- WHAT WAS ASKED AND HOW IT WAS MARKED, AS ONE DOCUMENT.
    --
    -- Opaque to this migration on purpose. T-6.3 decides the question types and
    -- the shape of each one's options, answer key and weights; T-6.4 decides
    -- scoring modes. A column per type-specific field now would be inventing both
    -- of those decisions from the outside, and every one of those columns would
    -- then have to be added to a trigger's list -- which is the list this file
    -- exists in order not to have.
    --
    -- The stem is IN here rather than beside it, for the reason ADR-0106 gives
    -- for an attempt holding only the version id: two places that say the same
    -- thing invite the day they disagree. An authoring list reads
    -- question.internal_name instead.
    body         jsonb        NOT NULL,

    -- WHEN THIS VERSION FIRST WENT IN FRONT OF A LEARNER, AND THE WHOLE HINGE OF
    -- ADR-0106.
    --
    -- NULL means draft: editing updates this row, because nobody has seen it and
    -- a new version would be archaeology of a question that never reached anyone.
    -- Non-null means history: editing creates a new version and this row is
    -- frozen by the trigger below.
    --
    -- Set by delivery, not by authoring -- T-6.5 draws and T-6.6 serves. Nothing
    -- sets it yet except ServedVersions, which exists so the rule can be
    -- exercised now and so delivery has one statement to call rather than an
    -- invariant to reimplement.
    first_served_at timestamptz,

    created_at   timestamptz  NOT NULL,

    CONSTRAINT uq_question_version UNIQUE (question_id, version)
);

ALTER TABLE question
    ADD CONSTRAINT fk_question_current_version
    FOREIGN KEY (current_version_id) REFERENCES question_version (id);

-- What an authoring list asks: this bank's questions, retired ones excluded.
CREATE INDEX ix_question_bank ON question (tenant_id, bank_id, lower(internal_name))
    WHERE retired_at IS NULL;

-- What the version history screen asks, and what T-6.5's draw will join through.
CREATE INDEX ix_question_version_question ON question_version (question_id, version DESC);


-- ---------------------------------------------------------------------------
-- IMMUTABILITY, IN THE DATABASE, BECAUSE THE REPOSITORY LAYER IS NOT THE ONLY
-- THING THAT EVER WRITES HERE
-- ---------------------------------------------------------------------------
--
-- ADR-0106 asks for this specifically and names why: a support fix applied in
-- SQL, a migration, a future service in another language. An invariant that
-- decides whether a disputed certification is defensible is worth stating where
-- every writer meets it, not in the one client that happens to be Java today.
--
-- TWO OPERATIONS ARE REFUSED, and the second is the one easy to leave out:
--
--   UPDATE of a served version -- the edit this exists to stop.
--   DELETE of a served version -- the same damage by another verb. ADR-0106
--     makes the attempt's foreign key ON DELETE RESTRICT rather than CASCADE for
--     exactly this reason; that key arrives with T-6.6, and until it does this
--     trigger is the only thing standing in front of a DELETE FROM
--     question_version.
--
-- THE ONE UPDATE THAT IS ALLOWED is the one that makes a row history in the first
-- place: setting first_served_at while it is still NULL. It is allowed because
-- OLD.first_served_at is NULL at that moment, and it cannot happen twice --
-- afterwards every UPDATE to the row is refused, including one that would move
-- the timestamp or put it back to NULL.
CREATE FUNCTION question_version_is_history() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'question_version % has been served and cannot be deleted (ADR-0106): it is what a learner was asked, and every attempt referencing it renders from this row', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;

    RAISE EXCEPTION 'question_version % has been served and cannot be edited (ADR-0106): editing a served version rewrites what a learner was asked. Create a new version instead', OLD.id
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- WHEN clauses rather than an IF inside the function, so the rule is visible in
-- \d output and the trigger does not fire at all on the ordinary case.
--
-- The UPDATE trigger is deliberately not conditioned on which columns changed: a
-- served row is history in full, so UPDATE ... SET body = body is refused too.
-- That is the intent -- a no-op write to a frozen row is a writer that believes
-- it may write, and the next thing it writes will not be a no-op.
CREATE TRIGGER question_version_no_edit_after_serving
    BEFORE UPDATE ON question_version
    FOR EACH ROW WHEN (OLD.first_served_at IS NOT NULL)
    EXECUTE FUNCTION question_version_is_history();

CREATE TRIGGER question_version_no_delete_after_serving
    BEFORE DELETE ON question_version
    FOR EACH ROW WHEN (OLD.first_served_at IS NOT NULL)
    EXECUTE FUNCTION question_version_is_history();


-- ---------------------------------------------------------------------------
-- DELIBERATELY NOT HERE
-- ---------------------------------------------------------------------------
--
-- TAGS AND DIFFICULTY. T-6.1 built bank_tag and bank_difficulty for questions to
-- reference, and ADR-0106 puts both on the non-versioned side, so they belong on
-- `question` when they arrive. They are absent because the thing that gives them
-- a shape is the draw (T-6.5): "five medium questions tagged fire-safety" is what
-- decides whether difficulty is a column or a range, and whether tags are a join
-- table or an array validated against the vocabulary. Choosing now would be
-- choosing for T-6.5 from the outside, and the vocabularies dangling for one
-- issue is the cheaper of the two mistakes.
--
-- attempt_response.question_version_id. T-6.6's, and the reason this table's key
-- is an id rather than (question_id, version).
