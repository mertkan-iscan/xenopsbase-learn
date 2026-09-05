package com.xenopsoftware.learn.catalog.home;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Anything an author changes makes every learner's home screen out of date (T-5.8).
 *
 * <h2>Why this is a filter and not a line in each service</h2>
 *
 * A course restructured, a gate rewritten, an item republished, an assignment made to a group: all
 * of them change what somebody's screen should say, and all of them arrive as a successful write to
 * this service's API. Bumping the tenant epoch from each of the twelve or so methods that do those
 * things would work today and would be wrong the first time somebody adds a thirteenth — and the
 * failure is a screen that quietly shows yesterday's course for a minute, which nobody would trace
 * back to a missing line.
 *
 * <p>So it is closed by default instead: <b>every successful mutating request bumps the epoch</b>.
 * A new endpoint is covered before it is written. The cost is over-invalidation — creating a draft
 * item nobody can see also invalidates — which costs a cache miss, and authoring writes are rare
 * against the read this protects.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * It does not touch per-learner versions. Those move for events (progress, completion, group reach,
 * profile) inside the transaction that applies them, where the guarantee has to be exact because
 * they happen constantly. This is the coarse half, for the changes that are rare and broad.
 *
 * <p>It also runs <b>after</b> the response, outside the request's transaction, which is the one
 * weakness worth naming: a crash between a commit and this bump leaves screens stale until the
 * cache entry expires. That is bounded by the TTL — a minute — and it is the trade for not having
 * to remember anything.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class HomeInvalidation extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(HomeInvalidation.class);

    private final HomeVersions versions;

    public HomeInvalidation(HomeVersions versions) {
        this.versions = versions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        chain.doFilter(request, response);
        if (!changesSomething(request) || response.getStatus() >= 300) {
            return;
        }
        try {
            versions.bumpTenant(TenantContext.require());
        } catch (RuntimeException e) {
            // The write succeeded; only the invalidation did not. Screens are stale until the
            // cache entry expires, which is a minute, and turning a successful edit into an error
            // afterwards would be worse in every way.
            LOG.warn("Could not invalidate cached home screens after {} {}", request.getMethod(),
                request.getRequestURI(), e);
        }
    }

    private static boolean changesSomething(HttpServletRequest request) {
        String method = request.getMethod();
        return request.getRequestURI().startsWith("/api/")
            && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "DELETE".equals(method));
    }
}
