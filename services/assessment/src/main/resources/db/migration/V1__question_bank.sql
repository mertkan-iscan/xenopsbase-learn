-- V1 — question banks, and the vocabularies selection draws against (T-6.1).
--
-- A bank is two things at once, and both are load-bearing:
--
--   1. THE UNIT OF AUTHORING PERMISSION. An author who writes safety questions
--      should not be editing the compliance exam, and "the whole tenant" is the
--      only other boundary available. A permission scoped to a bank needs the
--      bank to be a row with an id before anything can point a grant at it.
--
--   2. THE POPULATION RANDOM SELECTION DRAWS FROM (T-6.5). A section that says
--      "five medium questions about fire safety" is a query, and a query needs
--      somewhere to run. The alternative is an ad-hoc filter over every question
--      in the company, which is the same query with no name and no owner.
--
-- The second is the one that cannot be retrofitted. Permission boundaries can
-- be tightened later; a draw whose population was never a first-class thing has
-- no stable answer to "what was this test drawn from", and T-6.5 has to record
-- exactly that for every attempt.

CREATE TABLE question_bank (
    id          uuid         PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,

    name        varchar(256) NOT NULL,
    description text,

    -- PLATFORM BANKS, USING THE ANSWER T-5.1 ALREADY GAVE. A platform-owned
    -- bank offered to every tenant is not a special kind of row: it is an
    -- ordinary row in the platform's own reserved tenant (`__platform`, T-1.5)
    -- with this flag set, exactly as content_item.shared works.
    --
    -- So there is no nullable tenant_id anywhere. A nullable tenant column
    -- produces rows that match no filter and become invisible to the tenant
    -- that owns them, which is the failure TenantOwned exists to prevent.
    --
    -- Reading these from a customer's tenant is therefore a DELIBERATE
    -- cross-tenant read that the T-1.1 discriminator will not perform by
    -- accident -- see PlatformBanks, which passes the tenant by hand because
    -- @TenantId does not filter native SQL.
    shared      boolean      NOT NULL DEFAULT false,

    -- WHERE A COPY CAME FROM, AS A RECORD AND NOT AS A LINK.
    --
    -- Copying a platform bank produces an independent tenant bank: the source
    -- may later be edited, archived or withdrawn, and none of that may reach
    -- the copy. A foreign key would say the opposite -- it would make the
    -- source's lifecycle the copy's problem, and a customer's questions would
    -- become undeletable-by-us or, worse, silently changed under a published
    -- exam.
    --
    -- Deliberately unconstrained, and that is the point: it answers "where did
    -- this come from" for a human, and nothing joins on it.
    copied_from_bank_id uuid,

    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);

-- One bank per name per company, case-insensitively. Two banks called
-- "Fire safety" and "fire safety" is not a naming preference, it is two
-- populations a section could draw from with no way for an author to tell which
-- one they picked.
CREATE UNIQUE INDEX ux_question_bank_name ON question_bank (tenant_id, lower(name));

-- What every bank list screen asks.
CREATE INDEX ix_question_bank_tenant ON question_bank (tenant_id, lower(name));

-- What a customer's "browse the shared library" screen asks, across tenants.
-- Partial, because shared rows are a handful against every tenant's own.
CREATE INDEX ix_question_bank_shared ON question_bank (shared) WHERE shared;


-- ---------------------------------------------------------------------------
-- THE VOCABULARIES, AND WHY THEY ARE TABLES WHEN content_item's TAGS ARE NOT
-- ---------------------------------------------------------------------------
--
-- content_item.tags is a `text[]` with a GIN index and a comment saying a tag
-- there is "a label a human typed, not an entity with an identity". That is
-- correct for content and wrong here, and the difference is who reads them.
--
-- A content tag is read by a person filtering a list. A typo makes one item
-- harder to find, and the person tries again.
--
-- A question tag is read by a DRAW. "Five medium questions tagged fire-safety"
-- resolves against whatever rows match, so `fire-safety` and `fire safety` are
-- two populations, and an author who mistypes one does not get an error -- they
-- get a shorter exam, silently, and only after it has been sat. The vocabulary
-- is what turns that into a rejected write at authoring time.
--
-- Per tenant, because it is the customer's language. Seeded with nothing: an
-- empty vocabulary means an untagged bank, not a broken one.

CREATE TABLE bank_tag (
    -- A surrogate key rather than (tenant_id, tag), which is what the row is
    -- actually identified by. Hibernate's @TenantId stamps tenant_id itself and
    -- is not available to be half of a primary key, so the natural key becomes
    -- a unique index and every tenant-scoped table in this repository keeps the
    -- same shape.
    id         uuid         PRIMARY KEY,
    tenant_id  varchar(64)  NOT NULL,
    tag        varchar(64)  NOT NULL,
    created_at timestamptz  NOT NULL
);

-- The natural key. Case-insensitive for the same reason bank names are: a
-- vocabulary containing both `Fire-Safety` and `fire-safety` has stopped being
-- a vocabulary.
CREATE UNIQUE INDEX ux_bank_tag ON bank_tag (tenant_id, lower(tag));

CREATE TABLE bank_difficulty (
    id         uuid         PRIMARY KEY,
    tenant_id  varchar(64)  NOT NULL,
    code       varchar(32)  NOT NULL,

    -- DIFFICULTY IS ORDERED AND TAGS ARE NOT, which is the whole reason it is a
    -- second table rather than a reserved tag prefix.
    --
    -- A draw says "medium or harder" (T-6.5) and a report asks whether a
    -- question was harder than the one it replaced (T-7.7). Both need an order,
    -- and neither can get one from a string. The rank is the customer's own --
    -- three levels or seven, named in their language -- and only the ordering
    -- is ours.
    rank       smallint     NOT NULL,

    created_at timestamptz  NOT NULL
);

CREATE UNIQUE INDEX ux_bank_difficulty_code ON bank_difficulty (tenant_id, lower(code));

-- Two levels may not share a rank. "Medium and Moderate are both 2" makes
-- "medium or harder" ambiguous at exactly the boundary somebody chose it for.
CREATE UNIQUE INDEX ux_bank_difficulty_rank ON bank_difficulty (tenant_id, rank);
