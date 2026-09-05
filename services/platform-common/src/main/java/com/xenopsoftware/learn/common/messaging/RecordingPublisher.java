package com.xenopsoftware.learn.common.messaging;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What runs when no broker is configured (T-9.8).
 *
 * <p>A developer with no NATS running still gets a working service: the outbox fills, the relay
 * drains it, and nothing is delivered anywhere. The same trade {@code FakeMediaProvider} makes for
 * video, and it warns for the same reason -- a green run against this proves the outbox works and
 * nothing whatsoever about the bus.
 *
 * <p>It still marks rows published, which is the honest behaviour: the row's job is to survive
 * until it has been handed to transport, and here transport is a log line. Leaving rows unpublished
 * instead would make the backlog metric alarm on every developer machine, and an alert that is
 * always firing is an alert nobody reads.
 */
public class RecordingPublisher implements MessagePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(RecordingPublisher.class);

    private final List<OutboxMessage> published = new CopyOnWriteArrayList<>();

    @PostConstruct
    void warnLoudly() {
        LOG.warn("NO MESSAGE BUS CONFIGURED (platform.messaging.nats-url is unset). Events are "
            + "written to the outbox, drained, and DELIVERED NOWHERE. Nothing that depends on an "
            + "event will happen.");
    }

    @Override
    public void publish(OutboxMessage message) {
        published.add(message);
        LOG.info("Outbox -> (nowhere) {} {} for {}", message.subject(), message.type(),
            message.tenantId());
    }

    @Override
    public boolean reachesABroker() {
        return false;
    }

    /** What this was asked to publish, for tests that assert the relay drained. */
    public List<OutboxMessage> published() {
        return List.copyOf(published);
    }
}
