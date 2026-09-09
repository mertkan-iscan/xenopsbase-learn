package com.xenopsoftware.learn.gateway.web.rest;

import com.xenopsoftware.learn.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import java.net.URI;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * What the frontend needs to know about the session, and how to end it (T-10.2).
 *
 * <p>Three endpoints and no more. The frontend cannot inspect a token — it does not have one — so
 * these exist to answer the two questions a screen actually asks: <em>am I signed in</em>, and
 * <em>where do I send someone who is not</em>.
 */
@RestController
@RequestMapping("/auth")
public class SessionResource {

    /**
     * @param signedIn  whether this browser has a live session
     * @param name      what to show in the corner of the screen, or null
     * @param signInUrl where to navigate to sign in. Given by the server rather than hardcoded in
     *                  the app, so the registration id lives in one place
     */
    public record SessionView(boolean signedIn, String name, String signInUrl) {}

    /** @param endSessionUrl where to navigate to finish signing out at the issuer, or null */
    public record SignOutView(String endSessionUrl) {}

    private static final String REGISTRATION_ID = "oidc";
    private static final String SIGN_IN_URL = "/oauth2/authorization/" + REGISTRATION_ID;

    private final ClientRegistrationRepository registrations;
    private final GatewayProperties properties;

    public SessionResource(ClientRegistrationRepository registrations, GatewayProperties properties) {
        this.registrations = registrations;
        this.properties = properties;
    }

    /**
     * Permitted without a session on purpose: "are you signed in" answered with 401 is a question
     * that cannot be asked. It is also what issues the CSRF cookie for a page that has just
     * loaded, which is why the frontend calls it first.
     */
    @GetMapping("/session")
    public SessionView session(CsrfToken csrf) {
        // RESOLVING the token is what makes the deferred repository write the cookie -- the
        // repository is lazy by design, so a page that never reads the token never gets one, and
        // its first write is refused once, in a way that looks random. Injecting the CsrfToken is
        // not enough on its own; the value has to be asked for.
        csrf.getToken();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return new SessionView(false, null, SIGN_IN_URL);
        }
        return new SessionView(true,
            Optional.ofNullable(user.getPreferredUsername()).orElse(user.getSubject()),
            SIGN_IN_URL);
    }

    /**
     * End the session here, and say where to end it at the issuer.
     *
     * <p><b>JSON with a URL rather than a 302</b>, and the reason is the same one that makes the
     * API entry point answer 401: this is called with {@code fetch}, which follows redirects
     * silently. A redirect to Keycloak would be fetched, not navigated to, so the browser would
     * end up with the issuer's HTML in a promise and an SSO session still very much alive — a
     * sign-out that clears the local session and quietly leaves the one that would sign the person
     * straight back in.
     *
     * <p>The local session is invalidated first, so that a person who never follows the returned
     * URL — closes the tab, loses the network — is still signed out of this product. The SSO
     * session outliving that is the issuer's to bound.
     */
    @PostMapping("/logout")
    public SignOutView logout(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String idToken = authentication != null
            && authentication.getPrincipal() instanceof OidcUser user
                ? user.getIdToken().getTokenValue() : null;

        Optional.ofNullable(request.getSession(false)).ifPresent(session -> session.invalidate());
        SecurityContextHolder.clearContext();

        return new SignOutView(endSessionUrl(idToken));
    }

    private String endSessionUrl(String idToken) {
        ClientRegistration registration = registrations.findByRegistrationId(REGISTRATION_ID);
        Object endpoint = registration == null ? null
            : registration.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
        if (endpoint == null) {
            // A provider that publishes no end-session endpoint is a provider whose SSO session we
            // cannot end. Saying null is better than inventing a URL: the frontend then simply
            // does not navigate, and the person is signed out of this product only.
            return null;
        }
        UriComponentsBuilder url = UriComponentsBuilder.fromUri(URI.create(endpoint.toString()))
            .queryParam("post_logout_redirect_uri", properties.appUrl());
        if (idToken != null) {
            url.queryParam("id_token_hint", idToken);
        }
        return url.build().toUriString();
    }
}
