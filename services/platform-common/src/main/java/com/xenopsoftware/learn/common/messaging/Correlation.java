package com.xenopsoftware.learn.common.messaging;

import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * The id that ties a request to everything it caused (T-9.8's fourth criterion).
 *
 * <p>Carried onto every outbox row, onto the message, and restored when a consumer handles it, so
 * one id spans "a person clicked a button" through "three services reacted". Without it, tracing a
 * consequence back to its cause means matching timestamps across four logs.
 *
 * <p>Also placed in the logging {@link MDC}, so every line a request or a delivery produces
 * carries it without anybody having to remember to log it.
 *
 * <p><b>The minimum, deliberately.</b> T-9.13 owns real distributed tracing — spans, a propagation
 * format, a collector. This is the one field that has to exist before there is anything to
 * propagate, and it is built here rather than waiting because an outbox row written today without
 * it can never be correlated retrospectively.
 */
public final class Correlation {

    /** The header a caller may set to join an existing chain, and that this echoes. */
    public static final String HEADER = "X-Correlation-Id";

    /** The MDC key, so a log pattern can name it. */
    public static final String MDC_KEY = "correlationId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Correlation() {}

    /** The id on this thread, or a fresh one -- never null, so a caller never has to check. */
    public static String current() {
        String id = CURRENT.get();
        return id == null ? UUID.randomUUID().toString() : id;
    }

    /** Whether this thread is already part of a chain. */
    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    /**
     * Runs {@code body} under this id, restoring whatever was bound before.
     *
     * <p>The only way in, for the reason TenantContext gives: threads are pooled, and an id left
     * bound would silently attribute the next request on that thread to the previous one's cause.
     */
    public static <T> T callWith(String correlationId, Supplier<T> body) {
        String previous = CURRENT.get();
        String previousMdc = MDC.get(MDC_KEY);
        CURRENT.set(correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
                MDC.remove(MDC_KEY);
            } else {
                CURRENT.set(previous);
                MDC.put(MDC_KEY, previousMdc);
            }
        }
    }
}
