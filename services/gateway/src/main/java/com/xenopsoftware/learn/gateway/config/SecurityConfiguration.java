package com.xenopsoftware.learn.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;

/**
 * Who gets in, and what the browser is allowed to hold (T-9.17, T-10.2, ADR-0111).
 *
 * <h2>Two ways to authenticate, and they are not alternatives</h2>
 *
 * <ul>
 *   <li><b>{@code oauth2Login}</b> — a browser. The authorization-code flow runs here, the tokens
 *       stay in a server-side session in Valkey, and the browser holds a cookie. This is the whole
 *       of T-10.2's answer: an access token in {@code localStorage} is readable by any script that
 *       reaches the page, and this product also serves uploaded SCORM packages (ADR-0105), so that
 *       is a specific foreseeable risk rather than a general one.
 *   <li><b>{@code oauth2ResourceServer}</b> — an API client with its own bearer token. Validated
 *       here rather than waved through to be validated separately by each of four services.
 * </ul>
 *
 * <p>Both end with the same thing reaching the services: a bearer token in {@code Authorization},
 * put there by the {@code TokenRelay} filter for session callers and passed through for the rest.
 * That is what lets every service stay a plain stateless resource server (their
 * {@code SecurityConfiguration} says so: "this service holds no session").
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfiguration {

    @Bean
    SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
        http
            /*
             * CSRF stays ON, unlike every service behind this one, and the difference is not an
             * inconsistency — it is the consequence of holding a session.
             *
             * The services disable CSRF correctly: they are stateless and authenticate by bearer
             * token, which a browser does not attach automatically, so there is nothing for a
             * cross-site request to ride on. This gateway authenticates by COOKIE, which a
             * browser does attach automatically. Copying their `csrf.disable()` up here would be
             * copying a line whose justification was left behind.
             *
             * The cookie repository (readable by script, paired with the header) is what lets a
             * SPA on another origin participate; the attribute handler is what makes the token
             * resolve without subscribing to the request body.
             */
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler()))
            .authorizeExchange(exchange -> exchange
                .pathMatchers(HttpMethod.GET, "/management/health/**", "/management/info").permitAll()
                .pathMatchers("/login/**", "/oauth2/**", "/logout").permitAll()

                /*
                 * REFUSED AT THE EDGE, and this is the rule that most needs to be here rather
                 * than downstream.
                 *
                 * /api/v1/internal is T-9.11's service-to-service surface -- `whoami` reports the
                 * identity a service sees, and the relay makes a service ORIGINATE a call. Both
                 * are operator tools reachable from inside the cluster, and neither is a thing
                 * the internet may ask for. The services do authenticate these, so this is a
                 * second line rather than the only one; it is here because "an internal endpoint
                 * became publicly reachable" is a mistake that happens by someone adding a route,
                 * not by someone removing an annotation.
                 */
                .pathMatchers("/api/v1/internal/**").denyAll()

                /*
                 * Home-provider discovery (T-1.8): answers "which provider signs you in" for
                 * somebody who has not signed in, so it cannot require a session. Mirrors
                 * identity's own chain exactly, narrowed to the one method, so the exception
                 * cannot widen by someone adding a handler to that controller.
                 */
                .pathMatchers(HttpMethod.POST, "/api/v1/auth/discovery").permitAll()

                /*
                 * Cloudflare Stream's webhook (T-3.1). Not a session and not a bearer token: it
                 * authenticates by signature, which only `streaming` can check, so the edge
                 * forwards it and refuses to pretend it has an opinion. Anonymous here is
                 * deliberate and narrow -- it is one path, and what protects it is the signature
                 * check at the other end.
                 */
                .pathMatchers("/webhooks/media/**").permitAll()

                .pathMatchers("/api/**").authenticated()
                .anyExchange().denyAll())
            .oauth2Login(login -> {})
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
            .logout(logout -> logout.logoutUrl("/logout"));
        return http.build();
    }
}
