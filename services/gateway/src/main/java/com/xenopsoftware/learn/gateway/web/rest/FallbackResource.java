package com.xenopsoftware.learn.gateway.web.rest;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * What a caller is told when the edge cannot complete a request itself (T-9.17).
 *
 * <p>Both answers here are machine-readable for the reason {@code AccountStatus.refusalBody} gives:
 * a UI has to say something true, and it cannot branch on prose.
 */
@RestController
public class FallbackResource {

    /**
     * A downstream service is failing or slow, and the circuit breaker opened.
     *
     * <p>503 with {@code Retry-After} rather than 500: this is a statement about the platform's
     * current state, not about the caller's request, and the difference decides whether a client
     * retries or gives up. The learner-facing consequence is deliberately narrow — ADR-0101 puts
     * video on Cloudflare's edge, so a backend outage stops the console and not playback, which is
     * the property T-3.10 exists to prove.
     */
    @RequestMapping("/fallback")
    public Mono<ResponseEntity<Map<String, Object>>> downstreamUnavailable() {
        return Mono.just(ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "10")
            .body(Map.of("error", Map.of(
                "code", "SERVICE_UNAVAILABLE",
                "message", "That part of the platform is not responding. Try again shortly."))));
    }

    /**
     * A path this gateway deliberately refuses to guess at.
     *
     * <p>Today that is exactly one: {@code /api/v1/assignments/**}, which identity and catalog
     * both claim and which mean different things in each (role assignments against content
     * assignments). ADR-0109's merge of the two into {@code core} is what resolves it — one
     * process, and Spring matches on the full path.
     *
     * <p><b>501 rather than 404, and the distinction is the point.</b> A 404 would say the
     * resource does not exist, which is false: it exists twice. Answering 501 with both owners
     * named turns a routing gap into something an operator can read in one response, instead of
     * a mystery where half the calls reach a service that returns 404 for a row it does not have.
     */
    @RequestMapping("/collision")
    public Mono<ResponseEntity<Map<String, Object>>> pathClaimedByTwoServices() {
        return Mono.just(ResponseEntity
            .status(HttpStatus.NOT_IMPLEMENTED)
            .body(Map.of("error", Map.of(
                "code", "ROUTE_AMBIGUOUS",
                "message", "This path is claimed by two services and cannot be routed until they "
                    + "share a process. See ADR-0109 (core) and ADR-0111.",
                "claimedBy", java.util.List.of("identity", "catalog")))));
    }
}
