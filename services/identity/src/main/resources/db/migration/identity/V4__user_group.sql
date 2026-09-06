-- V4 — the group tree and its membership (T-1.3).
--
-- ADJACENCY LIST, deliberately, and the reason is acceptance criterion four:
-- moving a group must re-parent its subtree without rewriting membership rows.
-- Here a move is ONE row update -- no descendant rows, no membership rows, no
-- path strings. A closure table or a materialised path would make the
-- descendant query trivial and the move O(subtree); this project moves groups
-- (reorganisations) and reads descendants (every authorization check), so the
-- read cost is measured rather than assumed, in GroupHierarchyBenchmarkTest.
--
-- No depth column. Depth is a WRITE-TIME rule enforced by walking ancestors,
-- which keeps a move a single-row update; the recursive queries additionally
-- carry a hard depth cap, so even data that somehow held a cycle cannot
-- produce the infinite walk the criterion warns about.

CREATE TABLE user_group (
    id         uuid         PRIMARY KEY,
    tenant_id  varchar(64)  NOT NULL,
    parent_id  uuid         REFERENCES user_group (id),
    name       varchar(255) NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL
);

CREATE INDEX ix_user_group_tenant ON user_group (tenant_id);
-- The descendant walk's join column: every recursive step is a lookup by parent.
CREATE INDEX ix_user_group_parent ON user_group (parent_id);

-- Siblings cannot share a name, case-insensitively. Roots included, which is
-- what the COALESCE is for: NULL parents would otherwise all be distinct and
-- a tenant could hold five root groups called "Engineering".
CREATE UNIQUE INDEX uq_user_group_sibling_name
    ON user_group (tenant_id, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));

CREATE TABLE group_membership (
    id         uuid        PRIMARY KEY,
    tenant_id  varchar(64) NOT NULL,
    group_id   uuid        NOT NULL REFERENCES user_group (id),
    -- A REAL foreign key, because app_user is this module's own table in this
    -- module's own database. A reference to a person in ANOTHER module is an
    -- app_user.id with no FK (ADR-0104); here the boundary is not crossed, so
    -- the database can enforce it.
    user_id    uuid        NOT NULL REFERENCES app_user (id),
    created_at timestamptz NOT NULL,

    -- A user may be in several groups; twice in one group is not membership,
    -- it is a duplicate.
    CONSTRAINT uq_group_membership UNIQUE (group_id, user_id)
);

CREATE INDEX ix_group_membership_group ON group_membership (group_id);
CREATE INDEX ix_group_membership_user ON group_membership (user_id);
