package com.xenopsoftware.learn.identity.web.rest;

import com.xenopsoftware.learn.identity.authz.CatalogPermissionEvaluator;
import com.xenopsoftware.learn.identity.authz.Permission;
import com.xenopsoftware.learn.identity.authz.RequestPermissions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The disclosure rule, implemented once (T-2.4's fifth criterion): a denial answers 403 only
 * when the caller could already know the resource exists, and 404 otherwise.
 *
 * <p>Concretely: a denied <b>read</b> is always 404 — the read itself is the disclosure gate,
 * so its denial must not disclose. A denied <b>mutation</b> is 403 for a caller who holds the
 * resource's {@code read} permission (they can see the thing; hiding it now would be theater)
 * and 404 for one who does not (a 403 would confirm to a blind caller that the id they guessed
 * is real). Cross-tenant ids never reach here at all — the discriminator (T-1.1) already
 * resolved them to nothing.
 *
 * <p>This advice catches the method-security denial inside MVC, which is also what keeps the
 * response shape ours — left to the filter chain it becomes a bare 403 with
 * {@code WWW-Authenticate: insufficient_scope}, the misleading hint T-1.2 already met once.
 */
@RestControllerAdvice
public class AccessDeniedAdvice {

    private final RequestPermissions requestPermissions;

    public AccessDeniedAdvice(RequestPermissions requestPermissions) {
        this.requestPermissions = requestPermissions;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> denied(HttpServletRequest request) {
        if ("GET".equals(request.getMethod())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.status(callerCouldSee(request) ? HttpStatus.FORBIDDEN : HttpStatus.NOT_FOUND)
            .build();
    }

    private boolean callerCouldSee(HttpServletRequest request) {
        Object denied = request.getAttribute(CatalogPermissionEvaluator.DENIED_ATTRIBUTE);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(denied instanceof Permission permission)
            || !(authentication instanceof JwtAuthenticationToken token)) {
            // A denial we cannot attribute -- some future mechanism, not the evaluator. Closed
            // in the safe direction for authorization (denied), open in the safe direction for
            // disclosure (403 admits nothing about a specific resource without an id-bearing
            // read path, and every id-bearing read path is a GET).
            return true;
        }
        Optional<Permission> read = Permission.byCode(permission.resource() + ":read");
        return read.isPresent() && requestPermissions.forCaller(token.getToken()).holds(read.get());
    }
}
