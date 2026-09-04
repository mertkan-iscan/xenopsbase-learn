package com.xenopsoftware.learn.streaming.playback;

import com.xenopsoftware.learn.common.service.ServiceCalls;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A client for identity with timeouts on it (T-9.11 supplies the credentials; this adds the
 * bound).
 *
 * <p>Short timeouts, because these calls sit between a learner pressing play and the video
 * starting: the alternative to failing fast is a request thread parked on a service that is
 * already in trouble, and a learner watching a spinner instead of reading an error they could
 * retry.
 *
 * <p>One place rather than one per caller, so the two questions this service asks identity —
 * what may this person view, and who are they — cannot drift into having different patience.
 */
@Component
class IdentityCalls {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final ServiceCalls serviceCalls;

    IdentityCalls(ServiceCalls serviceCalls) {
        this.serviceCalls = serviceCalls;
    }

    RestClient client() {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(CONNECT_TIMEOUT);
        timeouts.setReadTimeout(READ_TIMEOUT);
        // mutate() keeps the credentials ServiceCalls attached -- this service's own, and the
        // caller's forwarded unchanged -- and adds only the timeouts a hot-path call needs.
        return serviceCalls.to("identity").mutate().requestFactory(timeouts).build();
    }
}
