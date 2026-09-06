package com.xenopsoftware.learn.gateway.web.filter;

import com.xenopsoftware.learn.common.tenancy.AccountStatus;
import com.xenopsoftware.learn.common.tenancy.TenantClaims;
import com.xenopsoftware.learn.common.tenancy.TenantStatusKeys;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * A suspended company stops at the door (T-1.4, ADR-0111).
 *
 * <p>The reactive twin of {@code StatusGateFilter}, and deliberately not a replacement for it.
 * {@code StatusGateFilter}'s javadoc calls itself "a fast path and not the boundary"; this is the
 * same fast path one hop earlier. The boundary is still the check inside the write transaction,
 * in the module that owns the rows, and neither of these gates changes that.
 *
 * <p>What it buys is that a suspended tenant is refused <b>once, here</b>, rather than four times
 * inside — and refused before the request costs a routed connection, a downstream thread and a
 * database round trip.
 *
 * <h2>Everything it agrees with the services on comes from one place</h2>
 *
 * The claim ({@link TenantClaims#TENANT_CLAIM}), the key ({@link TenantStatusKeys#forTenant}), the
 * vocabulary ({@link AccountStatus}) and the refusal body ({@link AccountStatus#refusalBody}) are
 * all read from {@code platform-common}. What is <em>not</em> shared is the client: this reads
 * Valkey through {@link ReactiveStringRedisTemplate}, because the blocking template the MVC
 * services use would park an event-loop thread on every request.
 *
 * <p>That split — same contract, different client — is the whole shape of ADR-0111 in one class.
 *
 * <h2>Permissive when it cannot answer, deliberately</h2>
 *
 * No Valkey, no tenant claim, an unreadable entry, a timeout: the request proceeds. T-1.4 settled
 * this and the reasoning is unchanged — a cache outage that suspended every customer at once is a
 * worse failure than one that briefly lets a suspended customer read, and the writes that matter
 * are refused inside the transaction regardless. An unrecognised value is logged, because that
 * one means a newer build wrote a status this one does not know.
 */
@Component
public class StatusGateWebFilter implements WebFilter, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(StatusGateWebFilter.class);

    private final ReactiveStringRedisTemplate valkey;

    public StatusGateWebFilter(ReactiveStringRedisTemplate valkey) {
        this.valkey = valkey;
    }

    /**
     * After authentication, before routing.
     *
     * <p>Ordering matters in both directions and neither is arbitrary. Before the security
     * filters there is no verified claim to read, so the gate would be reading whatever the caller
     * sent. After routing there is nothing left to refuse — the request has already been
     * forwarded, which is the cost this filter exists to avoid.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 90;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Health, docs, the login callback and anything outside the API. A suspended tenant must
        // still be able to sign in far enough to be told why, and an operator must still reach
        // the probes.
        if (!exchange.getRequest().getPath().value().startsWith("/api/")) {
            return chain.filter(exchange);
        }

        /*
         * THE ABSENT OPERATOR IS THE POINT: there is no switchIfEmpty after the flatMap below,
         * and an earlier version of this method had one. It is worth the paragraph, because the
         * bug it caused was a security hole that reads as correct.
         *
         * Everything on this path returns Mono<Void>, and a Mono<Void> ALWAYS completes empty --
         * including the one that writes a 403. So `...flatMap(this::gate).switchIfEmpty(chain)`
         * meant: refuse the request, write the refusal body, and then hand the request to the
         * chain anyway. The caller saw a 403 and the routed service saw the request. Only the
         * refusal assertions in StatusGateWebFilterTest caught it; every permissive case passed,
         * for the wrong reason.
         *
         * So the "was there a security context" question is answered on a value that can be
         * empty -- the tenant -- and the branch is inside a single terminal flatMap.
         */
        return ReactiveSecurityContextHolder.getContext()
            .map(context -> context.getAuthentication())
            .map(StatusGateWebFilter::tenantOrNone)
            // No security context at all: an unauthenticated request, which the security chain
            // answers and this filter has no business refusing first.
            .defaultIfEmpty(NO_TENANT)
            .flatMap(tenant -> NO_TENANT.equals(tenant)
                ? chain.filter(exchange)
                : gate(exchange, chain, tenant));
    }

    /** Not a valid tenant id, so it cannot collide with one; see the branch above. */
    private static final String NO_TENANT = "";

    private Mono<Void> gate(ServerWebExchange exchange, WebFilterChain chain, String tenant) {
        boolean write = AccountStatus.isWrite(exchange.getRequest().getMethod().name());
        return valkey.opsForValue()
            .get(TenantStatusKeys.forTenant(tenant))
            .map(published -> {
                AccountStatus status = TenantStatusKeys.parseOrNull(published);
                if (status == null) {
                    LOG.warn("Unreadable status entry for tenant {}: {}", tenant, published);
                    return AccountStatus.ACTIVE;
                }
                return status;
            })
            // Absent entry: the normal state of a tenant nobody has ever suspended.
            .defaultIfEmpty(AccountStatus.ACTIVE)
            .onErrorResume(valkeyDown -> {
                LOG.warn("Could not read the status entry for tenant {}; the edge is permissive "
                    + "until Valkey returns. Writes are still refused by the owning module.",
                    tenant, valkeyDown);
                return Mono.just(AccountStatus.ACTIVE);
            })
            .flatMap(status -> status.permitsReads() && (!write || status.permitsWrites())
                ? chain.filter(exchange)
                : refuse(exchange, tenant, status, write));
    }

    private Mono<Void> refuse(ServerWebExchange exchange, String tenant, AccountStatus status, boolean write) {
        LOG.info("Refusing {} {} for tenant {}: account is {}",
            exchange.getRequest().getMethod(), exchange.getRequest().getPath().value(), tenant, status);
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer body = exchange.getResponse().bufferFactory()
            .wrap(status.refusalBody(write).getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(body));
    }

    /**
     * The tenant from the verified token, or {@link #NO_TENANT} when there is not one.
     *
     * <p>Two shapes reach this gateway and both have to be handled, which is a difference from the
     * services behind it: a browser arrives with a session and an {@link OidcUser}, an API client
     * arrives with a bearer token and a {@link JwtAuthenticationToken}. A gate that understood
     * only the second would wave every browser session through — which is most of the traffic and
     * exactly the traffic T-1.4 is about.
     *
     * <p>Platform staff bind to the reserved tenant, matching {@code TenantFilter} exactly; a
     * platform token that also carries a customer's {@code tenant_id} is not honoured, for the
     * reason that filter records.
     */
    private static String tenantOrNone(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return NO_TENANT;
        }
        String tenant = null;
        String side = null;
        if (authentication.getPrincipal() instanceof OidcUser user) {
            tenant = user.getClaimAsString(TenantClaims.TENANT_CLAIM);
            side = user.getClaimAsString(TenantClaims.SIDE_CLAIM);
        } else if (authentication instanceof JwtAuthenticationToken jwtToken) {
            Jwt jwt = jwtToken.getToken();
            tenant = jwt.getClaimAsString(TenantClaims.TENANT_CLAIM);
            side = jwt.getClaimAsString(TenantClaims.SIDE_CLAIM);
        }
        if (TenantClaims.PLATFORM.equals(side)) {
            return TenantClaims.PLATFORM_TENANT;
        }
        return tenant == null || tenant.isBlank() ? NO_TENANT : tenant;
    }
}
