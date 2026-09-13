package com.xenopsoftware.learn.packaging.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything under {@code /api/**} is authenticated — and {@code /served/**} deliberately is not.
 *
 * <p>The chain is the template's (T-9.10), with one addition this service is the only one to need.
 *
 * <h2>Why the content-origin route is anonymous</h2>
 *
 * <p>{@code /served/**} is what the tenant's content origin proxies to. ADR-0105's decision is
 * that <b>no credential of any kind is ever presented to that origin</b>: an uploaded SCORM
 * package is third-party JavaScript executing in that document, and a cookie, a bearer token or a
 * session on that origin is something that code can reach — for every tenant, in every learner's
 * browser. So the requirement here is not "authentication is inconvenient", it is "authentication
 * would be the vulnerability".
 *
 * <p>What replaces it: an unguessable id, a tenant that has to match the row, an allowlist that
 * decided at ingest which files exist at all, and the fact that the origin holds nothing worth
 * taking. {@code LaunchUrls} states plainly what that trades away.
 *
 * <p><b>GET only.</b> Nothing on this path writes, and permitting the method rather than the path
 * means a future handler that does write cannot be reached anonymously by accident.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // No CSRF token, because there is no session to ride: every write here is
            // authenticated by a bearer token the browser does not attach automatically.
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/management/health/**", "/management/info").permitAll()
                .requestMatchers("/v3/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/served/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }
}
