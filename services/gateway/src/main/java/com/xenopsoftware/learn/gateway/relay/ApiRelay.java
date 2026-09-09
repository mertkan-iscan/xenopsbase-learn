package com.xenopsoftware.learn.gateway.relay;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * The relay: one origin in, three services out, and a token the browser never holds (T-10.2).
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It does not interpret anything. No body is parsed, no status is translated, no error is
 * rewritten. A gateway that understands the payloads it carries is a gateway that has to be
 * changed whenever a service changes, and the eight-module boundary ADR-0109 draws would then run
 * through this file.
 *
 * <h2>Three headers are dropped on purpose, and each is a hole if it is not</h2>
 *
 * <ul>
 *   <li><b>Authorization</b>, inbound. A caller must not be able to send their own token through
 *       the relay: the whole guarantee is that the token on the inward call is the one this
 *       process obtained for the session it verified. Forwarding a caller-supplied one would make
 *       the gateway an oracle that signs whatever it is handed.
 *   <li><b>Cookie</b>, inbound. The session cookie is this origin's and means nothing inward; the
 *       services are stateless resource servers. Sending it would put a credential in front of
 *       three processes that have no reason to see one.
 *   <li><b>Set-Cookie</b>, outbound. A resource server has no business setting a cookie on the
 *       app's origin, and relaying one would let any service write to the same jar that holds the
 *       session.
 * </ul>
 *
 * <p>The hop-by-hop headers go too, for the ordinary reason: they describe one connection and this
 * is two.
 */
@RestController
public class ApiRelay {

    /**
     * RFC 9110's hop-by-hop set, plus the two the servlet container owns.
     *
     * <p>{@code content-length} is recomputed from the body we actually write, and copying the
     * upstream's would be a promise about bytes that may no longer be true. {@code host} is the
     * upstream's to set.
     */
    private static final Set<String> NOT_FORWARDED = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
        "transfer-encoding", "upgrade", "host", "content-length",
        "authorization", "cookie", "set-cookie");

    private final Upstreams upstreams;
    private final AccessTokens tokens;
    private final RestClient http;

    public ApiRelay(Upstreams upstreams, AccessTokens tokens) {
        this.upstreams = upstreams;
        this.tokens = tokens;
        // RestClient.builder() rather than an injected RestClient.Builder: Boot 4 does not
        // auto-configure one, which the rest of this repository has already discovered twice.
        this.http = RestClient.builder().build();
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> relay(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String path = request.getRequestURI();
        String target = upstreams.forPath(path);
        if (target == null) {
            // A 404 rather than a default route: a default would mean every endpoint any service
            // ever adds is exposed here the moment it exists, which is the opposite of a routing
            // table being a decision.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No service answers " + path);
        }

        String query = request.getQueryString();
        URI uri = URI.create(target + path + (query == null ? "" : "?" + query));
        byte[] body = request.getInputStream().readAllBytes();

        RestClient.RequestBodySpec outbound = http
            .method(HttpMethod.valueOf(request.getMethod()))
            .uri(uri)
            .headers(headers -> copyInboundHeaders(request, headers));
        outbound.header(HttpHeaders.AUTHORIZATION,
            "Bearer " + tokens.forCurrentCaller(request, response));
        if (body.length > 0) {
            outbound.body(body);
        }

        // exchange rather than retrieve: a 4xx from a service is an answer to relay, not an
        // exception to translate. retrieve() would turn every refusal into a 500 from here, and
        // the disclosure rules (T-2.4) would be lost in the process.
        return outbound.exchange((inbound, answer) -> ResponseEntity
            .status(answer.getStatusCode())
            .headers(copyOutboundHeaders(answer.getHeaders()))
            .body(answer.getBody().readAllBytes()), false);
    }

    private void copyInboundHeaders(HttpServletRequest request, HttpHeaders headers) {
        for (String name : Collections.list(request.getHeaderNames())) {
            if (!NOT_FORWARDED.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                headers.addAll(name, Collections.list(request.getHeaders(name)));
            }
        }
    }

    private HttpHeaders copyOutboundHeaders(HttpHeaders upstream) {
        HttpHeaders copied = new HttpHeaders();
        upstream.forEach((name, values) -> {
            if (!NOT_FORWARDED.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                copied.addAll(name, List.copyOf(values));
            }
        });
        return copied;
    }
}
