-- V4 — assignment: who has to do what (T-5.5).
--
-- ONE MODEL, FOUR REFERENCE KINDS, THREE TARGET KINDS.
--
-- "Assign one video to one student" and "assign a course to a group" look like
-- different features, and building them as different features ends with four
-- assignment code paths of which three are wrong -- the one nobody exercises is
-- the one that silently stops reaching people. A reference that can point at
-- any level of the tree makes them the same feature, and this table is that
-- reference.

-- The number an assignment pins (T-5.5's fifth criterion).
--
-- Bumped whenever a course's STRUCTURE changes -- a module or node added, moved
-- or removed. Not on a rename: a typo fixed in a title is not a different
-- course, and treating it as one would flag every assignment in the tenant as
-- drifted for a change nobody needs to know about.
--
-- WHAT THIS PIN IS AND IS NOT. It records WHICH version an assignment was made
-- against, so "this course changed since you assigned it" is a query rather
-- than a guess. It is NOT a snapshot: serving a learner the structure as it was
-- needs immutable published versions, which is T-5.7's, and pretending
-- otherwise here would be a column that looks like a guarantee and is not one.
ALTER TABLE course ADD COLUMN structure_version bigint NOT NULL DEFAULT 1;

CREATE TABLE assignment (
    id             uuid        PRIMARY KEY,
    tenant_id      varchar(64) NOT NULL,

    -- USER, GROUP or TENANT. TENANT means everybody in the company and carries
    -- no target_id -- "the whole company" is not an id, and inventing one would
    -- make every query join to a row that exists to mean "no row".
    target_type    varchar(16) NOT NULL,
    target_id      uuid,

    -- COURSE, MODULE, NODE or CONTENT_ITEM. No foreign key, and that is the one
    -- place this table gives up a database-level guarantee on purpose: a column
    -- that must reference one of four tables cannot have a foreign key, and the
    -- alternatives are four nullable columns with a CHECK that exactly one is
    -- set (four indexes, four joins, and every reader learning the trick) or a
    -- table per kind (the four code paths this design exists to avoid).
    -- Referential integrity is enforced at write time by AssignmentService,
    -- which is also where the "only PUBLISHED may be newly referenced" rule
    -- lives (T-5.1) -- a foreign key could not have expressed that anyway.
    reference_type varchar(16) NOT NULL,
    reference_id   uuid        NOT NULL,

    -- The structure_version of the course this reference belongs to, at the
    -- moment of assigning. Null for a bare CONTENT_ITEM, which has no structure
    -- to drift.
    pinned_version bigint,

    -- app_user.id of whoever assigned it (ADR-0104), for "who gave me this".
    assigned_by    uuid        NOT NULL,
    assigned_at    timestamptz NOT NULL,

    -- Revoked rather than deleted. An obligation that was withdrawn is a
    -- different fact from one that never existed, and a compliance report a
    -- year later has to be able to tell them apart.
    revoked_at     timestamptz,

    CONSTRAINT ck_assignment_target CHECK (target_type IN ('USER', 'GROUP', 'TENANT')),
    CONSTRAINT ck_assignment_reference
        CHECK (reference_type IN ('COURSE', 'MODULE', 'NODE', 'CONTENT_ITEM')),
    -- A TENANT assignment has no target id; the other two must have one.
    CONSTRAINT ck_assignment_target_id
        CHECK ((target_type = 'TENANT') = (target_id IS NULL)),
    CONSTRAINT ck_assignment_revoked CHECK (revoked_at IS NULL OR revoked_at >= assigned_at)
);

-- The same thing assigned to the same target twice is not a stronger obligation,
-- it is a duplicate that shows up twice on a learner's list.
--
-- A partial unique INDEX rather than an EXCLUDE constraint: EXCLUDE with `=` on
-- scalar columns needs btree_gist, which is an extension to install for a
-- guarantee a plain index already gives. Partial on revoked_at, so withdrawing
-- an assignment and later making it again is allowed -- which is an ordinary
-- thing to do and would otherwise collide with the historical row.
CREATE UNIQUE INDEX uq_assignment_live ON assignment (
    tenant_id, target_type,
    coalesce(target_id, '00000000-0000-0000-0000-000000000000'::uuid),
    reference_type, reference_id
) WHERE revoked_at IS NULL;

-- The read a learner's home screen makes: everything live for these targets.
CREATE INDEX ix_assignment_target ON assignment (tenant_id, target_type, target_id)
    WHERE revoked_at IS NULL;

-- "Who has been assigned this course", for the compliance side (T-7.6).
CREATE INDEX ix_assignment_reference ON assignment (tenant_id, reference_type, reference_id);

-- WHICH GROUPS REACH WHICH LEARNER -- catalog's own projection of identity's tree.
--
-- Catalog must not read identity's schema (ADR-0109), and it must not call it
-- synchronously on the screen a learner opens first: a home screen that fails
-- when identity is slow is a home screen that fails.
--
-- REACH, NOT MEMBERSHIP, and the difference is where the tree walk happens. A
-- group assignment reaches the members of that group AND of everything inside
-- it -- containment is what the tree means, and it is the rule role assignments
-- already follow (T-2.3). Identity owns the tree, so identity does the walk and
-- publishes the result; catalog stores who is reached and never needs to know
-- the shape. That keeps the depth limit (GroupHierarchy.MAX_DEPTH) in the one
-- module that can enforce it.
--
-- NOTHING WRITES THIS YET. T-1.3's membership events and T-9.8's bus are what
-- fill it. Until then a group assignment reaches nobody, which is the correct
-- answer for a platform with no membership data rather than an approximation
-- that reaches everybody.
CREATE TABLE learner_group_reach (
    tenant_id  varchar(64) NOT NULL,
    learner_id uuid        NOT NULL,
    group_id   uuid        NOT NULL,

    PRIMARY KEY (tenant_id, learner_id, group_id)
);

-- The one query the learner read makes against this.
CREATE INDEX ix_learner_group_reach_learner ON learner_group_reach (tenant_id, learner_id);
