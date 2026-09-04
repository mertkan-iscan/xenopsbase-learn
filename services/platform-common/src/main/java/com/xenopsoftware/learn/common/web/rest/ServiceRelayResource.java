package com.xenopsoftware.learn.common.web.rest;

import com.xenopsoftware.learn.common.service.ServiceCalls;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves a chain end to end: this service calls another one carrying the caller's identity, and
 * reports what that service saw (T-9.11).
 *
 * <p><b>Off unless an operator turns it on.</b> Everything else here answers a question about
 * the request that arrived; this one makes the service ORIGINATE a call, which is a small
 * amplifier and an operator's tool rather than a learner's. Enabling it is a deliberate act
 * during an investigation.
 *
 * <p>Its target is a configured NAME, never a URL from the caller: a relay that forwards to an
 * arbitrary address is a request-forgery hole with a diagnostic label on it.
 */
@RestController
@RequestMapping("/api/v1/internal")
@ConditionalOnProperty(name = "platform.diagnostics.relay", havingValue = "true")
public class ServiceRelayResource {

    private final ServiceCalls calls;

    public ServiceRelayResource(ServiceCalls calls) {
        this.calls = calls;
    }

    /**
     * Calls the named service and returns what it saw.
     *
     * <p>With {@code next}, it asks that service to relay onward — which is how a chain of
     * three or more is proved rather than asserted: the identity that arrives at the far end is
     * the one the person presented at the edge, whatever it crossed on the way. Both hops are
     * configured names, so the chain can only run between services an operator named.
     */
    @GetMapping("/relay/{service}")
    public Map<String, Object> relay(@PathVariable String service,
            @RequestParam(name = "next", required = false) String next) {
        String path = next == null || next.isBlank()
            ? "/api/v1/internal/whoami"
            : "/api/v1/internal/relay/" + next;
        Map<?, ?> downstream = calls.to(service).get()
            .uri(path)
            .retrieve()
            .body(Map.class);
        return Map.of("relayedTo", service, "sawThere", downstream);
    }
}
