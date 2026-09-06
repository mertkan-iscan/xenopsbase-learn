-- V10 — support impersonation, and the record that makes it visible afterwards (T-2.8).
--
-- The row lives in the CUSTOMER's tenant, not the platform's. That is the whole
-- design in one column: the customer's own administrators must be able to read
-- what was done to their account (T-2.8's fifth criterion), and a record kept
-- only in our tenant would make "show me every time your staff entered my
-- account" a support request instead of a query the customer runs themselves.
--
-- It also means the tenant discriminator (T-1.1) does the disclosure work: a
-- tenant admin listing sessions sees theirs and, structurally, nobody else's.

CREATE TABLE impersonation_session (
    id                   uuid         PRIMARY KEY,
    tenant_id            varchar(64)  NOT NULL,
    -- The support engineer, as an app_user in the platform tenant (ADR-0104
    -- applies to staff too, which is what makes this a real foreign key rather
    -- than a name copied out of a token). A cross-tenant reference on purpose:
    -- the actor genuinely is not a member of the tenant they are entering, and
    -- pretending otherwise by copying them in would create a customer-visible
    -- account nobody granted.
    actor_user_id        uuid         NOT NULL REFERENCES app_user (id),
    -- The customer's user whose view is being reproduced.
    impersonated_user_id uuid         NOT NULL REFERENCES app_user (id),
    -- Recorded at the start, never nullable, never blank. A session with no
    -- stated reason is indistinguishable from account takeover after the fact,
    -- which is the failure this whole table exists to prevent.
    reason               varchar(512) NOT NULL,
    -- Read-only unless someone deliberately held a SECOND permission when the
    -- session started. Stored on the row rather than derived at request time:
    -- what the session was allowed to do must stay answerable after the grant
    -- that allowed it has been revoked.
    writable             boolean      NOT NULL DEFAULT false,
    started_at           timestamptz  NOT NULL,
    -- Time-boxed at the start (T-2.8's second criterion). Not a policy applied
    -- at read time -- a stored expiry keeps a session that was cut short by a
    -- shorter policy from silently coming back to life when the policy widens.
    expires_at           timestamptz  NOT NULL,
    -- Set when it ends early, by the actor or by a refusal.
    ended_at             timestamptz,
    ended_reason         varchar(64),

    CONSTRAINT ck_impersonation_window CHECK (expires_at > started_at),
    -- Nobody impersonates themselves: the record would be indistinguishable
    -- from ordinary activity, which defeats the point of recording it.
    CONSTRAINT ck_impersonation_distinct CHECK (actor_user_id <> impersonated_user_id)
);

-- What a tenant admin's screen asks: this company's sessions, newest first.
CREATE INDEX ix_impersonation_session_tenant
    ON impersonation_session (tenant_id, started_at DESC);

-- What the per-request check asks: is this session still live. Partial, because
-- an ended session is never looked up this way and the live set is tiny.
CREATE INDEX ix_impersonation_session_live
    ON impersonation_session (actor_user_id) WHERE ended_at IS NULL;

-- The audit log GAINS the second identity rather than growing a second table,
-- exactly as V5 said it would. actor_user_id keeps its meaning -- the human who
-- caused this -- and stays the support engineer during a session, because the
-- whole point is that their actions are not attributed to the customer.
ALTER TABLE audit_log
    ADD COLUMN impersonated_user_id     uuid REFERENCES app_user (id),
    ADD COLUMN impersonation_session_id uuid REFERENCES impersonation_session (id);

-- "What happened while your staff were in my account", as one indexed read.
CREATE INDEX ix_audit_log_impersonation
    ON audit_log (tenant_id, impersonation_session_id)
 WHERE impersonation_session_id IS NOT NULL;
