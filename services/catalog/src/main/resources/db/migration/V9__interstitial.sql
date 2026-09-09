-- V9 — a question pinned inside a video's timeline, and the record of answering it (T-5.4).
--
-- EVERYTHING ELSE IN THE STRUCTURE IS ORDERED RELATIVE TO OTHER NODES. This one
-- is positioned INSIDE another item, so it is bound to (node, second) rather
-- than to an ordinal — and that one difference is why it is not a `course_node`
-- with a fractional ordinal, which is what it looks like from a distance.
--
-- WHAT MAKES A BLOCKING ONE REAL. A pause a player performs is a pause a player
-- can decline to perform. What is enforced instead is arithmetic: streaming
-- will not CREDIT coverage past an unanswered blocking interstitial (T-3.7), so
-- a learner whose player skipped it can watch the rest of the video and still
-- not complete the item. `position_seconds` is therefore not a hint to a UI. It
-- is a frontier in the completion accounting, and this table is where it is
-- decided.

CREATE TABLE node_interstitial (
    id        uuid        PRIMARY KEY,
    tenant_id varchar(64) NOT NULL,

    -- CASCADE, unlike course_node.content_item_id. An interstitial has no
    -- meaning away from the node whose timeline it sits in: there is nowhere
    -- else it could be shown and nothing else it could be attached to.
    node_id   uuid        NOT NULL REFERENCES course_node (id) ON DELETE CASCADE,

    -- Whole seconds into the item. NOT validated against the video's duration,
    -- and that is ADR-0109 rather than laziness: the duration is streaming's
    -- fact, catalog may not copy it, and asking for it while an author drags a
    -- marker would put a cross-service call on an editor keystroke. An
    -- interstitial past the end is simply never reached, which is a harmless
    -- authoring mistake and a visible one.
    position_seconds integer NOT NULL,

    -- BLOCKING is the default, because "answer this before going on" is what an
    -- author means by putting a question inside a video; a marker that only
    -- offers itself is the deliberate exception. A non-blocking one is shown,
    -- may be answered, is reported — and moves no frontier.
    blocking  boolean     NOT NULL DEFAULT true,

    -- Whether a later viewing asks again. Default false: a learner who answered
    -- this and came back to re-watch a section has answered it, and re-asking
    -- would punish exactly the behaviour the product wants.
    --
    -- Its scope when true is ONE VIEWING, identified by the playback session the
    -- answer arrived under. That is a pedagogical preference and not a security
    -- boundary — a learner who wanted to could answer under a stale viewing id
    -- and skip the re-ask — and it is written down here rather than defended,
    -- because the thing worth defending is completion, which the frontier holds
    -- whichever viewing this is.
    ask_again boolean     NOT NULL DEFAULT false,

    -- Assessment's question (T-6.2), by id. A reference and never a copy: the
    -- question's text, options and answer key live in the module that versions
    -- them, and a stem duplicated here would be the stem that stops matching.
    question_id uuid      NOT NULL,

    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT ck_interstitial_position CHECK (position_seconds >= 0),

    -- ONE PER SECOND PER NODE. Two interstitials at the same instant have no
    -- order a player could show them in and no order this table could report,
    -- so the ambiguity is refused where it is cheap to refuse rather than
    -- resolved by whatever the planner returns.
    CONSTRAINT uq_interstitial_position UNIQUE (node_id, position_seconds)
);

-- The read the frontier query makes: the blocking ones on a node, earliest first.
CREATE INDEX ix_interstitial_node
    ON node_interstitial (tenant_id, node_id, position_seconds);

-- WHO ANSWERED WHAT, and the only thing that moves a frontier (T-5.4).
--
-- CATALOG DOES NOT DECIDE THAT AN ANSWER HAPPENED AND CANNOT. Assessment
-- observes the attempt and owns the record of it (ADR-0109, and exactly the
-- shape node_completion has for streaming's completions); what crosses the
-- boundary is an event, and this table is the projection of it. There is
-- deliberately no endpoint a browser can post to that writes a row here: a
-- learner who could assert "I answered it" would have a one-request path past
-- every blocking interstitial in the product.
CREATE TABLE interstitial_response (
    id              uuid        PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    interstitial_id uuid        NOT NULL
                                REFERENCES node_interstitial (id) ON DELETE CASCADE,

    -- app_user.id (ADR-0104). Never an IdP sub, for the reason every other
    -- learner-keyed table here gives: a sub is a link identity may repair.
    learner_id      uuid        NOT NULL,

    -- Assessment's attempt, when there is one. NULLABLE because attempts are
    -- T-6.6's (#65) and this table has to exist before them — the frontier is
    -- the half T-5.4 owes, and it is testable today by delivering the event.
    -- The reporting criterion is the half that waits for the attempt.
    attempt_id      uuid,

    -- WHICH VIEWING, for ask_again. The empty string means "any viewing", which
    -- is what an ordinary interstitial's answer is worth. A real value scopes
    -- the answer to one playback session.
    --
    -- Not nullable, and that is the unique key working: in Postgres two NULLs
    -- are distinct, so a nullable column here would let the same learner's
    -- answer be recorded any number of times and the at-least-once bus would
    -- do exactly that.
    viewing         varchar(64) NOT NULL DEFAULT '',

    -- When the learner answered, not when the bus got round to telling us.
    answered_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_interstitial_response UNIQUE (interstitial_id, learner_id, viewing)
);

-- "What has this learner answered on this node" — the frontier query's NOT
-- EXISTS, which is the read on the playback hot path.
CREATE INDEX ix_interstitial_response_learner
    ON interstitial_response (tenant_id, learner_id, interstitial_id);
