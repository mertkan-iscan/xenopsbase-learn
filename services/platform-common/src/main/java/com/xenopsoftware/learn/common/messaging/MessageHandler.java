package com.xenopsoftware.learn.common.messaging;

/**
 * Something that reacts to an event (T-9.8).
 *
 * <p>A bean per subject. The subscriber finds them, claims each message once
 * ({@link ConsumedMessages}) and calls {@link #handle} inside a transaction with the tenant and the
 * correlation id bound — so a handler reads like ordinary domain code rather than like plumbing.
 */
public interface MessageHandler {

    /** The subject subscribed to. May be a NATS wildcard, as in {@code identity.group.*}. */
    String subject();

    /**
     * Reacts.
     *
     * <p>Runs inside the transaction that also marks the message consumed, so throwing rolls both
     * back and the message is redelivered. That is the correct behaviour for a transient failure
     * and the reason the mark is not committed separately.
     */
    void handle(OutboxMessage message);
}
