package com.xenopsoftware.learn.common.messaging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds a correlation id to every request (T-9.8).
 *
 * <p>FIRST in the chain, before authentication: a request refused at the door is a request
 * somebody may still have to trace, and an id assigned only to successful requests is missing
 * exactly when it is wanted.
 *
 * <p>A caller-supplied header joins an existing chain. That is deliberately trusted, and the trust
 * is cheap: a correlation id grants nothing, names nothing and reads nothing. The worst a forged
 * one does is put two unrelated things in one log query.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(Correlation.HEADER);
        String correlationId = incoming == null || incoming.isBlank()
            ? UUID.randomUUID().toString() : incoming.strip();
        // Echoed, so a caller can quote it in a support ticket without reading their own logs.
        response.setHeader(Correlation.HEADER, correlationId);
        try {
            Correlation.callWith(correlationId, () -> {
                try {
                    chain.doFilter(request, response);
                    return null;
                } catch (IOException | ServletException e) {
                    throw new DispatchFailure(e);
                }
            });
        } catch (DispatchFailure e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) e.getCause();
        }
    }

    /** Carries a checked servlet failure out through the binding helper and no further. */
    private static final class DispatchFailure extends RuntimeException {
        DispatchFailure(Exception cause) {
            super(cause);
        }
    }
}
