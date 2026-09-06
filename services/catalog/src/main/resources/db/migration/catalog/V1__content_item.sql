-- V1 — content_item, the thing everything else points at (T-5.1).
--
-- ONE TABLE WITH A DISCRIMINATOR, and the reason is arithmetic. Structure
-- (T-5.2), assignment (T-5.5), gating (T-5.3), progress and reporting all have
-- to work against something that is not a video specifically. A table per type
-- means each of those subsystems grows a branch per type: five branches in four
-- subsystems is twenty places to forget one when a sixth type arrives, and the
-- one that gets forgotten is discovered by a customer.
--
-- What stays type-specific is the payload and the player. Nothing else.

CREATE TABLE content_item (
    id          uuid         PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,

    -- The discriminator, as a STRING rather than a Postgres enum. A Postgres
    -- enum needs a migration to add a value, which would make "adding a content
    -- type touches the payload validation and the player, and nothing else"
    -- false on the first try. The set of valid codes is owned by the type
    -- registry in code (ContentTypes), which is where a reader can see all of
    -- them at once.
    type        varchar(32)  NOT NULL,

    title       varchar(512) NOT NULL,
    description text,

    -- DRAFT, PUBLISHED or ARCHIVED. The transitions and what may reference each
    -- state are ContentState's, not this column's -- a CHECK constraint here
    -- would duplicate a rule that has to be readable by the code making the
    -- reference, and two copies of a rule is one copy that goes stale.
    state       varchar(16)  NOT NULL,

    -- The typed half. Validated per type on the way in, so a row cannot hold a
    -- payload its own type does not accept; jsonb rather than text so a
    -- question about payload contents is a query rather than a scan.
    payload     jsonb        NOT NULL DEFAULT '{}'::jsonb,

    -- text[] rather than a join table, deliberately. A tag here is a label a
    -- human typed, not an entity with an identity: it has no owner, no
    -- description and nothing hangs off it. A join table would buy renaming a
    -- tag everywhere at once, which is not a feature anybody asked for, at the
    -- cost of a join on every list screen.
    tags        text[]       NOT NULL DEFAULT '{}',

    -- SHARED CONTENT, DESIGNED HERE AND USED LATER (T-5.1's last criterion).
    -- A platform-owned catalog offered across tenants needs somewhere to live.
    -- It lives in the platform's own reserved tenant like every other
    -- platform-side row (T-1.5), and this flag is what marks a row as offered
    -- rather than merely ours. Nothing reads it yet.
    --
    -- Why a column now: the alternative is a migration on a populated table
    -- later, and the discipline this project keeps is that a designed-for
    -- future is a column and a comment, never a schema change under deadline.
    -- Reading these rows from a customer's tenant is a deliberate cross-tenant
    -- read, because the T-1.1 discriminator will not do it by accident.
    shared      boolean      NOT NULL DEFAULT false,

    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

-- What every list screen asks: this company's items, newest first.
CREATE INDEX ix_content_item_tenant ON content_item (tenant_id, updated_at DESC);

-- What the type-filtered views ask.
CREATE INDEX ix_content_item_type ON content_item (tenant_id, type, state);

-- Tag filtering, which is a containment question rather than an equality one.
CREATE INDEX ix_content_item_tags ON content_item USING gin (tags);

-- Search over title and description. A trigram index rather than a tsvector:
-- the query a person actually types is a fragment of a title ("onboard"), and
-- full-text search stems and tokenises in a way that misses exactly that.
-- Revisit when search stops being "find the thing I named" and starts being
-- "find things about a topic" -- those are different features.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_content_item_title_search ON content_item USING gin (title gin_trgm_ops);
