package com.xenopsoftware.learn.common.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * How a service calls another one (T-9.11), and the only supported way to do it.
 *
 * <p>Every call carries two credentials: this service's own, proving which service is calling,
 * and — when there is one — <b>the end user's token, forwarded unchanged</b>. Forwarded rather
 * than re-minted, because the callee must be able to verify the user itself; a claim this
 * service makes about who it is acting for would be a claim, and the third service in a chain
 * has no reason to believe it.
 *
 * <p>That unchanged forwarding is also what makes a three-service chain work at all: the token
 * that reaches the third service is the same one the person presented at the edge, so it does
 * not matter how many hops it crossed.
 */
@Component
public class ServiceCalls {

    private final ServiceTokens tokens;
    private final ServiceEndpoints endpoints;

    public ServiceCalls(ServiceTokens tokens, ServiceEndpoints endpoints) {
        this.tokens = tokens;
        this.endpoints = endpoints;
    }

    /**
     * A client pointed at another service, carrying this one's credentials and the caller's
     * identity if a person is behind the request.
     */
    public RestClient to(String service) {
        RestClient.Builder client = RestClient.builder()
            .baseUrl(endpoints.baseUrlOf(service))
            .defaultHeader(ServiceAuthenticationFilter.HEADER, "Bearer " + tokens.current());
        String userToken = forwardableUserToken();
        if (userToken != null) {
            client.defaultHeader("Authorization", "Bearer " + userToken);
        } else {
            // No person behind this call -- a scheduled job, a reconciliation. The service
            // presents its own token as the principal too, which is what makes "acting on its
            // own behalf" visible to the callee rather than indistinguishable from a user.
            client.defaultHeader("Authorization", "Bearer " + tokens.current());
        }
        return client.build();
    }

    private static String forwardableUserToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return null;
        }
        // A service token in the security context is not a user; forwarding it as one would
        // manufacture a person out of a machine.
        if (token.getToken().getClaimAsString("svc") != null) {
            return null;
        }
        return token.getToken().getTokenValue();
    }
}
