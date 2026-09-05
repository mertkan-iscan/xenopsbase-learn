-- V3 — gates: what makes a module or node reachable (T-5.3).
--
-- A GATE IS A LIST AND A COMBINATOR. NOT AN EXPRESSION LANGUAGE.
--
-- The tempting design is a rule string -- "node:A.completed && (node:B.passed
-- || node:C.completed)" -- and it is tempting because it is easy to store and
-- endlessly flexible. It is also the end of the second acceptance criterion:
-- a rule you can only evaluate by parsing is a rule you cannot EXPLAIN without
-- writing a second parser, and the day those two disagree a learner is told
-- they may proceed by a screen while the server says no. Requirements as rows
-- with one ALL/ANY combinator can be evaluated and read aloud by the same walk.

CREATE TABLE gate (
    id          uuid        PRIMARY KEY,
    tenant_id   varchar(64) NOT NULL,

    -- Which course this gate belongs to. Denormalised on purpose: every read is
    -- "the gates of this course", and deriving it would mean joining through
    -- course_module for a module gate and through course_node and
    -- course_module for a node gate -- two different joins for one question,
    -- on the path a learner waits on.
    course_id   uuid        NOT NULL REFERENCES course (id) ON DELETE CASCADE,

    -- MODULE or NODE. Both are gateable: a gate on a module locks everything
    -- in it, a gate on a node locks one step.
    target_type varchar(16) NOT NULL,
    target_id   uuid        NOT NULL,

    -- ALL or ANY, and nothing else. Two combinators cover every rule anybody
    -- has asked for and both can be said in one English sentence; a third would
    -- be the first step back towards an expression language.
    combinator  varchar(8)  NOT NULL,

    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,

    -- ONE GATE PER TARGET. Two gates on one node would need a combinator
    -- BETWEEN them that nobody specified, and the answer would be whichever the
    -- evaluator happened to read first.
    CONSTRAINT uq_gate_target UNIQUE (target_id),
    CONSTRAINT ck_gate_target_type CHECK (target_type IN ('MODULE', 'NODE')),
    CONSTRAINT ck_gate_combinator CHECK (combinator IN ('ALL', 'ANY'))
);

CREATE INDEX ix_gate_course ON gate (course_id);

CREATE TABLE gate_requirement (
    id               uuid        PRIMARY KEY,
    tenant_id        varchar(64) NOT NULL,
    gate_id          uuid        NOT NULL REFERENCES gate (id) ON DELETE CASCADE,

    requirement_type varchar(16) NOT NULL,
    requirement_id   uuid        NOT NULL,

    -- COMPLETED or PASSED. PASSED is only meaningful where there is something
    -- to pass, which the service checks at write time. What score counts as a
    -- pass is T-6.4's -- a scaled score on the attempt -- and deliberately not
    -- a threshold stored here: a per-gate pass mark would let the same test
    -- mean different things in two courses, which is exactly the confusion
    -- ADR-0106 keeps out of question versions.
    required_state   varchar(16) NOT NULL,

    created_at       timestamptz NOT NULL,

    -- The same requirement twice in one gate is not a stricter rule, it is a
    -- duplicate row that makes the explanation read badly.
    CONSTRAINT uq_gate_requirement UNIQUE (gate_id, requirement_id, required_state),
    CONSTRAINT ck_requirement_type CHECK (requirement_type IN ('MODULE', 'NODE')),
    CONSTRAINT ck_required_state CHECK (required_state IN ('COMPLETED', 'PASSED'))
);

CREATE INDEX ix_gate_requirement_gate ON gate_requirement (gate_id);

-- What a learner has finished, as catalog's own projection.
--
-- CATALOG DOES NOT OWN COMPLETION AND THIS TABLE DOES NOT PRETEND TO. The
-- module that observes the evidence owns the record of it (ADR-0109):
-- streaming derives completion from watched intervals (ADR-0107, T-3.7),
-- assessment from a submitted attempt, packaging from a SCORM run-time. This
-- is a copy, kept so that reachability can be answered without three
-- synchronous calls on the screen a learner looks at most -- a gate evaluated
-- by calling three services is a gate that fails when any of them is slow.
--
-- IT HAS NO WRITER YET, deliberately. T-3.7 and T-9.8 are what fill it, by
-- event. Until then reachability answers as it would for a learner who has
-- completed nothing, which is the correct answer for a platform where nobody
-- has finished anything. There is no endpoint that writes it: an API letting a
-- client declare itself complete is the exact hole ADR-0107 exists to close.
CREATE TABLE node_completion (
    id             uuid        PRIMARY KEY,
    tenant_id      varchar(64) NOT NULL,

    -- app_user.id (ADR-0104). identity owns the person; this is a reference.
    learner_id     uuid        NOT NULL,

    -- The NODE, not the content item. The same video in two courses is two
    -- nodes, and finishing it in the onboarding course is not automatically
    -- finishing it in the refresher -- whether it should be is a product
    -- question (T-5.7), and a schema that cannot express both answers has
    -- decided it by accident.
    node_id        uuid        NOT NULL REFERENCES course_node (id) ON DELETE CASCADE,

    -- COMPLETED, or PASSED for something with a score.
    state          varchar(16) NOT NULL,
    recorded_at    timestamptz NOT NULL,

    CONSTRAINT uq_node_completion UNIQUE (learner_id, node_id, state),
    CONSTRAINT ck_completion_state CHECK (state IN ('COMPLETED', 'PASSED'))
);

-- The read every reachability evaluation makes: this learner, this course.
CREATE INDEX ix_node_completion_learner ON node_completion (tenant_id, learner_id, node_id);
