package com.xenopsoftware.learn.gateway.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.RefreshTokenOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
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

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(requests -> requests
                // The probes and the sign-in machinery. Everything else needs a session.
                .requestMatchers("/management/health/**", "/management/info").permitAll()
                .requestMatchers("/auth/session").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(Customizer.withDefaults())
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
