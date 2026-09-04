package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.identity.sso.TenantSso;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Home-provider discovery, for somebody who has not signed in yet (T-1.8).
 *
 * <p><b>The one unauthenticated endpoint in this service, and it has to be.</b> A learner types
 * their address on a sign-in page and needs to be sent to their company's provider; requiring a
 * token first would require the sign-in this exists to route. So the question is not how to gate
 * it but how to make it worth nothing to an attacker, and the answers are in {@code TenantSso}:
 * exact verified domains only, no listing, no prefix matching, and one shape of response whether
 * or not there is an answer.
 *
 * <p>POST rather than GET even though it reads nothing, because the address goes in the body: an
 * email in a query string is an email in access logs, in browser history and in a referrer header
 * — and it is somebody's, before they have agreed to anything.
 *
 * <p>The response never names the company. It carries the alias to redirect to and the label to
 * show, which is everything a login page needs, and nothing that turns this into a way to learn
 * who a domain belongs to beyond what the redirect would reveal anyway.
 */
@RestController
@RequestMapping("/api/v1/auth/discovery")
public class DiscoveryResource {

    private final TenantSso sso;

    public DiscoveryResource(TenantSso sso) {
        this.sso = sso;
    }

    public record DiscoveryRequest(String email) {}

    /**
     * @param provider    the realm alias to send the browser to, or null for "sign in normally"
     * @param displayName what to put on the button, or null
     */
    public record DiscoveryView(String provider, String displayName) {}

    @PostMapping
    public DiscoveryView discover(@RequestBody DiscoveryRequest request) {
        // Always 200 with the same two fields. A 404 for "no provider here" would make this a
        // yes/no oracle with a status code, which is the same leak in a tidier wrapper -- and it
        // would make a perfectly normal sign-in look like an error to the page.
        return sso.discover(request.email())
            .map(provider -> new DiscoveryView(provider.alias(), provider.displayName()))
            .orElseGet(() -> new DiscoveryView(null, null));
    }
}
