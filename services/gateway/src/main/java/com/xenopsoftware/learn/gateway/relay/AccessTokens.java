package com.xenopsoftware.learn.gateway.relay;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * A token for the person who is calling, refreshed before it expires and never given to them
 * (T-10.2).
 *
 * <h2>Silent refresh is not a feature here, it is the absence of one</h2>
 *
 * <p>The frontend has no refresh logic, no expiry timer and no retry-on-401-because-of-expiry,
 * because it has no token to expire. {@link OAuth2AuthorizedClientManager} notices that the stored
 * access token is within the clock skew of its expiry and exchanges the refresh token for a new
 * one before the call goes out — server-side, on the request that needed it, invisible to the
 * browser.
 *
 * <p>That is what makes the forty-minute exam safe. The failure T-10.2 names — a submission lost
 * because a token expired thirty seconds ago — cannot be produced by expiry at all here: expiry is
 * handled a layer below the request, and the request simply carries a fresh token.
 *
 * <p>What CAN still end is the session itself: the refresh token expires, an administrator revokes
 * it, or Keycloak's SSO session ends. That is a real state and it gets its own exception, because
 * the browser's correct response to it — hold the work, sign in again, replay — is completely
 * different from its response to a permission refusal.
 */
@Component
public class AccessTokens {

    private final OAuth2AuthorizedClientManager clients;

    public AccessTokens(OAuth2AuthorizedClientManager clients) {
        this.clients = clients;
    }

    /**
     * The current caller's access token.
     *
     * <p>The request and response are handed to the manager because refreshing may need them —
     * the authorized-client repository is servlet-scoped, and a refresh that could not write the
     * new token back would refresh on every single call.
     */
    public String forCurrentCaller(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken signedIn)) {
            throw new SessionEndedException("There is no signed-in session on this request.");
        }

        OAuth2AuthorizedClient client;
        try {
            client = clients.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId(signedIn.getAuthorizedClientRegistrationId())
                .principal(signedIn)
                .attribute(HttpServletRequest.class.getName(), request)
                .attribute(HttpServletResponse.class.getName(), response)
                .build());
        } catch (ClientAuthorizationException refusedByTheIssuer) {
            // The refresh token is gone: expired, revoked, or its SSO session ended. Not an error
            // to log as a fault -- it is the ordinary end of a working day.
            throw new SessionEndedException("The session has ended and could not be renewed.");
        }

        if (client == null || client.getAccessToken() == null) {
            throw new SessionEndedException("The session holds no usable token any more.");
        }
        return client.getAccessToken().getTokenValue();
    }
}
