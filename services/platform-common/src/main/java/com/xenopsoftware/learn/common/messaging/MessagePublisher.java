package com.xenopsoftware.learn.common.messaging;

/**
 * The broker, as the only thing the relay needs from it (T-9.8).
 *
 * <p>A port because the broker is transport and transport is replaceable, and because a developer
 * with no broker running must still get a working stack -- the same trade
 * {@code MediaProvider} makes for video.
 */
public interface MessagePublisher {

    /**
     * Publishes, or throws.
     *
     * <p>THROWING IS THE CONTRACT. A publisher that swallows a failure would let the relay mark a
     * row published that never left the building, and the outbox would then be a table of lies
     * that looks healthy. The relay is built to retry; it can only do that if it is told.
     */
    void publish(OutboxMessage message);

    /** Whether this really reaches a broker, so the relay can say so rather than pretend. */
    default boolean reachesABroker() {
        return true;
    }
}
