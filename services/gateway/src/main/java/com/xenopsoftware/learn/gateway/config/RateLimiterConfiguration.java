package com.xenopsoftware.learn.gateway.config;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Who a rate limit counts against (T-8.7).
 *
 * <p>The issue title is the requirement: <b>per principal, not per address</b>. Spring Cloud
 * Gateway ships no default {@code KeyResolver}, deliberately — the answer is application-specific,
 * and getting it wrong produces a limiter that looks configured and governs nothing.
 *
 * <p>Two ways to get it wrong here, and both produce ONE bucket for everybody:
 *
 * <ul>
 *   <li><b>Keying on the remote address without the forwarded chain.</b> Every request arrives
 *       from the ingress, so the limit becomes a global ceiling and the first busy learner rate
 *       limits the rest of the platform.
 *   <li><b>Keying on the authentication without excluding anonymous.</b> An
 *       {@link AnonymousAuthenticationToken} reports {@code isAuthenticated() == true} and always
 *       has the same name, so every unauthenticated request shares a bucket called
 *       {@code anonymousUser}. That is the same global ceiling wearing a different label, and it
 *       is the one that looks correct in review.
 * </ul>
 *
 * <h2>The key</h2>
 *
 * <pre>
 *   sub:&lt;subject&gt;        for an authenticated caller, session or bearer token
 *   ip:&lt;client address&gt;  before sign-in, when there is nothing else to key on
 * </pre>
 *
 * <p>The subject is preferred because it survives a client changing address — a learner's phone
 * moving between networks mid-course keeps its bucket, and an attacker rotating addresses does not
 * get a fresh one for free once authenticated.
 *
 * <p>{@code sub} rather than {@code preferred_username}: the stemcell took a null-pointer in this
 * exact method from a token that carried no {@code preferred_username}, and a rate limit key that
 * can be null is a rate limit that stops applying to precisely the tokens that are unusual.
 *
 * <h2>Not keyed on the tenant, and that is a decision</h2>
 *
 * A per-tenant bucket would let one company's traffic throttle its own users collectively, which
 * sounds fairer and is not: it makes one learner's script the reason their colleagues see 429s,
 * with nothing in the response that would let anyone work out why. Per-principal keeps the blast
 * radius at the caller who caused it. A per-tenant ceiling is a separate concern and belongs with
 * plan limits rather than with abuse control.
 *
 * <h2>When the key cannot be resolved</h2>
 *
 * The request is served — {@code deny-empty-key} is false in configuration. A limiter that fails
 * closed turns a bug in identifying callers into an outage.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimiterConfiguration {

    /**
     * The bucket key for one request.
     *
     * <p>Referenced by name from application.yml as {@code '#{@clientKeyResolver}'}. Named there
     * rather than relying on "there is exactly one {@code KeyResolver} bean", so that adding a
     * second resolver later is a compile-time question rather than a silent change of behaviour.
     */
    @Bean
    public KeyResolver clientKeyResolver() {
        XForwardedRemoteAddressResolver addressResolver = XForwardedRemoteAddressResolver.maxTrustedIndex(1);

        return exchange -> ReactiveSecurityContextHolder.getContext()
            .map(context -> context.getAuthentication())
            .filter(RateLimiterConfiguration::isRealUser)
            .map(RateLimiterConfiguration::subjectOf)
            .filter(subject -> !subject.isBlank())
            .map(subject -> "sub:" + subject)
            // switchIfEmpty rather than defaultIfEmpty: the address lookup should happen only
            // when it is needed, not on every authenticated request as well.
            .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientAddress(addressResolver, exchange)));
    }

    /**
     * True for a real principal. Anonymous authentication reports itself as authenticated, which
     * is the trap this exists to avoid — see the class javadoc.
     */
    private static boolean isRealUser(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static String subjectOf(Authentication authentication) {
        if (authentication.getPrincipal() instanceof OidcUser user && user.getSubject() != null) {
            return user.getSubject();
        }
        // getName() is the name-attribute claim, which is configurable and has been null in this
        // position before. Kept as a fallback rather than as the primary, and null-safe.
        String name = authentication.getName();
        return name == null ? "" : name;
    }

    /**
     * The client's address, one proxy hop back.
     *
     * <p>{@code maxTrustedIndex(1)} takes the last entry of {@code X-Forwarded-For} — the address
     * the closest proxy actually observed — rather than the first, which is client-supplied and
     * therefore arbitrary. Falls back to the socket address when there is no forwarded header,
     * which is the in-cluster case.
     *
     * <p><b>What this key is worth depends entirely on the edge in front of it.</b> Anything that
     * could reach this process directly could present its own {@code X-Forwarded-For} and mint a
     * fresh bucket per request. Today nothing can — the services are cluster-internal with no
     * Ingress — and the day that changes, this assumption changes with it and should be re-read.
     */
    private static String clientAddress(XForwardedRemoteAddressResolver resolver, ServerWebExchange exchange) {
        InetSocketAddress resolved = resolver.resolve(exchange);
        if (resolved != null && resolved.getAddress() != null) {
            return resolved.getAddress().getHostAddress();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "" : remote.getAddress().getHostAddress();
    }
}
