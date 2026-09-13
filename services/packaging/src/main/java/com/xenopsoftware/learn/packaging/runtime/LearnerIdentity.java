package com.xenopsoftware.learn.packaging.runtime;

import com.xenopsoftware.learn.common.service.ServiceCalls;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Who is asking, as {@code app_user.id} (T-4.4, ADR-0104).
 *
 * <p>The same class catalog and streaming each carry, for the same reason and with the same
 * shape: a runtime row is about the caller and nobody else, so it takes no learner id — the
 * identity comes from the token. This module cannot turn a token into a durable id by itself,
 * because identity owns the person and this schema may not be read from here (ADR-0109), so it
 * asks, through the service-to-service credential T-9.11 established.
 *
 * <p><b>The third copy, and that is worth naming rather than leaving for somebody to discover.</b>
 * It is duplicated rather than shared because the natural home would be platform-common, and
 * putting a cache of identity answers in the library every service scans would give every service
 * one whether or not it should have one. When a fourth appears, that is the argument to revisit —
 * three near-identical files is the signal, not yet the fix.
 *
 * <p><b>Cached, because a SCORM package commits often.</b> A conformant course calls
 * {@code LMSCommit} at every slide boundary, so without this every one is a hop to identity to
 * re-learn an answer that changes about once in a person's employment.
 *
 * <p>What is <b>not</b> cached is anything about permission, status or membership. Those are
 * decided per request, and a cache here would quietly extend them.
 */
@Component
public class LearnerIdentity {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerIdentity.class);

    /** Long enough to make the call rare, short enough that T-1.7's relink lands the same day. */
    private static final Duration TTL = Duration.ofMinutes(15);

    /** Bounded: an unbounded map keyed by whatever a caller presents is a leak wearing a cache. */
    private static final int MAX_ENTRIES = 20_000;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private record Me(UUID id) {}

    private record Known(UUID id, Instant expiresAt) {}

    private final Map<String, Known> byCaller = new ConcurrentHashMap<>();
    private final ServiceCalls serviceCalls;
    private final Clock clock;

    public LearnerIdentity(ServiceCalls serviceCalls, Clock clock) {
        this.serviceCalls = serviceCalls;
        this.clock = clock;
    }

    /**
     * The caller's durable id, or empty when identity could not be asked.
     *
     * <p>Empty rather than an exception, and the caller turns it into a 503: the request is fine
     * and later will work, which is what a client needs to be told. A 500 would say "unknown"
     * about something known, and a 4xx would tell a learner their own course is their fault.
     */
    public Optional<UUID> current(String tenantId, String subject) {
        String key = tenantId + " " + subject;
        Known known = byCaller.get(key);
        if (known != null && known.expiresAt().isAfter(clock.instant())) {
            return Optional.of(known.id());
        }
        try {
            Me me = client().get().uri("/api/v1/me").retrieve().body(Me.class);
            if (me == null || me.id() == null) {
                return Optional.empty();
            }
            if (byCaller.size() >= MAX_ENTRIES) {
                byCaller.clear();
            }
            byCaller.put(key, new Known(me.id(), clock.instant().plus(TTL)));
            return Optional.of(me.id());
        } catch (RestClientException identityUnreachable) {
            LOG.warn("Could not resolve the caller through identity; their place in this package "
                + "cannot be read or saved right now.", identityUnreachable);
            return Optional.empty();
        }
    }

    /** Forget everything. Exists for tests that change who identity answers with. */
    public void forget() {
        byCaller.clear();
    }

    private RestClient client() {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(CONNECT_TIMEOUT);
        timeouts.setReadTimeout(READ_TIMEOUT);
        // mutate() keeps the credentials ServiceCalls attached -- this service's own and the
        // caller's, forwarded unchanged -- and adds only the timeouts a hot path needs.
        return serviceCalls.to("identity").mutate().requestFactory(timeouts).build();
    }
}
