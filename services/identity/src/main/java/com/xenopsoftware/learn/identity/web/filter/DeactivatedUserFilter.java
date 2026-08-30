package com.xenopsoftware.learn.identity.web.filter;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.identity.user.AppUser;
import com.xenopsoftware.learn.identity.user.AppUserRepository;
import com.xenopsoftware.learn.identity.user.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A deactivated person is refused on the next request, not the next token (T-1.9).
 *
 * <p><b>Why a filter and not a permission.</b> Deactivation is not "may not do this"; it is "may
 * not act". Expressed as authorization it would have to be added to every check that exists and
 * every check anybody writes later, and the one that gets forgotten is the endpoint that still
 * works for somebody who left the company. Here it is one refusal in front of everything.
 *
 * <p><b>The cost, stated.</b> One indexed lookup by {@code idp_sub} per authenticated request.
 * That is deliberate: it is what makes the effect immediate rather than "within one token
 * lifetime", and a status cached for correctness would be a status that can be stale exactly
 * when it matters. The gateway will front-run this for the whole platform against a
 * version-stamped entry (T-1.4); until it exists, this service answers for itself.
 *
 * <p>Ordered after {@code TenantFilter}, because it reads a tenant-scoped row and the tenant is
 * bound there. The refusal carries a machine-readable reason so a UI can say something true
 * instead of showing an unexplained error.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 50)
public class DeactivatedUserFilter extends OncePerRequestFilter {

    private final AppUserRepository users;

    public DeactivatedUserFilter(AppUserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || TenantContext.get() == null) {
            // Unauthenticated, or a request with no tenant bound: there is no app_user to have a
            // status, and refusing here would refuse the health endpoint.
            chain.doFilter(request, response);
            return;
        }

        Optional<AppUser> caller = users.findByIdpSub(token.getToken().getSubject());
        if (caller.isPresent() && caller.get().getStatus() == UserStatus.DEACTIVATED) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                {"reason":"USER_DEACTIVATED",\
                "message":"This account has been deactivated. Ask an administrator to restore it."}""");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Never on the error dispatch. A refusal written here re-enters the chain through
     * {@code /error}, and refusing that dispatch too would replace every error body with this
     * one — the same trap the security chain's ERROR permit exists for.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }
}
