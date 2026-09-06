package com.xenopsoftware.learn.common.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Who is calling, and whether they may (T-9.11).
 *
 * <h2>Two credentials, and each answers a different question</h2>
 *
 * The {@code Authorization} bearer is the <b>end user's own token</b>, forwarded unchanged
 * through every hop. The callee validates it exactly as it would from the edge — signature,
 * issuer, expiry — which is what makes the propagated identity <b>verified rather than
 * asserted</b>. A calling service cannot fabricate a user it was never given, because doing so
 * would mean forging a Keycloak signature.
 *
 * <p>{@code X-Service-Authorization} is the caller's own client-credentials token, and it answers
 * "which service is this". Its {@code svc} claim comes from the realm, not from a header the
 * caller writes, so a service cannot claim to be another.
 *
 * <p>The alternative — trusting a header like {@code X-On-Behalf-Of} — is what makes a
 * permission model decorative: every service becomes able to act as any user, and a bug in the
 * least careful one is a full compromise.
 *
 * <h2>What is refused</h2>
 *
 * A request presenting a service header that does not verify is refused outright, and counted.
 * Absence is not refusal: a request with no service header is an ordinary edge request, which
 * the security chain judges on the user token alone.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 110)
public class ServiceAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Authorization";

    private static final Logger LOG = LoggerFactory.getLogger(ServiceAuthenticationFilter.class);

    private final JwtDecoder decoder;
    private final AtomicLong refused = new AtomicLong();

    public ServiceAuthenticationFilter(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    /** How many calls have been refused for a bad service credential. */
    public long refusedCount() {
        return refused.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            // Not an inter-service call. The security chain still judges the user token.
            chain.doFilter(request, response);
            return;
        }
        String token = header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
        Jwt serviceToken;
        try {
            serviceToken = decoder.decode(token);
        } catch (JwtException invalid) {
            // Network-level trust is not authentication: a call that presents a service
            // credential we cannot verify is refused rather than treated as anonymous, because
            // the alternative is an internal API one misrouted request from being public.
            refused.incrementAndGet();
            LOG.warn("Refused an inter-service call: the service credential did not verify ({})",
                invalid.getMessage());
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                "{\"error\":{\"code\":\"SERVICE_CREDENTIAL_INVALID\","
                + "\"message\":\"The calling service could not be authenticated.\"}}");
            return;
        }

        String serviceId = serviceToken.getClaimAsString("svc");
        if (serviceId == null || serviceId.isBlank()) {
            refused.incrementAndGet();
            LOG.warn("Refused an inter-service call: a verified token with no svc claim, so the "
                + "caller cannot be identified. A user token is not a service credential.");
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                "{\"error\":{\"code\":\"SERVICE_CREDENTIAL_INVALID\","
                + "\"message\":\"The calling service could not be identified.\"}}");
            return;
        }

        request.setAttribute(CallingService.ATTRIBUTE,
            new CallingService(serviceId, carriesAUser()));
        chain.doFilter(request, response);
    }

    /**
     * Whether this hop carries a person. The user token has already been validated by the
     * resource server, so this reads the outcome rather than the header.
     */
    private static boolean carriesAUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return false;
        }
        // A service acting for itself presents its own token in Authorization too, and that
        // token has no subject-side claims -- no tenant, no side. That is the distinction
        // logs and audit need (T-9.11).
        return token.getToken().getClaimAsString("svc") == null;
    }
}
