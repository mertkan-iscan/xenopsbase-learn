-- V11 — a customer brings their own identity provider, and their people land
-- in their company and nowhere else (T-1.8, ADR-0102).
--
-- THE WHOLE SECURITY ARGUMENT IS THE ALIAS COLUMN BELOW.
--
-- ADR-0102 put every company in one realm, so the realm can no longer answer
-- "which company is this person in" — one realm per customer would have. What
-- answers it instead is which provider authenticated them, and that is a fact
-- about our configuration rather than anything the provider asserts. A
-- customer's identity provider can put whatever it likes in an assertion,
-- including another company's slug; it cannot change which alias it is.

CREATE TABLE tenant_identity_provider (
    -- The Keycloak alias, and the primary key on purpose: an alias maps to
    -- exactly one company, globally, and the database is what makes that true
    -- rather than a check somebody remembers to write. Two rows claiming one
    -- alias would be the ambiguity the whole design exists to remove.
    alias        varchar(64)  PRIMARY KEY,
    tenant_id    varchar(64)  NOT NULL,
    -- OIDC or SAML. Recorded because the two need different configuration and
    -- an operator reading this table should not have to infer which is which.
    kind         varchar(16)  NOT NULL,
    -- What a learner sees on the button, if they ever see one. Discovery means
    -- they usually do not.
    display_name varchar(128) NOT NULL,
    -- Whether the realm has actually been made to match this row. A row written
    -- here and never applied would be a company that believes it has SSO, so
    -- this is not derived at read time: it records that the apply happened.
    applied_at   timestamptz,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,

    CONSTRAINT ck_provider_kind CHECK (kind IN ('OIDC', 'SAML'))
);

CREATE INDEX ix_tenant_identity_provider_tenant ON tenant_identity_provider (tenant_id);

-- Home-provider discovery: an email domain a company has PROVED it owns.
CREATE TABLE tenant_email_domain (
    id                 uuid         PRIMARY KEY,
    tenant_id          varchar(64)  NOT NULL,
    -- Stored lowercase; the unique index below is what enforces it in practice.
    domain             varchar(253) NOT NULL,
    -- The value that must appear in a DNS TXT record for the domain. Generated
    -- here, never chosen by the claimant: a token the claimant picks proves
    -- they can publish a string they already knew.
    verification_token varchar(64)  NOT NULL,
    verified_at        timestamptz,
    created_at         timestamptz  NOT NULL,

    CONSTRAINT uq_tenant_email_domain UNIQUE (tenant_id, domain)
);

-- ONE VERIFIED OWNER PER DOMAIN, and unverified claims deliberately unconstrained.
--
-- The other way round is worse in a way that is easy to miss: a global unique
-- index over all claims lets anybody with an account reserve `bigcorp.com`
-- before Bigcorp signs up, and the only remedy is a support ticket. Here a
-- squatter's claim costs them a row and blocks nothing, because the thing that
-- confers ownership is a DNS record they cannot publish.
CREATE UNIQUE INDEX uq_tenant_email_domain_verified
    ON tenant_email_domain (lower(domain)) WHERE verified_at IS NOT NULL;

CREATE INDEX ix_tenant_email_domain_lookup ON tenant_email_domain (lower(domain));
