-- V2 — app_user: the identity every other table, in every service, references
-- (T-1.2, ADR-0104).
--
-- id is OURS, generated here, never by Keycloak. idp_sub is the one place a
-- Keycloak sub is stored in this platform: nullable, unique, and repairable by
-- one UPDATE (docs/runbooks/identity.md). A schema test fails the build if a
-- column shaped like a sub ever appears anywhere else.
--
-- tenant_id NOT NULL, like every tenant-scoped table (T-1.1): a nullable
-- tenant column produces rows that match no filter and become invisible to
-- the tenant that owns them. That rule is also enforced by a schema test.

CREATE TABLE app_user (
    id           uuid         PRIMARY KEY,
    tenant_id    varchar(64)  NOT NULL,
    email        varchar(320) NOT NULL,
    display_name varchar(255) NOT NULL,
    -- ACTIVE on first-login provisioning. INVITED and DEACTIVATED arrive with
    -- T-1.9, which owns the lifecycle; the column shape is set here so T-1.9
    -- is an enum value and a transition, not a migration of populated rows.
    status       varchar(16)  NOT NULL,
    idp_sub      varchar(255),
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,

    -- Subs are unique per realm, so the link is unique globally, not per
    -- tenant. Postgres treats NULLs as distinct, which is exactly right:
    -- any number of not-yet-linked users, at most one owner per sub.
    CONSTRAINT uq_app_user_idp_sub UNIQUE (idp_sub)
);

-- The re-link procedure's matching key (ADR-0104): at most one row per
-- verified email within a tenant, case-insensitively. Also what makes an
-- email collision at provisioning a loud conflict instead of a second row.
CREATE UNIQUE INDEX uq_app_user_tenant_email ON app_user (tenant_id, lower(email));
