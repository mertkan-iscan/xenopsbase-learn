package com.xenopsoftware.learn.identity.authz;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The port between the evaluator (T-2.4) and wherever grants actually live.
 *
 * <p>The production implementation reads roles and scoped assignments (T-2.2, T-2.3) and unions
 * them into one {@link GrantedPermissions}; until those tables exist, {@link UngrantedResolver}
 * answers with nothing, which fails closed. The seam exists so the evaluator, the disclosure
 * rule and the coverage tests are finished machinery that T-2.3 plugs data into, rather than
 * code written against tables that were designed under deadline.
 */
public interface PermissionsResolver {

    GrantedPermissions resolveFor(Jwt caller);
}
