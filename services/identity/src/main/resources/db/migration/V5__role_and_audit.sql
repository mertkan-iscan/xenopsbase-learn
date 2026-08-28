-- V5 — roles as rows, the version that invalidates caches, and the audit log
-- this platform did not have yet (T-2.2, ADR-0103).
--
-- app_role, not "role": ROLE is a reserved word in Postgres, and a table that
-- needs quoting everywhere is a table somebody eventually forgets to quote.
-- Same reasoning as app_user.

CREATE TABLE app_role (
    id          uuid         PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    name        varchar(128) NOT NULL,
    description varchar(512),
    -- TENANT or PLATFORM. Both are modelled because the catalog has both sides
    -- (T-2.1), but only tenant-side roles are runtime-editable today: a
    -- platform-side row cannot even be read yet, because the T-1.1 resolver
    -- refuses a session with no tenant bound and the root-tenant opt-in
    -- arrives with T-1.5. Seeded system roles are T-2.7.
    side        varchar(16)  NOT NULL,
    -- A system role is cloneable but not editable (T-2.7 owns the seeding and
    -- the rule; the column exists from the first migration so that task is a
    -- seeder and a guard, not a migration of populated rows).
    system      boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

-- Two roles in one tenant cannot share a name, case-insensitively. The id is
-- the identity everywhere else (assignments point at it), so renaming is free
-- and this index constrains only what a human reads.
CREATE UNIQUE INDEX uq_app_role_name ON app_role (tenant_id, lower(name));

CREATE TABLE role_permission (
    id              uuid        PRIMARY KEY,
    tenant_id       varchar(64) NOT NULL,
    role_id         uuid        NOT NULL REFERENCES app_role (id) ON DELETE CASCADE,
    -- A real foreign key to the catalog projection: a role cannot hold a
    -- permission that does not exist, and the seeder is the only writer of
    -- that table (T-2.1). This is also what stops a retired-and-orphaned
    -- permission from being silently deleted out from under a role -- the
    -- seeder marks it orphaned precisely because this reference exists.
    permission_code varchar(64) NOT NULL REFERENCES permission (code),
    created_at      timestamptz NOT NULL,

    CONSTRAINT uq_role_permission UNIQUE (role_id, permission_code)
);

CREATE INDEX ix_role_permission_role ON role_permission (role_id);

-- The number a cached permission set is validated against (ADR-0103).
--
-- PER TENANT, where the ADR says per (tenant, user) -- and the difference is
-- honest rather than a shortcut: until assignments exist (T-2.3) nothing can
-- say WHICH users a role edit affects, so the only correct answer is "every
-- user in the tenant". That invalidates more than necessary and never less,
-- which is the safe direction; T-2.5 narrows it once T-2.3 can answer.
--
-- In Postgres rather than Valkey, deliberately. The bump happens inside the
-- same transaction as the change it describes, so the version can never be
-- ahead of data that rolled back or behind data that committed -- a dual
-- write to a cache cannot promise that. T-2.5 mirrors this to Valkey for the
-- gateway's fast path; the row stays the source of truth.
CREATE TABLE authz_version (
    tenant_id  varchar(64) PRIMARY KEY,
    version    bigint      NOT NULL,
    updated_at timestamptz NOT NULL
);

-- The audit mechanism T-2.2 was told to use "the existing" version of. There
-- was none, so this is it, and E7's audited reads (T-7.10) and T-2.8's visible
-- impersonation extend this table rather than inventing a second one.
CREATE TABLE audit_log (
    id            uuid        PRIMARY KEY,
    tenant_id     varchar(64) NOT NULL,
    -- app_user.id, never a username and never a sub (ADR-0104): an audit trail
    -- a profile edit can rewrite is not an audit trail. NOT NULL, which is
    -- what confines runtime role editing to tenant-side callers for now --
    -- platform staff have no app_user row, and T-2.7 seeds their roles rather
    -- than editing them at runtime.
    actor_user_id uuid        NOT NULL REFERENCES app_user (id),
    action        varchar(64) NOT NULL,
    target_type   varchar(64) NOT NULL,
    target_id     uuid,
    -- Before and after, as the change actually was. jsonb rather than text so
    -- a compliance question can be a query instead of a grep.
    payload       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL
);

CREATE INDEX ix_audit_log_target ON audit_log (tenant_id, target_type, target_id);
CREATE INDEX ix_audit_log_created ON audit_log (tenant_id, created_at DESC);
