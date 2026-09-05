package com.xenopsoftware.learn.catalog.home;

import com.xenopsoftware.learn.common.service.ServiceCalls;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Who is asking, as {@code app_user.id} (T-5.8, ADR-0104).
 *
 * <p>The home screen is about the caller and nobody else, so it takes no learner id: the identity
 * comes from the token. Catalog cannot turn a token into a durable id by itself — identity owns the
 * person and this module may not read its schema (ADR-0109) — so it asks, through the
 * service-to-service credential T-9.11 established.
 *
 * <p><b>Cached, because this is the most-hit authenticated read in the product.</b> Without it,
 * every home screen is a hop to identity to re-learn an answer that changes about once in a
 * person's employment. Fifteen minutes in the process, bounded, and exactly the shape
 * {@code streaming}'s {@code ViewerIdentities} already uses for the heartbeat path — the same
 * problem, so the same answer rather than a second one.
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
    private final java.time.Clock clock;

    public LearnerIdentity(ServiceCalls serviceCalls, java.time.Clock clock) {
        this.serviceCalls = serviceCalls;
        this.clock = clock;
    }

    /**
     * The caller's durable id, or empty when identity could not be asked.
     *
     * <p>Empty rather than an exception, and the caller turns it into a 503: the request is fine
     * and later will work, which is what a client needs to be told. A 500 would say "unknown" about
     * something known, and a 4xx would tell a learner their own screen is their fault.
     */
    public java.util.Optional<UUID> current(String tenantId, String subject) {
        String key = tenantId + " " + subject;
        Known known = byCaller.get(key);
        if (known != null && known.expiresAt().isAfter(clock.instant())) {
            return java.util.Optional.of(known.id());
        }
        try {
            Me me = client().get().uri("/api/v1/me").retrieve().body(Me.class);
            if (me == null || me.id() == null) {
                return java.util.Optional.empty();
            }
            if (byCaller.size() >= MAX_ENTRIES) {
                byCaller.clear();
            }
            byCaller.put(key, new Known(me.id(), clock.instant().plus(TTL)));
            return java.util.Optional.of(me.id());
        } catch (RestClientException identityUnreachable) {
            LOG.warn("Could not resolve the caller through identity; the home screen cannot be "
                + "built for them right now.", identityUnreachable);
            return java.util.Optional.empty();
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
