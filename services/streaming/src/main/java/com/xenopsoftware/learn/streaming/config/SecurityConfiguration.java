package com.xenopsoftware.learn.streaming.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything under {@code /api/**} is authenticated, and that is the default rather than a
 * per-endpoint decision — the same chain as identity's, carried by the template (T-9.10).
 *
 * <p>The ERROR dispatch is permitted for the reason identity learned the hard way: without it,
 * {@code denyAll()} swallows the container's forward to {@code /error} and a controller's 404
 * comes back as a bare 403 with a misleading {@code insufficient_scope} hint.
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
                // The provider holds no token; its credential is the signature on the body,
                // which the adapter verifies before parsing a byte (T-3.3). Permitting the
                // path here and refusing an unsigned body there IS the design: an
                // authenticated webhook endpoint would mean holding a credential for
                // somebody else system.
                .requestMatchers(HttpMethod.POST, "/webhooks/media").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }
}
