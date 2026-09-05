-- V2 — the tree: a course of modules, a module of ordered nodes (T-5.2).
--
-- THE ORDERING SCHEME IS THE DECISION THIS MIGRATION EXISTS TO MAKE, and it is
-- the one that is annoying to change once courses exist.
--
-- Dense integers (0, 1, 2, ...) are the obvious choice and the wrong one.
-- Inserting at position 3 of a 40-node module rewrites 37 rows, so a reorder is
-- a transaction touching most of the table; two authors reordering different
-- parts of the same module at the same time write overlapping row sets and one
-- of them loses work that was never in conflict.
--
-- What is used here is a RATIONAL MIDPOINT in `numeric`: to place a node
-- between two others, take the average of their ordinals. An insert or a move
-- is then ALWAYS a single-row UPDATE, whatever the module's size, and two
-- authors reordering different parts touch different rows and both succeed.
--
-- numeric, not double precision. A float midpoint stops being a midpoint after
-- about 50 subdivisions at the same point, silently: two nodes end up with the
-- same ordinal because the gap fell below the mantissa, and the order becomes
-- whatever the planner returns. Postgres numeric is exact and unbounded, so the
-- midpoint is always strictly between.
--
-- THE COST, STATED. Repeatedly inserting at the SAME point doubles the
-- denominator each time, so the stored value grows about one digit per
-- insertion there -- a hundred insertions between the same two nodes is a
-- hundred-digit number. It stays correct and stays fast; it just gets ugly.
-- Rebalancing (renumbering a module 1, 2, 3, ...) is the escape hatch and is a
-- deliberate operation, not something that happens under a user.
--
-- CONCURRENCY, STATED. There is deliberately NO unique constraint on
-- (parent, ordinal). Two authors inserting into the same gap at the same
-- instant compute the same midpoint and both commit; the result is two nodes
-- with equal ordinals, adjacent, in an order broken by id. Nobody's write is
-- lost and no row anybody else touched is rewritten. A unique constraint would
-- convert that harmless tie into one author's request failing, which is a worse
-- answer to a question nobody asked.

CREATE TABLE course (
    id          uuid         PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    title       varchar(512) NOT NULL,
    description text,
    -- NO lifecycle column. A course's publishing story is T-5.7's, and it is
    -- not the same story as a content item's: publishing a new VERSION of a
    -- course must not rewrite the history of learners who finished the old one,
    -- which is a versioning design rather than a state column. Adding
    -- DRAFT/PUBLISHED here now would be the thing T-5.7 has to undo.
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

CREATE INDEX ix_course_tenant ON course (tenant_id, updated_at DESC);

CREATE TABLE course_module (
    id         uuid         PRIMARY KEY,
    tenant_id  varchar(64)  NOT NULL,
    -- CASCADE: a module has no meaning outside its course, so deleting the
    -- course deletes them. Contrast course_node.content_item_id below, where
    -- the referenced thing very much has a life of its own.
    course_id  uuid         NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    title      varchar(512) NOT NULL,
    ordinal    numeric      NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL
);

-- The read every course screen makes, in the order it wants them.
CREATE INDEX ix_course_module_order ON course_module (course_id, ordinal, id);

CREATE TABLE course_node (
    id              uuid        PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    module_id       uuid        NOT NULL REFERENCES course_module (id) ON DELETE CASCADE,

    -- A REFERENCE, AND THE REASON THERE IS NO UNIQUE CONSTRAINT ON IT.
    -- The same content item may appear in any number of courses and any number
    -- of times, without being copied: one video used by the onboarding course
    -- and the annual refresher is one row here pointed at twice. Copying it
    -- would give two items that drift, and a learner who watched "it" in one
    -- course getting no credit in the other.
    --
    -- NO ACTION (the default) rather than CASCADE, deliberately: deleting a
    -- content item a course points at is REFUSED by the database. The
    -- alternative deletes nodes out of a course somebody is part-way through,
    -- and the first anyone knows is a learner finding a shorter course than
    -- they started. Withdrawal is ARCHIVED (T-5.1), which keeps existing
    -- references working and accepts no new ones.
    content_item_id uuid        NOT NULL REFERENCES content_item (id),

    ordinal         numeric     NOT NULL,

    -- Optional nodes are visible and never block a gate (T-5.3). Default true,
    -- because "counts towards completion" is what an author means by adding
    -- something to a course, and optional is the deliberate exception.
    required        boolean     NOT NULL DEFAULT true,

    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);

CREATE INDEX ix_course_node_order ON course_node (module_id, ordinal, id);

-- "Which courses use this item" -- asked before archiving one, and by the
-- refusal above when somebody tries to delete one.
CREATE INDEX ix_course_node_content ON course_node (tenant_id, content_item_id);
