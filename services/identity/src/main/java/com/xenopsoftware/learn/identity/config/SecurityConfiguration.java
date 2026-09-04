package com.xenopsoftware.learn.identity.config;

import com.xenopsoftware.learn.identity.authz.CatalogPermissionEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything under {@code /api/**} is authenticated, and that is the default rather than a
 * per-endpoint decision.
 *
 * <p>The inverse — permit by default, secure the ones that need it — fails silently: a controller
 * added without an annotation is public, and nothing reports it. Here the same mistake produces a
 * 401 during the first test, which is the loud version of the same information.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Stateless: this service holds no session. The gateway is the only thing with a
            // session, and it relays a bearer token inward (T-9.11).
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // The container's internal forward to /error after an exception. Without this,
                // denyAll() swallows every error dispatch and a controller's 404 comes back as
                // a bare 403 with WWW-Authenticate: insufficient_scope -- the wrong status AND
                // a misleading hint. Not reachable from outside: dispatcher type is container
                // state, not something a request can claim.
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/management/health/**", "/management/info").permitAll()
                .requestMatchers("/v3/api-docs/**").permitAll()
                // Home-provider discovery (T-1.8). The single exception to the rule above, and
                // it cannot be otherwise: this answers "which provider should sign you in" for
                // somebody who has not signed in. Listed here rather than left to an annotation
                // so the exception is visible in the one place that describes the service's
                // exposure -- and narrow, so it can never widen by someone adding a method to
                // that controller. What keeps it cheap to attack is in TenantSso.discover.
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/discovery").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }

    /**
     * Routes every {@code hasPermission('resource', 'action')} through the catalog evaluator
     * (T-2.4). Static, as method-security configuration beans should be: this is infrastructure
     * the annotation processing itself depends on, and letting it wait for the full context
     * invites early-initialization cycles.
     */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(CatalogPermissionEvaluator evaluator) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(evaluator);
        return handler;
    }
}
