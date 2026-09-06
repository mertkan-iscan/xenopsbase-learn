-- V3 — permission: the catalog's database projection (T-2.1, ADR-0103).
--
-- The catalog itself is CODE -- the Permission enum -- because a permission is
-- only real if some code path checks it. This table exists so roles (T-2.2)
-- can reference permissions relationally and reports can join them; it is
-- written only by the startup seeder, never by an administrator.
--
-- No tenant_id, deliberately: the catalog is product truth, identical for
-- every tenant. What varies per tenant is roles and assignments (T-2.2,
-- T-2.3), not what is possible to permit.
--
-- orphaned: a code the enum no longer declares. Marked, never deleted --
-- deleting would break the roles that still reference it, silently retaining
-- it would keep a grantable permission nothing checks, which is the exact
-- failure this task exists to prevent.

CREATE TABLE permission (
    code       varchar(64) PRIMARY KEY,
    resource   varchar(32) NOT NULL,
    action     varchar(32) NOT NULL,
    side       varchar(16) NOT NULL,
    min_scope  varchar(16) NOT NULL,
    orphaned   boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
