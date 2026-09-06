package com.xenopsoftware.learn.common.web.rest;

import com.xenopsoftware.learn.common.service.CallingService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who this service thinks is calling, and what the next service thinks (T-9.11).
 *
 * <p>Permanent rather than a test fixture, because it is the answer to a real 2am question: an
 * operator seeing a permission denied three hops deep needs to know which identity actually
 * arrived there, and reading it from a log means trusting the log to have printed the right
 * thing. Here the service says what it sees.
 *
 * <p>The relay that proves a whole chain lives next door in {@code ServiceRelayResource}, off
 * unless an operator turns it on.
 */
@RestController
@RequestMapping("/api/v1/internal")
public class ServiceChainResource {

    /** What this service sees: the person, their tenant, and which service carried them here. */
    @GetMapping("/whoami")
    public Map<String, Object> whoami(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String subject = authentication instanceof JwtAuthenticationToken token
            ? token.getToken().getSubject() : null;
        String asService = authentication instanceof JwtAuthenticationToken token
            ? token.getToken().getClaimAsString("svc") : null;
        CallingService caller = (CallingService) request.getAttribute(CallingService.ATTRIBUTE);
        return Map.of(
            "subject", String.valueOf(subject),
            "tenant", String.valueOf(TenantContext.get()),
            // Null when the principal is a person, set when a service is acting for itself.
            "principalIsService", String.valueOf(asService),
            "calledBy", caller == null ? "edge" : caller.serviceId(),
            "onBehalfOfUser", caller != null && caller.onBehalfOfUser());
    }

}
