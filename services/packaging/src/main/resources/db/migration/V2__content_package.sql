-- V2 — content_package: an archive somebody uploaded, and what became of it
-- (T-4.1, T-4.2, ADR-0105).
--
-- WHAT THIS ROW IS. Catalog's `content_item` payload for a SCORM, cmi5 or
-- slides item is `{"packageId": …}` and nothing more (BuiltInContentTypes) —
-- a reference, never content. `id` here is that packageId. Catalog holds it and
-- asks this service when it needs to know anything about the package, which is
-- the same rule that keeps a video's duration out of catalog's tables.
--
-- WHY THE STATE MACHINE IS THE TABLE'S MAIN FEATURE. An uploaded archive is
-- third-party code from a vendor nobody has met (ADR-0105), so the interesting
-- question about any row is not "what is in it" but "how far has it been
-- allowed to get":
--
--   PENDING_UPLOAD  a target has been issued; no bytes have been examined
--   PROCESSING      the archive is being streamed, checked and extracted
--   READY           it passed every check and can be launched
--   REJECTED        it did not, and `error` says which check refused it
--   FAILED          something on OUR side broke -- storage, a timeout
--   DELETING/DELETED  the same two-step streaming uses (T-3.8): the claim that
--                     the bytes are gone is only made once they are
--
-- REJECTED AND FAILED ARE DIFFERENT ROWS ON PURPOSE. One is an author's
-- problem and the sentence tells them what to fix; the other is ours and
-- nobody should be told to re-export their course because our object storage
-- was unreachable. Collapsing them into one ERROR state is what makes a
-- support queue full of "it says it failed".

CREATE TABLE content_package (
    id            uuid         PRIMARY KEY,
    tenant_id     varchar(64)  NOT NULL,

    -- 'scorm', 'cmi5' or 'slides', matching the content-type codes catalog
    -- publishes (BuiltInContentTypes). Lowercase, because these are the
    -- vocabulary of the API rather than an enum of this service's own.
    kind          varchar(16)  NOT NULL,
    state         varchar(24)  NOT NULL,

    -- What the author called the file. Kept only to say it back to them in a
    -- list; nothing is ever derived from it, because a filename is a string an
    -- attacker chose.
    source_name   varchar(255),
    -- The declared size at creation, and the observed size once the archive has
    -- actually been read. They differ when a client lied, which is a thing the
    -- ingest refuses on rather than a thing to trust.
    declared_bytes bigint      NOT NULL,
    source_bytes  bigint,
    -- SHA-256 of the archive as received, hex. The identity of the bytes, for
    -- "is this the same course they uploaded in March".
    --
    -- varchar and not char(64), even though the length is genuinely fixed:
    -- Postgres pads a char to its length with spaces and compares it ignoring
    -- them, so a digest read back would carry trailing whitespace into every
    -- comparison somebody writes outside SQL. The fixed width buys nothing on
    -- disk here either -- it is the same varlena.
    source_sha256 varchar(64),

    -- What the manifest said. `title` is the package's own name, which an
    -- author sees suggested when they attach it; `entry_path` is the file a
    -- launch opens, relative to the package root, and is the ONE path in this
    -- table that a URL is built from -- so it is written only by the manifest
    -- reader, only after normalisation, and can never leave the root.
    title         varchar(512),
    entry_path    varchar(1024),
    -- 'scorm-1.2', 'scorm-2004', 'cmi5' or null for slides. The runtime, not
    -- the file format: it decides which API object the wrapper exposes.
    profile       varchar(32),

    -- Extraction facts, kept because they are the evidence behind a rejection
    -- and the numbers an operator needs when somebody asks why a 3GB export
    -- was refused.
    file_count    integer,
    unpacked_bytes bigint,

    -- Why a REJECTED row was rejected, or why a FAILED one failed. Text rather
    -- than a code: the useful version names the entry, and there is no closed
    -- set of entry names.
    error         text,

    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,

    deletion_requested_at timestamptz,
    deleted_at    timestamptz,

    CONSTRAINT ck_content_package_kind  CHECK (kind IN ('scorm', 'cmi5', 'slides')),
    CONSTRAINT ck_content_package_state CHECK (state IN
        ('PENDING_UPLOAD', 'PROCESSING', 'READY', 'REJECTED', 'FAILED', 'DELETING', 'DELETED'))
);

CREATE INDEX ix_content_package_tenant ON content_package (tenant_id);

-- The list screen's query: this company's packages, newest first. The state is
-- in the index because the common list is "everything that is not deleted",
-- and a company with a thousand courses should not read the deleted ones to
-- find that out.
CREATE INDEX ix_content_package_tenant_state ON content_package (tenant_id, state, created_at DESC);
