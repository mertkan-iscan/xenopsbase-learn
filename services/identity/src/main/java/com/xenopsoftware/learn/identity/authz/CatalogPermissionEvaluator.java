package com.xenopsoftware.learn.identity.authz;

import com.xenopsoftware.learn.common.tenancy.TenantFilter;
import java.io.Serializable;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The check behind every {@code @PreAuthorize("hasPermission('resource', 'action')")} (T-2.4).
 *
 * <p>No check in this codebase names a role. Roles are runtime data a customer reconfigures; a
 * method gated on {@code tenant-admin} is a method no customer can re-wire, which defeats the
 * model. Checks name a catalog permission, and the catalog is code (T-2.1), so this evaluator
 * can — and does — refuse a check against a permission that does not exist. Loudly, with an
 * exception, never a silent deny: the stemcell shipped checks against {@code ROLE_ADMIN}
 * authorities no token ever carried, and every one of them denied quietly. A typo here is a
 * 500 with a name in it, not a security posture nobody chose.
 *
 * <p>The decision order: the catalog must know the permission; the caller's {@code side} claim
 * must match the permission's side (ADR-0103's pre-filter — platform staff cannot hold tenant
 * permissions, wherever grants come from); then the per-request resolved set answers. On deny,
 * the permission is recorded on the request so {@code AccessDeniedAdvice} can apply the
 * disclosure rule (403 vs 404) without re-deriving what was denied.
 */
@Component
public class CatalogPermissionEvaluator implements PermissionEvaluator {

    /** Request attribute carrying the permission that denied, for the disclosure decision. */
    public static final String DENIED_ATTRIBUTE = CatalogPermissionEvaluator.class.getName() + ".denied";

    private final RequestPermissions requestPermissions;

    public CatalogPermissionEvaluator(RequestPermissions requestPermissions) {
        this.requestPermissions = requestPermissions;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object resource, Object action) {
        String code = resource + ":" + action;
        Permission permission = Permission.byCode(code).orElseThrow(() -> new IllegalStateException(
            "@PreAuthorize checks '" + code + "', which is not in the Permission catalog. "
            + "A check against a nonexistent permission would deny silently forever -- "
            + "add the catalog entry or fix the check."));

        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return false;
        }
        Jwt jwt = token.getToken();
        if (!permission.side().name().equals(callerSide(jwt))) {
            return deny(permission);
        }
        if (!requestPermissions.forCaller(jwt).holds(permission)) {
            return deny(permission);
        }
        return true;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId,
            String targetType, Object action) {
        throw new UnsupportedOperationException(
            "Checks are hasPermission('resource', 'action'); per-object decisions are scope "
            + "resolution (T-2.3), not a second check style.");
    }

    /**
     * Which side the caller is acting on. Their token says PLATFORM; an impersonation session
     * (T-2.8) says TENANT, and the swap is total rather than additive — while wearing a
     * customer's face a support engineer holds the customer's permissions and NOT their own
     * platform ones. A session that could still suspend companies would be a privilege
     * escalation dressed as a support tool, and the pre-filter is the cheapest place to say so.
     */
    private static String callerSide(Jwt jwt) {
        return com.xenopsoftware.learn.identity.impersonation.ImpersonationContext.current()
            .map(session -> PermissionSide.TENANT.name())
            .orElseGet(() -> jwt.getClaimAsString(TenantFilter.SIDE_CLAIM));
    }

    private static boolean deny(Permission permission) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            request.setAttribute(DENIED_ATTRIBUTE, permission, RequestAttributes.SCOPE_REQUEST);
        }
        return false;
    }
}
