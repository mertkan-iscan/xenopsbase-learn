package com.xenopsoftware.learn.common.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The two refusals and the one distinction that make an inter-service call trustworthy (T-9.11).
 */
class ServiceAuthenticationFilterTest {

    private JwtDecoder decoder;
    private ServiceAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void freshRequest() {
        decoder = Mockito.mock(JwtDecoder.class);
        filter = new ServiceAuthenticationFilter(decoder);
        request = new MockHttpServletRequest("GET", "/api/v1/internal/whoami");
        response = new MockHttpServletResponse();
        chain = Mockito.mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    void aCallWithNoServiceHeaderIsJustAnEdgeRequest() throws Exception {
        filter.doFilter(request, response, chain);

        Mockito.verify(chain).doFilter(request, response);
        assertThat(request.getAttribute(CallingService.ATTRIBUTE)).isNull();
        assertThat(filter.refusedCount()).isZero();
    }

    @Test
    void aServiceCredentialThatDoesNotVerifyIsRefusedAndCounted() throws Exception {
        // Network-level trust is not authentication: an internal API that accepted this would be
        // one misrouted request away from being a public one.
        Mockito.when(decoder.decode("forged")).thenThrow(new JwtException("bad signature"));
        request.addHeader(ServiceAuthenticationFilter.HEADER, "Bearer forged");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("SERVICE_CREDENTIAL_INVALID");
        assertThat(filter.refusedCount()).isEqualTo(1);
        Mockito.verifyNoInteractions(chain);
    }

    @Test
    void aUserTokenIsNotAServiceCredential() throws Exception {
        // A verified token, but a person's. Accepting it here would let anyone who can sign in
        // present themselves as a service.
        Mockito.when(decoder.decode("a-users-token")).thenReturn(userToken("sub-casey"));
        request.addHeader(ServiceAuthenticationFilter.HEADER, "Bearer a-users-token");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(filter.refusedCount()).isEqualTo(1);
        Mockito.verifyNoInteractions(chain);
    }

    @Test
    void aServiceCarryingAPersonIsDistinguishableFromOneActingForItself() throws Exception {
        Mockito.when(decoder.decode("streaming-token")).thenReturn(serviceToken("streaming"));
        request.addHeader(ServiceAuthenticationFilter.HEADER, "Bearer streaming-token");

        // A person's token in Authorization: this hop carries somebody.
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(userToken("sub-casey")));
        filter.doFilter(request, response, chain);
        CallingService carrying = (CallingService) request.getAttribute(CallingService.ATTRIBUTE);
        assertThat(carrying.serviceId()).isEqualTo("streaming");
        assertThat(carrying.onBehalfOfUser()).isTrue();

        // The service's own token as the principal: a rollup, a reconciliation, nobody waiting.
        MockHttpServletRequest ownBehalf = new MockHttpServletRequest("GET", "/api/v1/internal/whoami");
        ownBehalf.addHeader(ServiceAuthenticationFilter.HEADER, "Bearer streaming-token");
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(serviceToken("streaming")));
        filter.doFilter(ownBehalf, new MockHttpServletResponse(), chain);
        CallingService alone = (CallingService) ownBehalf.getAttribute(CallingService.ATTRIBUTE);
        assertThat(alone.serviceId()).isEqualTo("streaming");
        assertThat(alone.onBehalfOfUser()).isFalse();
    }

    private static Jwt userToken(String subject) {
        return Jwt.withTokenValue("user").header("alg", "none").subject(subject)
            .claim("tenant_id", "acme").claim("side", "TENANT")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private static Jwt serviceToken(String service) {
        return Jwt.withTokenValue("svc").header("alg", "none").subject("service-account-svc-" + service)
            .claim("svc", service)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
