-- V6 — assignments, and the scope that is the whole point (T-2.3).
--
-- Holding group:read is not a permission to read groups; it is a permission to
-- read groups SOMEWHERE. Without the scope column the only expressible model is
-- company-wide, and a group admin who can see one department can see all of
-- them -- the single most likely way this product leaks data between
-- departments of the same customer.

CREATE TABLE role_assignment (
    id               uuid        PRIMARY KEY,
    tenant_id        varchar(64) NOT NULL,
    role_id          uuid        NOT NULL REFERENCES app_role (id) ON DELETE CASCADE,

    -- Who holds it: a person or a group, never both and never neither. Named user_id and
    -- group_id rather than subject_*: "subject" is the OIDC word for a sub, and a column that
    -- reads like a stored sub is one ADR-0104 guard rightly refuses to distinguish by intent. Two
    -- nullable columns with a check rather than one polymorphic id, so both
    -- ends keep a real foreign key -- an assignment to a user who was never
    -- created, or to a group that was deleted, cannot exist.
    user_id          uuid        REFERENCES app_user (id),
    group_id         uuid        REFERENCES user_group (id),

    -- PLATFORM, TENANT, GROUP or COURSE.
    scope_type       varchar(16) NOT NULL,
    -- The group or course the scope points at; null for TENANT and PLATFORM,
    -- which point at everything they can reach by definition.
    --
    -- No foreign key, and the reason differs per type: a GROUP scope id lives
    -- in user_group and IS validated in code, while a COURSE scope id belongs
    -- to catalog (T-5.2) -- another module, another database, so no constraint
    -- here could ever check it. Recorded rather than left to be discovered.
    scope_id         uuid,

    -- Who granted it. The audit log records the act; this records the standing
    -- fact, so "who gave them this" is a lookup instead of a log crawl.
    -- app_user.id, never a username (ADR-0104).
    granted_by       uuid        NOT NULL REFERENCES app_user (id),
    created_at       timestamptz NOT NULL,

    CONSTRAINT ck_role_assignment_subject
        CHECK (num_nonnulls(user_id, group_id) = 1),
    CONSTRAINT ck_role_assignment_scope
        CHECK ((scope_type IN ('TENANT', 'PLATFORM') AND scope_id IS NULL)
            OR (scope_type IN ('GROUP', 'COURSE') AND scope_id IS NOT NULL))
);

-- The same grant twice is not two grants. COALESCE because Postgres treats
-- NULLs as distinct, so without it a tenant-scoped assignment could be created
-- any number of times -- the same trap the group sibling-name index avoids.
CREATE UNIQUE INDEX uq_role_assignment ON role_assignment (
    role_id,
    COALESCE(user_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(group_id, '00000000-0000-0000-0000-000000000000'::uuid),
    scope_type,
    COALESCE(scope_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- The resolution path on every request: assignments for this person, and for
-- the groups they are in.
CREATE INDEX ix_role_assignment_user ON role_assignment (tenant_id, user_id);
CREATE INDEX ix_role_assignment_group ON role_assignment (tenant_id, group_id);
CREATE INDEX ix_role_assignment_role ON role_assignment (role_id);
