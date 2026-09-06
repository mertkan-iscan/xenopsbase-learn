-- The transactional outbox and the consumed-message ledger (T-9.8), now that
-- this service has something to announce: a learner finished something (T-3.7).
--
-- ONE IMPLEMENTATION, A TABLE PER PUBLISHER. The code is in platform-common and
-- this is its shape; Flyway locations are per module, so the DDL repeats while
-- the behaviour does not. A shared migration would put one module's release
-- schedule in charge of another's.
--
-- WHY A ROW AND NOT A DIRECT PUBLISH. Publishing from application code does two
-- things to two systems and every ordering has a window. Publish first and the
-- transaction can roll back, leaving a completion event for something that never
-- happened -- which fires a gate and a report entry for a fiction. Publish after
-- and the process can die in between, losing the event for a change that is now
-- permanent. An INSERT in the same transaction has no window, and the broker
-- becomes transport that is allowed to lose things.

-- The column is `topic` rather than `subject` for the reason catalog's copy
-- states: StreamingAppTest's ADR-0104 tripwire matches any column shaped like a
-- stored `sub`, and `subject` is exactly that shape. Moving out of the way of a
-- false positive is better than widening the rule that catches the real thing.
CREATE TABLE outbox (
    id             uuid         PRIMARY KEY,

    -- Whose event. Explicit rather than inferred, because a consumer runs on a
    -- delivery thread with no request and therefore no bound tenant.
    tenant_id      varchar(64)  NOT NULL,

    topic          varchar(255) NOT NULL,
    type           varchar(128) NOT NULL,
    payload        jsonb        NOT NULL,

    correlation_id varchar(64),
    occurred_at    timestamptz  NOT NULL,

    -- Null until the relay has handed it to transport. NOT a delete: a published
    -- row is the record of what was announced.
    published_at   timestamptz
);

CREATE INDEX ix_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL;

-- What a consumer here has already handled. This service publishes and does not
-- yet subscribe, and the table is created anyway: the ledger belongs to the bus
-- rather than to a direction, and a service that grows its first handler should
-- not also be growing a migration under whatever deadline produced the handler.
CREATE TABLE consumed_message (
    message_id  uuid         PRIMARY KEY,
    topic       varchar(255) NOT NULL,
    consumed_at timestamptz  NOT NULL
);

CREATE INDEX ix_consumed_message_age ON consumed_message (consumed_at);
