-- V9 — deleting a video must delete the bytes (T-3.8).
--
-- THE FAILURE THIS PREVENTS is the one the stemcell's V5 already named for
-- documents, with a larger number attached: a row marked deleted while the
-- object still exists is a leak wearing a tombstone. It costs money forever, it
-- is invisible to everyone who reads the database, and for a customer whose
-- deletion request is a legal obligation rather than a preference it is a
-- statement that is simply untrue.
--
-- SO THE ROW IS THE RECORD OF INTENT AND THE BYTES ARE THE FACT, IN THAT ORDER.
-- Deletion is two states, never one:
--
--   DELETING  somebody asked, we have not confirmed the bytes are gone. The
--             video stops playing immediately, and this row is the queue.
--   DELETED   the provider has confirmed. Only now is the claim true.
--
-- A row can never be gone while the asset remains, which is why nothing here
-- deletes the row at all. The tombstone is also what catalog's content_item
-- (T-5.1) dereferences to a clear answer instead of a dangling id, and what a
-- learner mid-course meets as CONTENT_REMOVED rather than "not ready yet".
--
-- The reverse -- the asset gone while the row still says READY -- is the case
-- provider_orphan below cannot detect, and is why the order is fixed.

ALTER TABLE video_asset
    -- WHO ASKED AND WHY. This is the audit record for the operation: per asset,
    -- permanent, and next to the thing it describes. An app_user id, never a
    -- token subject -- a sub is identity's to hold and repair (ADR-0104), and a
    -- copy of one here would go stale in silence.
    ADD COLUMN deletion_requested_at timestamptz,
    ADD COLUMN deletion_requested_by uuid,
    ADD COLUMN deletion_reason varchar(500),

    -- WHEN THE PROVIDER CONFIRMED. The only column in this file that is allowed
    -- to mean "the bytes are gone", and the only one written after a successful
    -- provider call.
    ADD COLUMN deleted_at timestamptz,

    -- WHY IT HAS NOT HAPPENED YET, for the operator who asks. A deletion that
    -- keeps failing is a compliance problem with a clock on it, and one that
    -- retries silently is one nobody escalates.
    ADD COLUMN deletion_attempts int NOT NULL DEFAULT 0,
    ADD COLUMN deletion_error text;

-- The reconciler's queue: rows waiting for a provider that has not confirmed.
-- Partial, because a healthy system has none of these and a full scan of every
-- video ever uploaded to find nothing is not a background job worth running.
CREATE INDEX ix_video_asset_deleting ON video_asset (updated_at) WHERE state = 'DELETING';


-- ---------------------------------------------------------------------------
-- THE OTHER DIRECTION: BYTES WE ARE PAYING FOR AND NO LONGER KNOW ABOUT
-- ---------------------------------------------------------------------------
--
-- An asset at the provider with no row here is the mirror of the leak above,
-- and it happens for ordinary reasons: an upload target created in a
-- transaction that rolled back, a restore from a backup taken before an upload,
-- a database that switched providers.
--
-- REPORTED, NEVER DELETED AUTOMATICALLY, and that is a decision rather than
-- caution. This service's view of the provider account is not authoritative:
-- the same Cloudflare account may hold assets another environment or another
-- tool created, and a job that deleted everything it did not recognise would
-- one day recognise nothing -- a listing that fails halfway, a provider id that
-- changed, a bug in the join. The blast radius of being wrong is every video
-- the company owns, and it is not recoverable. So this table exists to be read.
--
-- NO tenant_id, deliberately: an orphan is exactly a ref no row can tell us the
-- owner of. That is also why it is written with plain JDBC and never through
-- Hibernate -- the T-1.1 resolver would be right to refuse it a session.
CREATE TABLE provider_orphan (
    id            uuid         PRIMARY KEY,
    provider      varchar(32)  NOT NULL,
    provider_ref  varchar(255) NOT NULL,

    -- Both timestamps, because the pair is the signal. first_seen_at dates the
    -- leak; last_seen_at is what says it is still there -- an orphan that stops
    -- being listed was cleaned up by somebody, and a report that could not tell
    -- the difference would grow forever and be read by nobody.
    first_seen_at timestamptz  NOT NULL,
    last_seen_at  timestamptz  NOT NULL,

    CONSTRAINT uq_provider_orphan UNIQUE (provider, provider_ref)
);
