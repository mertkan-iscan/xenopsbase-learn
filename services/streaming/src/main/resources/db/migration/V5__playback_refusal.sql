-- V5 — every playback token this service refused, and why (T-3.4).
--
-- Refusals only. A grant is one row per viewer per five minutes on the learner
-- hot path, and what a learner watched is already E7's subject (T-7.1) — a
-- second, worse copy of that here would put a write on the one path ADR-0101
-- exists to keep light. A refusal is rare, has no other owner, and is the thing
-- somebody has to explain when a customer says "it says I cannot watch this".
--
-- Its own table rather than identity's audit_log, because no module reads
-- another module's schema. The two are joined on app_user.id when a question
-- needs both.

CREATE TABLE playback_refusal (
    id            uuid        PRIMARY KEY,
    tenant_id     varchar(64) NOT NULL,
    -- app_user.id, resolved through identity, never an IdP sub (ADR-0104) -- a rule this
    -- module's schema test enforces rather than leaves to memory. A sub is a link identity
    -- may repair (T-1.7 has a script for it), so one stored here would go stale in silence
    -- and the trail would quietly stop pointing at anybody.
    --
    -- No foreign key, and nullable: app_user lives in identity's schema, which this module
    -- does not read, and identity may be unreachable at the moment a refusal is written. A
    -- null loses the person and keeps the reason, which is the right way round -- "learners
    -- are being refused for want of a grant" is still answerable without knowing which one.
    actor_user_id uuid,
    -- The catalog node asked for. No foreign key: catalog is a different module
    -- with a different schema, and it does not exist yet (T-5.2). A refusal for
    -- a node id that was never real is exactly the kind of thing worth keeping.
    node_id     uuid        NOT NULL,
    -- RefusalReason.name(). The reason as the decision knew it, which is often
    -- narrower than what the caller was told: three of these render as the same
    -- bare 404 so that a caller cannot probe the difference. This column is
    -- where the difference survives, and it is the whole reason the audit is
    -- worth writing — "nobody granted learners content:view" and "the course
    -- was never assigned" are different problems with the same symptom.
    reason      varchar(64) NOT NULL,
    -- Free text from the decision: a gate's sentence, an asset state, an id
    -- that did not resolve. Nullable, because most refusals need no elaboration.
    detail      text,
    created_at  timestamptz NOT NULL
);

-- The two questions this table exists to answer, both scoped to one tenant:
-- "why is this person being refused" and "what is being refused across this
-- customer right now". Both start from tenant_id and read recent rows first.
CREATE INDEX idx_playback_refusal_tenant_time ON playback_refusal (tenant_id, created_at DESC);
CREATE INDEX idx_playback_refusal_actor ON playback_refusal (tenant_id, actor_user_id, created_at DESC);
