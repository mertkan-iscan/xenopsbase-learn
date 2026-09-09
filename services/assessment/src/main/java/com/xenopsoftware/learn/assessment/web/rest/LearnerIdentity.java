package com.xenopsoftware.learn.assessment.web.rest;

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
 * Who is sitting the test, as {@code app_user.id} (T-6.6, ADR-0104).
 *
 * <p>An attempt is keyed by the durable id and never by an IdP subject: a sub is a link identity
 * may repair (T-1.7), and an exam result that quietly stops pointing at anybody is worse than none.
 * Assessment cannot turn a token into that id by itself — identity owns the person and this module
 * may not read its schema (ADR-0109) — so it asks, through T-9.11's service-to-service credential.
 *
 * <p><b>This is the third copy of this class</b>, after catalog's {@code LearnerIdentity} and
 * streaming's {@code ViewerIdentities}, and that is worth saying out loud rather than leaving for
 * somebody to notice. It is not shareable today: {@code platform-common} carries {@code
 * ServiceCalls} but not a resolver, and putting one there would give every service a bean that
 * calls identity whether it needs one or not. The place it belongs is ADR-0109's {@code core}
 * merge, where identity is in the same process and the call is a method.
 *
 * <p>Cached for fifteen minutes, bounded, exactly as the other two are — the answer changes about
 * once in a person's employment, and without a cache this would be a hop to identity on every
 * saved answer, which during an exam is one per question per learner.
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
     * and later will work, which is what somebody sitting an exam needs to be told. A 500 would say
     * "unknown" about something known, and a 4xx would tell a learner their own exam is their
     * fault.
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
            LOG.warn("Could not resolve the caller through identity; they cannot sit a test right "
                + "now.", identityUnreachable);
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
