package com.xenopsoftware.learn.identity.authz;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The deny-safe placeholder until roles and assignments exist (T-2.2, T-2.3): every caller
 * resolves to no grants, so every {@code hasPermission} check fails closed.
 *
 * <p>Deliberately NOT a development convenience that grants everything — a permissive stub is
 * how a codebase reaches production with checks nobody ever saw deny. No production endpoint
 * carries a {@code hasPermission} check yet ({@code CatalogCoverageTest} proves it), so this
 * denies nothing real; the moment T-2.3's resolver replaces it, endpoints can start being
 * annotated without behavior flips hiding in the swap.
 */
@Component
public class UngrantedResolver implements PermissionsResolver {

    @Override
    public GrantedPermissions resolveFor(Jwt caller) {
        return GrantedPermissions.none();
    }
}
