-- V3 — html5 joins the kinds, and where a learner got to inside a package.
--
-- The CHECK in V2 listed the three kinds that existed then. A fourth (T-4.8)
-- has to be allowed before a row can carry it, and widening a CHECK is the one
-- schema change that is genuinely free -- it refuses strictly less than before,
-- so nothing already stored can stop being valid.
ALTER TABLE content_package DROP CONSTRAINT ck_content_package_kind;
ALTER TABLE content_package
    ADD CONSTRAINT ck_content_package_kind
    CHECK (kind IN ('scorm', 'cmi5', 'html5', 'slides'));

-- Where a learner got to inside a package (T-4.4, ADR-0107).
--
-- WHAT THIS IS FOR, IN ONE SENTENCE: a learner who closes a forty-minute SCORM
-- course at slide thirty must open it again at slide thirty.
--
-- SCORM's own answer to that is `cmi.suspend_data` plus `cmi.core.lesson_location`,
-- and the standard's contract is that the LMS gives them back on the next
-- launch. Without this table the wrapper's data model lives for exactly one
-- page load, every launch is `ab-initio`, and the product silently loses every
-- resume in it.
--
-- THE WHOLE CMI MAP, AS JSONB, RATHER THAN COLUMNS PER ELEMENT
--
-- The data model is open: a package writes `cmi.interactions.7.student_response`
-- and `cmi.objectives.2.score.raw`, and the element names are generated at
-- runtime from content nobody here has seen. A schema of named columns would
-- have to be extended every time an authoring tool used one more part of a
-- standard we already claim to support, and the failure would be silent -- the
-- value written, the value gone.
--
-- The three facts this platform ACTS on are lifted out into their own columns
-- below, derived on write. Everything else is storage the package gets back
-- verbatim, and no query here reaches inside it.
--
-- ONE ROW PER (LEARNER, PACKAGE, NODE), AND NODE IS PART OF THE KEY
--
-- The same package attached to two courses is two nodes, and finishing it in
-- the onboarding course is not finishing it in the annual refresher -- catalog
-- makes exactly this argument for `node_completion` (V3__gates.sql) and it has
-- to hold here too, or a learner's second course would open already complete.
--
-- `node_id` is nullable, for a launch that belongs to no course: an author
-- checking an upload before attaching it. UNIQUE NULLS NOT DISTINCT is what
-- makes that one row rather than unlimited rows -- a plain UNIQUE treats every
-- NULL as different, so a preview would create a fresh runtime on every launch
-- and never resume.

CREATE TABLE package_runtime (
    id             uuid         PRIMARY KEY,
    tenant_id      varchar(64)  NOT NULL,

    package_id     uuid         NOT NULL REFERENCES content_package (id) ON DELETE CASCADE,

    -- app_user.id (ADR-0104). identity owns the person; this is a reference,
    -- resolved from the caller's token and never taken from a request body.
    learner_id     uuid         NOT NULL,

    -- The catalog node this launch belongs to, or null for a preview. Not a
    -- foreign key: course_node is catalog's table, in catalog's database
    -- (ADR-0109), and a column that could not be written without reading it
    -- would be a boundary violation with a constraint on top.
    node_id        uuid,

    -- The CMI data model as the package left it. Given back verbatim on the
    -- next launch; nothing here parses it except the derivation below.
    data           jsonb        NOT NULL DEFAULT '{}'::jsonb,

    -- THE THREE FACTS THE PLATFORM ACTS ON, derived on write from `data` and
    -- stored so a compliance query is a row read rather than a JSON walk over
    -- every learner's runtime.
    --
    -- `completed` is what becomes a node completion; `passed` is null for a
    -- package that reports no success status, which is NOT the same as false
    -- (a package with no test neither passed nor failed); `score_raw` is
    -- whatever the package called a score, on whatever scale it chose, which
    -- is why nothing compares it to anything.
    completed      boolean      NOT NULL DEFAULT false,
    passed         boolean,
    score_raw      numeric(10, 4),

    -- How many times they have opened it. `cmi.core.entry` is `ab-initio` on
    -- the first and `resume` afterwards, which is a thing the package reads and
    -- branches on.
    launches       integer      NOT NULL DEFAULT 0,

    -- Corroboration, never the decision (ADR-0107): "time spent in a package is
    -- still recorded and still reported, as corroboration; it never overrides
    -- what the package said."
    seconds_spent  integer      NOT NULL DEFAULT 0,

    started_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    -- When they FIRST completed it. Set once and never moved: a package that
    -- keeps saying "completed" on every later launch must not walk a compliance
    -- date forward, because the date is the thing an auditor reads.
    completed_at   timestamptz,

    CONSTRAINT uq_package_runtime
        UNIQUE NULLS NOT DISTINCT (tenant_id, learner_id, package_id, node_id)
);

CREATE INDEX ix_package_runtime_tenant ON package_runtime (tenant_id);

-- "Who has finished this package", which is the compliance question, without
-- reading the runtime of everybody who has not.
CREATE INDEX ix_package_runtime_completed
    ON package_runtime (tenant_id, package_id, completed);
