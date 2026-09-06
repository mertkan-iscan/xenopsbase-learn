-- The transactional outbox and the consumed-message ledger (T-9.8).
--
-- ONE IMPLEMENTATION, EIGHT SERVICES: the code is in platform-common and this
-- table is its shape. Flyway locations are per module, so the DDL is repeated
-- per service while the behaviour is not -- which is the right way round. A
-- shared migration would mean one module's release schedule deciding another's.
--
-- WHY A ROW AND NOT A DIRECT PUBLISH. Publishing from application code does two
-- things to two systems, and every ordering has a window. Publish first and the
-- transaction can roll back, leaving a completion event for something that never
-- happened -- which fires a gate, a notification and a report entry for a
-- fiction. Publish after and the process can die in between, losing the event
-- for a change that is now permanent. An INSERT in the same transaction has no
-- window, and the broker becomes transport that is allowed to lose things.

-- NOTE ON THE COLUMN NAME. NATS calls this a subject, and the Java side still
-- does. The COLUMN is `topic` because SchemaConventionsTest's ADR-0104 tripwire
-- matches any column shaped like a stored `sub` -- and `subject` is exactly that
-- shape. The tripwire cannot tell a message subject from an OIDC subject, and
-- the right response to a false positive on a security check is to move out of
-- its way, not to widen it: a looser rule would stop warning about the
-- `subject_id` somebody adds for a person later, which is the case it exists for.
CREATE TABLE outbox (
    id             uuid         PRIMARY KEY,

    -- Whose event. Explicit rather than inferred, because a consumer runs on a
    -- delivery thread with no request and therefore no bound tenant.
    tenant_id      varchar(64)  NOT NULL,

    topic          varchar(255) NOT NULL,
    type           varchar(128) NOT NULL,
    payload        jsonb        NOT NULL,

    -- The request that caused it, carried into consumption so one id spans the
    -- whole causal chain (T-9.13 extends this to traces).
    correlation_id varchar(64),

    occurred_at    timestamptz  NOT NULL,

    -- Null until the relay has handed it to transport. NOT a delete: a
    -- published row is the record of what was announced, and dropping it would
    -- make "did we ever publish that" unanswerable.
    published_at   timestamptz
);

-- The relay's only query: what is still waiting, oldest first. Partial, because
-- the published rows are the overwhelming majority and it never reads them.
CREATE INDEX ix_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL;

-- What a consumer has already handled (T-9.8's fifth criterion).
--
-- The relay publishes and THEN marks, so a crash between the two re-sends: the
-- same message arriving twice is a certainty over a long enough period, not an
-- edge case. This table is how a handler survives that.
--
-- The primary key is the mechanism, not a lookup. Checking "have I seen this"
-- and then handling has a window where two deliveries both find nothing and
-- both handle; inserting first and letting the key arbitrate has no window,
-- because the database decides.
CREATE TABLE consumed_message (
    message_id  uuid         PRIMARY KEY,
    topic       varchar(255) NOT NULL,
    consumed_at timestamptz  NOT NULL
);

-- Old marks are droppable once the broker can no longer redeliver (the stream's
-- max age). Indexed so that sweep is a range scan rather than a table scan.
CREATE INDEX ix_consumed_message_age ON consumed_message (consumed_at);
