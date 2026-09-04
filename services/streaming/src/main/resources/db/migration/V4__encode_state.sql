-- V4 — the encode state arriving twice (T-3.3).
--
-- Two things this needs that the asset table did not have: somewhere to keep
-- the provider's own words when an encode fails, and a record of which events
-- have already been applied.

ALTER TABLE video_asset
    -- The provider's reason, verbatim. An author told "encoding failed" files a
    -- support ticket; an author told "audio codec not supported" re-exports the
    -- file. Bounded because it is somebody else's string, and truncated rather
    -- than dropped if they ever send an essay.
    ADD COLUMN error_reason varchar(512);

-- Every event we have already acted on. The webhook handler is idempotent
-- BECAUSE of this row, not because the transition happens to be repeatable:
-- five deliveries of one event insert once and transition once, and the four
-- losers see the unique violation and stop.
--
-- No tenant_id, deliberately: a provider event arrives before anything of ours
-- has decided which tenant it belongs to, and the asset it names is what
-- carries the tenant. It is infrastructure, not tenant data.
CREATE TABLE provider_event (
    provider    varchar(32)  NOT NULL,
    event_id    varchar(255) NOT NULL,
    provider_ref varchar(255) NOT NULL,
    received_at timestamptz  NOT NULL DEFAULT now(),

    PRIMARY KEY (provider, event_id)
);

CREATE INDEX ix_provider_event_ref ON provider_event (provider, provider_ref);

-- The reconciling poll's query: assets sitting in a non-terminal state. Partial,
-- because the terminal ones are the overwhelming majority once a library exists
-- and none of them are ever a candidate.
CREATE INDEX ix_video_asset_unsettled ON video_asset (updated_at)
    WHERE state IN ('PENDING_UPLOAD', 'PROCESSING');
