-- V2 — video_asset: the one place the delivery vendor is named (T-3.1, ADR-0101).
--
-- provider is a discriminator ('fake', 'cloudflare-stream'); provider_ref is
-- OPAQUE -- minted by the provider, meaningful to nothing but the adapter that
-- issued it. Everything else, in every schema, references video by OUR id:
-- content_item (T-5.1, catalog) points here, never at a provider_ref. That is
-- what keeps ADR-0101's escape hatch an adapter swap instead of a migration.
--
-- state is ours, not the vendor's: PENDING_UPLOAD, PROCESSING, READY, ERRORED
-- (MediaAssetState). T-3.2 writes the first rows; T-3.3 reconciles state from
-- webhook and poll, idempotently, which is why the vendor's answer is never
-- stored verbatim.

CREATE TABLE video_asset (
    id               uuid         PRIMARY KEY,
    tenant_id        varchar(64)  NOT NULL,
    provider         varchar(32)  NOT NULL,
    provider_ref     varchar(255) NOT NULL,
    state            varchar(24)  NOT NULL,
    duration_seconds double precision,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,

    -- One row per provider asset: the webhook and the poll (T-3.3) both
    -- upsert against this, which is what makes them idempotent.
    CONSTRAINT uq_video_asset_provider_ref UNIQUE (provider, provider_ref)
);

CREATE INDEX ix_video_asset_tenant ON video_asset (tenant_id);
