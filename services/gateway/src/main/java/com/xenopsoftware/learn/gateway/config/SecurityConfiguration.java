package com.xenopsoftware.learn.gateway.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.RefreshTokenOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Sign-in, and the shape of every refusal that follows (T-10.2).
 *
 * <h2>The storage decision, which is the whole issue</h2>
 *
 * <p><b>The browser never holds a token.</b> It holds an opaque session cookie; the access token
 * and the refresh token live in this process's session store and go inward on the relay's calls.
 *
 * <p>The alternative — the authorization-code flow in the SPA, with the access token in
 * {@code localStorage} or in a JavaScript variable — is the common answer and it is the wrong one
 * for <em>this</em> product. This platform will host uploaded SCORM packages (T-4.1) and third-party
 * embeds, so "any script that reaches the page can read the token" stops being a general caution
 * and becomes a specific, foreseeable path: a package a customer uploaded, running on the app's
 * origin, reading a bearer token for the whole tenant. Keeping the token out of the browser makes
 * that path not exist rather than defended.
 *
 * <p>What it costs, stated plainly: the frontend cannot call a service directly. Every call goes
 * through {@code ApiRelay}, and this process is on the path of every request a person makes. That
 * is a real availability cost and it is the reason ADR-0109 runs it at two replicas.
 *
 * <h2>An unauthenticated API call gets 401, never a redirect</h2>
 *
 * <p>The default entry point sends a 302 to the identity provider, which is right for a browser
 * navigating and useless for {@code fetch}: the request follows the redirect, hits Keycloak's login
 * page, and the SPA receives 200 and a mouthful of HTML where it expected JSON. Worse, it looks
 * like the API answered. Under {@code /api} the answer is a problem document with a code the
 * frontend can act on.
 *
 * <h2>CSRF is on, because the credential is a cookie now</h2>
 *
 * <p>The moment the browser authenticates with a cookie rather than a header, every write endpoint
 * behind this gateway is reachable from any page a person happens to have open. {@code SameSite=Lax}
 * covers most of it; the token cookie plus header covers the rest, and the two together are cheap.
 * The cookie is deliberately readable by script — it is not a credential, it is a value the frontend
 * has to echo back, and a token an attacker cannot read from another origin is exactly the point.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /**
     * How long before expiry a token counts as expired, so it is refreshed on the call BEFORE the
     * one that would have failed.
     *
     * <p>Sixty seconds is Spring's default and it is not enough here. A learner submitting an exam
     * sends one request after minutes of silence; if that request is the one that discovers the
     * token expired, the refresh happens inside it and any hiccup at the issuer becomes a failed
     * submission. Five minutes means the token is renewed by whatever ordinary request happened to
     * come first, and the submission carries one that is already fresh.
     */
    private static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(5);

    private final GatewayProperties properties;

    /**
     * The saved request, read WITHOUT Spring's replay marker.
     *
     * <p>`HttpSessionRequestCache` appends a `continue` parameter to the URL it hands back, so
     * that `RequestCacheAwareFilter` can recognise the request coming round again and replay the
     * original body and headers. Nothing here replays anything: what is being served is a static
     * application that will route on the path itself. The marker would have no consumer and would
     * appear in the learner's address bar, on the first screen they see after signing in.
     */
    private final HttpSessionRequestCache savedRequests = new HttpSessionRequestCache();

    SecurityConfiguration(GatewayProperties properties) {
        this.properties = properties;
        this.savedRequests.setMatchingRequestParameterName(null);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        AuthenticationSuccessHandler signedIn = this::backToTheApp;
        http
            .authorizeHttpRequests(requests -> requests
                // The probes and the sign-in machinery. Everything else needs a session.
                .requestMatchers("/management/health/**", "/management/info").permitAll()
                .requestMatchers("/auth/session").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(login -> login.successHandler(signedIn))
            // Logout is a POST to /auth/logout that answers with JSON, not a form post that
            // redirects -- see SessionResource#logout for why a redirect is wrong for fetch.
            .logout(logout -> logout.disable())
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // The plain handler rather than the XOR one: the XOR handler masks the token per
                // response, so the value in the cookie and the value a client must send stop being
                // the same string, and an SPA echoing the cookie is refused with a message that
                // says nothing about masking. The BREACH mitigation it buys assumes a page that
                // reflects the token into a compressed HTML body, and nothing here renders HTML.
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .exceptionHandling(handling -> handling
                .defaultAuthenticationEntryPointFor(
                    (request, response, exception) -> writeSessionEnded(response),
                    request -> request.getRequestURI().startsWith("/api")));
        return http.build();
    }

    /**
     * The manager that refreshes a token before it expires, without the browser knowing.
     *
     * <p>Two providers and no more: {@code authorizationCode} for the sign-in that already
     * happened, {@code refreshToken} for every renewal after it. No client-credentials provider —
     * this process must never be able to obtain a token that is not on behalf of a person, because
     * the day it can, "the gateway did it" becomes an answer in an audit log.
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository clients) {
        RefreshTokenOAuth2AuthorizedClientProvider refresh =
            new RefreshTokenOAuth2AuthorizedClientProvider();
        refresh.setClockSkew(REFRESH_BEFORE_EXPIRY);

        OAuth2AuthorizedClientProvider providers = OAuth2AuthorizedClientProviderBuilder.builder()
            .authorizationCode()
            .provider(refresh)
            .build();

        DefaultOAuth2AuthorizedClientManager manager =
            new DefaultOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(providers);
        return manager;
    }

    /**
     * Where a person lands after signing in, built from the app's ORIGIN rather than from the
     * request (T-10.2).
     *
     * <p>Spring's default is {@code SavedRequestAwareAuthenticationSuccessHandler}, which returns
     * the saved request's ABSOLUTE url — reconstructed from scheme, host and port as this process
     * saw them. Behind the tunnel and the ingress that reconstruction produced
     * {@code https://learn-dev.xenopsoftware.com:80/}: the scheme from {@code X-Forwarded-Proto}
     * and the port from {@code X-Forwarded-Port}, which disagree. A browser sent there speaks TLS
     * to a plaintext port and shows ERR_SSL_PROTOCOL_ERROR — after a successful login, which is
     * the worst place to fail, because the person has already authenticated and the address bar
     * says something that looks right.
     *
     * <p>{@code server.forward-headers-strategy} does not fix this one. The filter it installs
     * drops a port only when it is the default for the scheme, and 80 is not the default for
     * https — so the pair survives normalisation intact.
     *
     * <p>This is the same decision the {@code redirect-uri} in application.yml already makes, in
     * the same words: the app's origin, not this process's. THE PATH IS STILL THE SAVED ONE, so a
     * learner who was sent to sign in from a deep link comes back to it; only the origin is
     * replaced, because the origin is the part this process cannot observe correctly.
     */
    void backToTheApp(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        SavedRequest saved = savedRequests.getRequest(request, response);
        String path = "/";
        if (saved != null) {
            URI original = URI.create(saved.getRedirectUrl());
            path = original.getRawPath() == null || original.getRawPath().isEmpty()
                ? "/"
                : original.getRawPath();
            String query = withoutTheReplayMarker(original.getRawQuery());
            if (!query.isEmpty()) {
                path = path + "?" + query;
            }
        }
        // No slash doubling: appUrl is an origin and every path here starts with one.
        response.sendRedirect(properties.appUrl().replaceAll("/+$", "") + path);
    }

    /**
     * Drops Spring's {@code continue} marker from a saved query string.
     *
     * <p>`HttpSessionRequestCache` appends it when it SAVES, so it is already in the stored URL by
     * the time this reads it — configuring the read side does not help. Its purpose is to let
     * `RequestCacheAwareFilter` recognise the request coming round again and replay it; nothing
     * here replays anything, because what gets served is a static application that routes on the
     * path. Left in, it is a stray parameter in the address bar on the first screen after sign-in.
     */
    private static String withoutTheReplayMarker(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        return Arrays.stream(rawQuery.split("&"))
            .filter(parameter -> !parameter.equals("continue") && !parameter.startsWith("continue="))
            .collect(Collectors.joining("&"));
    }

    private static void writeSessionEnded(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
            {"type":"about:blank","title":"Unauthorized","status":401,\
            "code":"SESSION_ENDED",\
            "detail":"Your session has ended. Sign in again; anything you were part-way through \
            is still here."}""");
    }
}
