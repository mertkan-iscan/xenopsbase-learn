-- V7 — what a learner has actually watched, and the completion derived from it
-- (T-3.7, ADR-0107).
--
-- A FURTHEST POSITION IS A CLAIM; A SET OF SECONDS IS A MEASUREMENT. Dragging
-- the scrubber produces the first without watching anything, which is why this
-- table stores the second: the union of the intervals a player reported covering,
-- and nothing a browser can assert directly. There is no `completed` column a
-- client can set, and no endpoint that would set one.

CREATE TABLE learner_node_progress (
    id             uuid        PRIMARY KEY,
    tenant_id      varchar(64) NOT NULL,

    -- app_user.id (ADR-0104), resolved through identity. Never an IdP sub: a sub
    -- is a link identity may repair (T-1.7 has a script for it), and a completion
    -- record that quietly stops pointing at anybody is worse than none.
    learner_id     uuid        NOT NULL,

    -- THE NODE, NOT THE CONTENT ITEM, and this is a real departure from ADR-0107's
    -- wording that is worth reading before changing.
    --
    -- ADR-0107 says the key is (app_user_id, content_item_id). This module does not
    -- know what a content item is and must not learn (ADR-0109): what it has is the
    -- node a playback token was minted for. Keying by node also keeps a product
    -- question open that keying by item would answer by accident -- whether
    -- finishing a video in the onboarding course finishes the same video in the
    -- refresher is T-5.7's decision, and catalog can fold node-grained evidence
    -- either way. Item-grained coverage here would have decided it in a schema.
    node_id        uuid        NOT NULL,

    -- Whose duration is the denominator. Ours, never a provider ref (ADR-0101).
    -- No foreign key on purpose is not needed here: the asset is this module's own
    -- row, so the reference is real and worth enforcing.
    video_asset_id uuid        NOT NULL REFERENCES video_asset (id) ON DELETE CASCADE,

    -- THE REPRESENTATION (ADR-0107). Whole seconds, half-open [from, to), as a
    -- Postgres int4multirange -- chosen for one property: union is native and the
    -- result is always normalised, so no merge can leave overlapping or adjacent
    -- fragments behind however badly ordered its input was.
    covered        int4multirange NOT NULL DEFAULT '{}'::int4multirange,

    -- Denormalised so that "is this person done" is a column read rather than an
    -- aggregate over a range type. It is derived from `covered` and a test
    -- recomputes it from the multirange rather than trusting it.
    covered_seconds  integer   NOT NULL DEFAULT 0,

    -- THE SIZE BOUND, made visible rather than assumed. A learner watching
    -- straight through has exactly one fragment; fragments come only from seeking.
    -- Gaps of two seconds or less are coalesced (inside a ten-second heartbeat's
    -- sampling error), and at the cap the two fragments separated by the smallest
    -- gap are merged and the row is flagged approximate -- so a pathological
    -- scrubber degrades a bounded amount instead of growing a value that is
    -- rewritten on every heartbeat. The cap's measured justification is in
    -- ADR-0107 and docs/slos.md.
    fragments      smallint    NOT NULL DEFAULT 0,
    approximate    boolean     NOT NULL DEFAULT false,

    -- Where to resume. The furthest second reached, which is not the same as the
    -- furthest second WATCHED when seeking is allowed -- a learner who skipped
    -- ahead and stopped should return to where they stopped, and their coverage
    -- still says they did not watch the middle.
    furthest_second integer    NOT NULL DEFAULT 0,

    -- THE POLICY, COPIED FROM CATALOG WHEN THE ROW IS CREATED AND REFRESHED
    -- OCCASIONALLY. Both belong to the content item and neither may be asked for
    -- per heartbeat: at one post per learner per ten seconds, a catalog hop here
    -- would be the most-called cross-service call in the product. The entitlement
    -- port is asked when a row is first created for a learner and again once the
    -- copy is older than the configured refresh interval.
    threshold_percent  smallint    NOT NULL,
    allow_seek_forward boolean     NOT NULL DEFAULT true,
    policy_seen_at     timestamptz NOT NULL,

    -- The extent as the provider reported it (T-3.1), copied at the moment
    -- completion was derived so that a re-encode changing the duration cannot
    -- silently re-decide a completion that has already been reported.
    extent_seconds integer,

    completed_at   timestamptz,

    -- DERIVED, SELF_REPORTED or MANUAL (ADR-0107). Only DERIVED is written here;
    -- the column exists because every export must carry the source, and a report
    -- that mixes measured with self-reported completions without saying so is the
    -- exact report ADR-0107 exists to prevent. SCORM and cmi5 (T-4.4, T-4.6) write
    -- the other values in their own module.
    completion_source varchar(16) NOT NULL DEFAULT 'DERIVED',

    -- THE RATE-SANITY ANCHOR. Wall clock cannot be argued with: coverage credited
    -- over this row's life may not exceed the time that has passed since the first
    -- heartbeat for it, at the fastest rate any player offers, plus a grace for a
    -- buffered batch. Anchored to the ROW rather than to a playback session
    -- because a learner returning from ten minutes offline legitimately posts ten
    -- minutes of samples under one expired token, and a per-session bound would
    -- reject exactly that honest case.
    first_seen_at    timestamptz NOT NULL,

    -- Which playback session (T-3.4's token, as a SHA-256 of it -- the token
    -- itself is a credential and is not stored) most recently contributed, and
    -- when it first did. ADR-0107 chose this credential over designing a nonce;
    -- this is where the attribution lands.
    session_hash       char(64),
    session_started_at timestamptz,

    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,

    -- One row per learner per node. The merge is an upsert against this key, which
    -- is what makes a duplicated or out-of-order batch cost nothing.
    CONSTRAINT uq_learner_node_progress UNIQUE (learner_id, node_id),
    CONSTRAINT ck_progress_threshold CHECK (threshold_percent BETWEEN 1 AND 100),
    CONSTRAINT ck_progress_source
        CHECK (completion_source IN ('DERIVED', 'SELF_REPORTED', 'MANUAL'))
);

-- The read a heartbeat makes, and the one the player makes on load.
CREATE INDEX ix_progress_learner ON learner_node_progress (tenant_id, learner_id, node_id);

-- "Who finished this, and when" -- the question a report asks before the rollups
-- of E7 exist, and the one a reconciliation job (T-7.3) will ask afterwards.
CREATE INDEX ix_progress_completed ON learner_node_progress (tenant_id, node_id, completed_at)
    WHERE completed_at IS NOT NULL;

-- Every batch this service would not credit, and why (T-3.7).
--
-- ADR-0107 requires an implausible batch to be rejected AND COUNTED. The meters
-- that count it exist and are not scrapeable -- every service here permits only
-- /management/health and /management/info, which T-9.13 owns -- so the count
-- that can actually be read is this table. It is also the better record: "why
-- is this person's progress not moving" is a question about one learner on one
-- item, and a reason with a time answers it while a counter does not.
--
-- ITS OWN TABLE RATHER THAN COLUMNS ON THE PROGRESS ROW, and the reason is a
-- deadlock that cost an afternoon here before. A rejection rolls its transaction
-- back, so the count has to be written in a separate one (REQUIRES_NEW) -- and
-- that transaction cannot touch the progress row, because the rejecting
-- transaction is holding it under SELECT ... FOR UPDATE and is waiting for the
-- new one to return. Postgres shows that as a hang rather than as a deadlock.
-- A different table has no such contention.
CREATE TABLE progress_refusal (
    id          uuid        PRIMARY KEY,
    tenant_id   varchar(64) NOT NULL,

    -- app_user.id (ADR-0104). Nullable for the same reason playback_refusal's is:
    -- identity may be unreachable at the moment a refusal is written, and losing
    -- the person while keeping the reason is the right way round.
    learner_id  uuid,
    node_id     uuid        NOT NULL,

    -- ProgressRejection.name(): IMPLAUSIBLE_RATE or SEEK_NOT_ALLOWED. The shape
    -- refusals (a malformed batch, one too large) are a player bug rather than
    -- anything about a learner, and they are counted in the meter only.
    reason      varchar(64) NOT NULL,
    detail      text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- "Why is this learner's progress stuck", and "what is being refused across this
-- customer right now" -- both start from the tenant and read recent rows first.
CREATE INDEX ix_progress_refusal_learner
    ON progress_refusal (tenant_id, learner_id, created_at DESC);
