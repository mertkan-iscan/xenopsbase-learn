-- V2 — raw playback heartbeats, as posted (T-3.6).
--
-- RAW, and deliberately not merged. ADR-0107 derives completion from the union
-- of watched intervals, and that union is T-3.7's `int4multirange` keyed per
-- learner per item. This table is what arrives, before anything interprets it:
-- one row per sample, written and never updated.
--
-- Keeping the two apart is what lets ingest be the one write path allowed to
-- fail. A merge is a read-modify-write against a row other requests want; an
-- append is not. Ingest stays an insert, and the expensive part happens later,
-- where being late is survivable and being slow does not reach a learner.
--
-- Droppable by design: reporting-inputs.md says rollups are expensive to
-- recompute and raw events are not, so backups follow the rollups. Losing a
-- heartbeat is acceptable. Losing all of them silently is not, which is what
-- the ingest metrics and the alert on T-3.6 exist for.

CREATE TABLE playback_heartbeat (
    id           uuid         PRIMARY KEY,
    tenant_id    varchar(64)  NOT NULL,

    -- The IdP subject from the verified token, and here that IS the right
    -- identifier -- which is the opposite of the rule streaming follows, so it
    -- needs saying. Streaming stores app_user.id because it CAN ask identity,
    -- off the hot path, when it audits a refusal. Reporting may not ask at all
    -- on this path: reporting-inputs.md forbids a synchronous call while
    -- ingesting a sample, precisely so that identity being down cannot stop a
    -- learner's player posting.
    --
    -- So the sample keeps the only identifier it arrived with, and is resolved
    -- to a person when identity's event has landed -- "a sample it cannot yet
    -- interpret is kept and interpreted when the event lands", which is what
    -- that document already promises.
    subject      varchar(255) NOT NULL,

    -- The catalog node being watched. No foreign key: catalog is another
    -- module's schema and does not exist yet (T-5.2).
    node_id      uuid         NOT NULL,

    -- The interval covered SINCE THE LAST HEARTBEAT, in whole seconds of the
    -- video's own timeline (ADR-0107). Not a position: a furthest position is a
    -- claim and a covered interval is a measurement, which is the whole reason
    -- T-3.7 exists. Half-open [from, to), so a ten-second heartbeat covering
    -- 0..10 and the next covering 10..20 meet exactly rather than overlapping.
    from_second  integer      NOT NULL,
    to_second    integer      NOT NULL,

    -- What the player says it was playing at. Recorded rather than trusted:
    -- T-3.7 rejects a batch claiming more content than wall clock allows for
    -- this rate, and it can only do that if the rate is kept.
    rate         real         NOT NULL,

    -- When the player observed it, by the player's clock, and when we received
    -- it, by ours. Both, because their difference is the ingest lag this issue
    -- exports as a metric -- and because a laptop resumed from sleep produces a
    -- skew that is worth seeing rather than a mystery.
    observed_at  timestamptz  NOT NULL,
    received_at  timestamptz  NOT NULL,

    CONSTRAINT ck_playback_heartbeat_interval CHECK (to_second > from_second AND from_second >= 0)
);

-- The one query T-3.7 will run: everything not yet merged for a learner and an
-- item, oldest first. Ordering by received_at rather than observed_at because
-- the merge consumes in arrival order; out-of-order observation is T-3.7's to
-- make idempotent, not this index's to hide.
CREATE INDEX idx_playback_heartbeat_unmerged
    ON playback_heartbeat (tenant_id, subject, node_id, received_at);
