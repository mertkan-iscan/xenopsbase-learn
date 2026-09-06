package com.xenopsoftware.learn.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The edge (T-9.17, ADR-0111).
 *
 * <h2>What this process is for</h2>
 *
 * Four things, and each of them is a thing that cannot be done well anywhere else:
 *
 * <ul>
 *   <li><b>The session.</b> This is a backend-for-frontend: it runs the OIDC code flow, keeps the
 *       tokens server-side in Valkey, and relays the access token inward. The browser holds a
 *       cookie and never a token — which is the whole of T-10.2's argument, given that the same
 *       product serves uploaded SCORM packages from a frontend origin (ADR-0105).
 *   <li><b>One correlation id per request.</b> Minted here, forwarded inward, echoed outward, so
 *       a customer quoting an id from an error page names something findable in four services'
 *       logs (T-9.13).
 *   <li><b>The tenant status gate, one hop earlier.</b> Every MVC service already refuses a
 *       suspended tenant (T-1.4). Doing it here as well means a suspended company stops at the
 *       door instead of four times inside, which is what {@code StatusGateFilter}'s javadoc
 *       means by calling itself "a fast path and not the boundary".
 *   <li><b>Rate limits per principal</b> rather than per address (T-8.7). Behind no shared edge,
 *       four services each guess at a global budget; here there is one bucket per caller.
 * </ul>
 *
 * <h2>Why it is the only reactive process here</h2>
 *
 * Spring Cloud Gateway exists on WebFlux and nowhere else. That is the entire reason, and
 * ADR-0111 is explicit that it does not generalise: the eight modules behind this one are Spring
 * MVC on virtual threads because they block on JPA by design, and porting their {@code ThreadLocal}
 * tenancy to Reactor Context would trade a compiler-checked invariant for a convention.
 *
 * <p>What this process shares with them is the contract, not the stack — {@code TenantClaims},
 * {@code TenantStatusKeys}, {@code AccountStatus}, {@code Correlation}, all from
 * {@code platform-common}. The filters here are a second implementation of the same rules against
 * the same claim names and the same Valkey key, which is the one duplication ADR-0111 accepts and
 * the reason it accepts no others.
 */
@SpringBootApplication
public class GatewayApp {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApp.class, args);
    }
}
