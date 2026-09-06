-- V7 — the tenant row, platform identity, and idempotency (T-1.5).
--
-- Three things this migration settles, each of which earlier tasks deferred to
-- "T-1.5's root-tenant opt-in".

-- 1. A company is a row (ADR-0102), and this is the row. tenant_id is the slug
-- every other table already carries as its discriminator, so it is the primary
-- key rather than a surrogate: a second identifier for the same thing would
-- mean every existing table pointed at the wrong one.
CREATE TABLE tenant (
    tenant_id   varchar(64)  PRIMARY KEY,
    name        varchar(255) NOT NULL,
    -- ACTIVE, READ_ONLY or SUSPENDED. T-1.4 owns the transitions and the
    -- enforcement; the column exists from the first migration so that task is
    -- a state machine and a gateway check, not a migration of live rows.
    status      varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    -- ARCHIVING, DESIGNED HERE AND IMPLEMENTED LATER (T-1.5's fifth criterion).
    -- Archiving sets this timestamp; it does not delete. An archived tenant is
    -- refused at the gateway exactly like a suspended one, keeps every row it
    -- ever had, and can be restored by clearing the column. Deletion proper is
    -- a separate, later operation that must (a) delete the provider-side video
    -- assets first (T-3.8 owns that path), (b) delete our rows in foreign-key
    -- order inside one transaction, and (c) leave the audit log alone, because
    -- the record of what a customer did outlives the customer. Nothing here is
    -- allowed to cascade on its own: every FK into tenant data is deliberately
    -- absent so that no delete can happen implicitly.
    archived_at timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);

-- 2. The platform is a tenant, reserved. Platform staff are people too, and
-- ADR-0104 says a person is an app_user.id -- so there has to be a tenant for
-- their rows to live in, or "who provisioned this company" has no honest
-- answer in the audit log.
--
-- T-1.1 deliberately bound NO tenant for platform tokens, and the reason was
-- sound at the time: a sentinel with no rows behind it becomes a filter that
-- matches nothing, silently. With this row and the people in it, the sentinel
-- matches exactly the platform's own data, which is what makes binding it
-- correct rather than dangerous.
INSERT INTO tenant (tenant_id, name) VALUES ('__platform', 'XenOpsBase (platform)');

-- Dev tenants that predate this table. Their rows already exist everywhere
-- else, so the tenant row is backfilled rather than invented.
INSERT INTO tenant (tenant_id, name)
SELECT DISTINCT tenant_id, tenant_id FROM app_user WHERE tenant_id <> '__platform'
ON CONFLICT DO NOTHING;

-- 3. Idempotency. T-1.5 was written expecting "the existing IdempotencyFilter";
-- there was none, so this is its store.
--
-- The fingerprint is what makes a repeated key safe: replaying a key with a
-- DIFFERENT body is a client bug, and answering it with the first request's
-- response would hide the bug and the wrong outcome together.
CREATE TABLE idempotency_record (
    idempotency_key     varchar(128) PRIMARY KEY,
    request_fingerprint varchar(128) NOT NULL,
    -- Null until the request finishes: a row with no response is a request
    -- still in flight, and a concurrent replay of it is a conflict rather
    -- than a second execution.
    response_status     integer,
    response_body       text,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    completed_at        timestamptz
);
