-- V6 — publishing a course produces something that cannot change afterwards (T-5.7).
--
-- THE FAILURE THIS PREVENTS. Without a snapshot, republishing a course silently
-- changes what "completed" meant for everyone who already finished it. Last
-- quarter's compliance report becomes a statement about a course that no longer
-- exists, and nobody notices until an auditor asks what was actually covered.
--
-- T-5.5 pinned course.structure_version, which recorded WHICH version an
-- assignment was made against and could not produce it. That was honest about
-- its limit and this is the limit being removed: the version is now a document.

CREATE TABLE course_version (
    id           uuid         PRIMARY KEY,
    tenant_id    varchar(64)  NOT NULL,
    course_id    uuid         NOT NULL REFERENCES course (id) ON DELETE CASCADE,

    -- Monotonic per course, and what an assignment pins.
    version      bigint       NOT NULL,

    -- THE WHOLE STRUCTURE, AS IT WAS: modules, nodes, required flags, gates.
    --
    -- A document rather than copied rows in versioned tables. A snapshot is read
    -- whole and never queried across, so a table per versioned thing would buy
    -- joins nobody makes at the cost of doubling every structure table and every
    -- foreign key into it. It also makes the diff an ordinary comparison of two
    -- values rather than a query per level of the tree.
    --
    -- It deliberately holds content item IDs, not their titles: an item's title
    -- is the item's own, and copying it here would be the drift ADR-0109 warns
    -- about, with the added twist that nobody could ever correct it.
    snapshot     jsonb        NOT NULL,

    -- TRUE when nothing but wording changed since the previous version.
    --
    -- This is T-5.7's "trivial edit path for typos", and the shape of the answer
    -- is worth stating because the obvious alternative is worse. Editing a
    -- PUBLISHED version in place would make "what did this learner actually see"
    -- unanswerable, which is the exact failure at the top of this file. So a typo
    -- still produces a row -- but a row that costs nobody anything: assignments
    -- pinned to the previous version follow a text-only chain automatically, no
    -- learner is disturbed and no report is invalidated.
    --
    -- THE LIMIT, STATED: text only means titles and descriptions. Any difference
    -- in what a learner must DO -- a node added, removed, reordered, made
    -- required or optional, or any gate change -- is a real version, and the
    -- server decides which it is by comparing the snapshots rather than trusting
    -- the caller to say.
    text_only    boolean      NOT NULL DEFAULT false,

    -- What the author says changed. Not a substitute for the diff.
    notes        varchar(1000),

    published_at timestamptz  NOT NULL,
    published_by uuid         NOT NULL,

    CONSTRAINT uq_course_version UNIQUE (course_id, version)
);

CREATE INDEX ix_course_version_course ON course_version (course_id, version DESC);

-- IMMUTABILITY, ENFORCED BY THE DATABASE RATHER THAN BY EVERYONE REMEMBERING.
--
-- The application has no code that updates a published version, and that is not
-- the same as it being impossible. A trigger is: the next person to add an
-- "just fix it in place" path gets an error rather than a silently rewritten
-- history, and so does a hand-run UPDATE in a console at midnight.
--
-- DELETE is allowed only by the cascade from course, because a course removed
-- entirely takes its versions with it -- there is nothing left to be honest
-- about. A direct DELETE is refused for the same reason an UPDATE is.
CREATE OR REPLACE FUNCTION refuse_course_version_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'course_version is immutable (T-5.7): a published version is what somebody was assigned '
        'and what a report describes. Publish a new version instead.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER course_version_is_immutable
    BEFORE UPDATE ON course_version
    FOR EACH ROW EXECUTE FUNCTION refuse_course_version_change();

-- WHICH VERSION A LEARNER FINISHED UNDER (T-5.7's fourth criterion).
--
-- Nullable, because completion recorded before a course was ever versioned has
-- no honest answer and inventing one would be worse than admitting it. Reports
-- show the version where there is one and say so where there is not.
ALTER TABLE node_completion ADD COLUMN course_version_id uuid REFERENCES course_version (id);

CREATE INDEX ix_node_completion_version ON node_completion (course_version_id)
    WHERE course_version_id IS NOT NULL;
