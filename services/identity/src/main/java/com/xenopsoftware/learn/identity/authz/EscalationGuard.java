package com.xenopsoftware.learn.identity.authz;

import com.xenopsoftware.learn.identity.audit.AuditLogger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Nobody grants what they do not hold (T-2.6, ADR-0103).
 *
 * <p>Role editing is a customer-facing feature by design, so the escalation path is exposed to
 * every customer on day one: build a role containing a permission you were never given, assign
 * it to yourself, and the whole model is decorative. This guard is the reason that does not
 * work, and it sits on <b>four</b> paths rather than the three the task names — role creation,
 * role editing, assignment, and <b>cloning</b>, which the task does not list and which is the
 * shortest escalation of all: a clone of the company-administrator template would otherwise
 * hand its permissions to anyone who asked for a copy.
 *
 * <h2>Two different questions</h2>
 *
 * Putting a permission INTO a role asks only whether the caller holds it at all — the role is
 * inert until it is assigned, and the scope is decided there. Assigning a role asks the sharper
 * question: the caller must hold every permission it carries <b>at that scope or wider</b>, so
 * a group administrator cannot promote their group-scoped {@code user:manage} into a
 * company-wide one by attaching it to a tenant-scoped assignment.
 *
 * <h2>What this means before anyone holds anything</h2>
 *
 * With no assignments in a tenant, every path here refuses — including for the first
 * administrator, who has nothing yet. That is the rule working, not a bug: the first grant
 * cannot come from inside the tenant, and it arrives with provisioning (T-1.5).
 */
@Component
public class EscalationGuard {

    private final RequestPermissions requestPermissions;
    private final AuditLogger audit;

    public EscalationGuard(RequestPermissions requestPermissions, AuditLogger audit) {
        this.requestPermissions = requestPermissions;
        this.audit = audit;
    }

    /** Putting these permissions into a role: the caller must hold each one somewhere. */
    public void requireHolds(Set<Permission> permissions, String path, UUID target) {
        GrantedPermissions held = callerGrants();
        Set<String> missing = new TreeSet<>();
        for (Permission permission : permissions) {
            if (!held.holds(permission)) {
                missing.add(permission.code());
            }
        }
        if (!missing.isEmpty()) {
            refuse(path, target, missing, null);
        }
    }

    /**
     * Assigning a role: the caller must hold every permission it carries at this scope or
     * wider. Explicitly, per T-2.6: granting at TENANT requires holding at TENANT or PLATFORM,
     * and never at GROUP.
     */
    public void requireHoldsAtLeast(Set<Permission> permissions, AssignmentScopeType scope,
            String path, UUID target) {
        GrantedPermissions held = callerGrants();
        Set<String> missing = new TreeSet<>();
        for (Permission permission : permissions) {
            boolean wideEnough = held.widest(permission)
                .map(grant -> grant.type().width() >= scope.width())
                .orElse(false);
            if (!wideEnough) {
                missing.add(permission.code());
            }
        }
        if (!missing.isEmpty()) {
            refuse(path, target, missing, scope);
        }
    }

    private GrantedPermissions callerGrants() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            // No caller means no holdings, which means nothing may be granted. Failing closed
            // here matters more than anywhere else in the codebase.
            return GrantedPermissions.none();
        }
        Jwt jwt = token.getToken();
        return requestPermissions.forCaller(jwt);
    }

    private void refuse(String path, UUID target, Set<String> missing, AssignmentScopeType scope) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", path);
        payload.put("missing", missing);
        if (scope != null) {
            payload.put("scope", scope.name());
        }
        // The attempt is the interesting event, so it is recorded even though nothing changed —
        // in its own transaction, since this refusal is about to roll the caller's back.
        audit.recordRefusal("grant.refused", "role", target, payload);
        throw new EscalationException(scope == null
            ? "You cannot put a permission into a role that you do not hold yourself: " + missing
            : "You cannot grant at " + scope + " what you hold more narrowly: " + missing);
    }
}
