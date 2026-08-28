-- V3 — what direct upload needs on video_asset (T-3.2).
--
-- size_bytes is the DECLARED size, captured when the target is issued: it is
-- what quota was checked against, and the tus Upload-Length makes it binding
-- at ingest. upload_target_expires_at is when the issued target stops being
-- usable; the reaper uses updated_at rather than this, because a target that
-- was re-issued moves updated_at forward and buys the upload more time.
--
-- created_by (the requesting app_user.id, per ADR-0104's audit rule) is
-- ABSENT deliberately rather than nullable: streaming cannot resolve a token
-- to an app_user.id until services can call identity (T-9.11), and a column
-- that is null for every row is a rule nobody is following yet pretending to
-- be followed. Recorded on #35; arrives with the resolution path.

ALTER TABLE video_asset
    ADD COLUMN size_bytes bigint NOT NULL,
    ADD COLUMN max_duration_seconds bigint NOT NULL,
    ADD COLUMN upload_target_expires_at timestamptz;
