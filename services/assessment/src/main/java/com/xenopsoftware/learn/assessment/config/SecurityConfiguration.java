package com.xenopsoftware.learn.assessment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything under {@code /api/**} is authenticated, and that is the default rather than a
 * per-endpoint decision — the same chain as identity's, streaming's, reporting's and catalog's,
 * carried by the template (T-9.10, and this is its fifth application).
 *
 * <p>The ERROR dispatch is permitted for the reason identity learned the hard way: without it,
 * {@code denyAll()} swallows the container's forward to {@code /error} and a controller's 404
 * comes back as a bare 403 with a misleading {@code insufficient_scope} hint.
 *
 * <p>{@code @EnableMethodSecurity} is on, and nothing uses it yet. That is deliberate rather than
 * copied: T-6.1's authoring boundary is a permission the evaluator in {@code identity} answers,
 * and until this module shares a process with it (ADR-0109) there is nothing to wire. Having the
 * annotation active means adding {@code @PreAuthorize} later is one line on one method rather than
 * a configuration change nobody remembers is missing — which is the failure mode where a method
 * carries a check that silently never runs.
 *
 * <p><b>{@code /management/metrics} and {@code /management/prometheus} are exposed by the
 * management configuration and denied here</b>, exactly as in the other four services. That is a
 * live gap rather than a decision — T-2.5 and T-3.6 are both open on it and T-9.13 (#91) owns the
 * call — and it is repeated here rather than quietly fixed, because a fifth service silently
 * disagreeing with the other four about which endpoints are reachable would be worse than a
 * consistent gap.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/management/health/**", "/management/info").permitAll()
                .requestMatchers("/v3/api-docs/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }
}
