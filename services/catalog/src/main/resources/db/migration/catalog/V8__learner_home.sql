-- V8 — what the learner home screen reads (T-5.8).
--
-- The screen is the most-hit authenticated view in the product and it touches
-- assignments, group reach, gates, due dates and progress at once. Two things
-- here make that one bounded read rather than a query per assignment: a local
-- copy of how far each learner has got, and a version somebody can key a cache
-- on.

-- ---------------------------------------------------------------------------
-- How far this learner has got with each node.
-- ---------------------------------------------------------------------------
--
-- A PROJECTION OF STREAMING'S DERIVED PROGRESS, NOT A SECOND CALCULATION.
-- `streaming` owns watched intervals and the completion derived from them
-- (ADR-0107, T-3.7); catalog may not read its tables (ADR-0109) and must not
-- work the number out again from anything else -- two answers to "how far are
-- they" is one that disagrees with the compliance report. So the number arrives
-- as an event and lands here, next to node_completion, which arrives the same
-- way and says the stronger thing.
--
-- WHY BOTH TABLES. node_completion is what a GATE reads: a state transition
-- that must never be lost, unique per (learner, node, state), written from an
-- event that is delivered at least once. This is what a SCREEN reads: a
-- percentage and a resume point that may be a few minutes stale without
-- misleading anybody, written from a throttled event that is allowed to be
-- late. Merging them would put the volume of the second on the guarantees of
-- the first.
CREATE TABLE node_progress (
    tenant_id      varchar(64) NOT NULL,

    -- app_user.id (ADR-0104). identity owns the person; this is a reference.
    learner_id     uuid        NOT NULL,

    -- The NODE, because that is what streaming measures against (T-3.7) and
    -- what a screen draws a bar under. No foreign key to course_node: the event
    -- can arrive before or after a node is deleted, and a delivery that fails
    -- because a row went away would stop every other learner's progress behind
    -- it. Rows for nodes that no longer exist are simply never read.
    node_id        uuid        NOT NULL,

    -- Coverage as a percentage of the item's extent, as streaming derived it.
    percent        smallint    NOT NULL,

    -- Where to resume: the furthest second reached. Not the furthest WATCHED --
    -- a learner who skipped ahead and stopped should return to where they
    -- stopped, and their coverage still says they did not watch the middle.
    resume_second  integer     NOT NULL,

    -- Whether coverage crossed the item's threshold. A convenience for the
    -- screen; the authority for a gate is still node_completion.
    completed      boolean     NOT NULL,

    -- Streaming's clock at the moment it announced. The upsert refuses to go
    -- backwards on it, because an at-least-once bus says nothing about order.
    updated_at     timestamptz NOT NULL,

    PRIMARY KEY (tenant_id, learner_id, node_id),
    CONSTRAINT ck_node_progress_percent CHECK (percent BETWEEN 0 AND 100)
);

-- The read the home screen makes: one learner, every node they might be shown.
CREATE INDEX ix_node_progress_learner ON node_progress (tenant_id, learner_id);

-- ---------------------------------------------------------------------------
-- What a cached home screen is keyed on.
-- ---------------------------------------------------------------------------
--
-- VERSION-KEYED, NOT EVICTED, which is the pattern identity's permission cache
-- already uses (T-2.2's authz_version). A writer bumps a counter in the same
-- transaction as the change; a reader includes the counter in the cache key, so
-- a stale entry is not deleted, it simply stops being addressed. Nobody has to
-- remember to evict, and a missed eviction cannot serve a wrong answer -- the
-- worst case is a cold key.
--
-- TWO SCOPES, because the changes have two shapes:
--   * A LEARNER row (learner_id set) is bumped by things about one person --
--     their progress, a completion, their groups, their profile.
--   * The TENANT row (learner_id null) is the company's epoch, bumped by things
--     that change what everybody sees: a course restructured, a gate rewritten,
--     an item republished, an assignment made to a group or to the company.
--     Bumping every learner's row for those would be a write per person.
CREATE TABLE home_version (
    tenant_id  varchar(64) NOT NULL,

    -- Null is the tenant-wide epoch. A nullable column rather than a sentinel
    -- uuid, so "the company's row" is a state the schema expresses rather than
    -- a magic value a reader has to know about.
    learner_id uuid,
    version    bigint      NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- One row per scope, and the partial index is what makes the tenant row unique:
-- a plain UNIQUE over (tenant_id, learner_id) would let two null rows coexist,
-- because null is not equal to null.
CREATE UNIQUE INDEX uq_home_version_learner
    ON home_version (tenant_id, learner_id) WHERE learner_id IS NOT NULL;
CREATE UNIQUE INDEX uq_home_version_tenant
    ON home_version (tenant_id) WHERE learner_id IS NULL;
