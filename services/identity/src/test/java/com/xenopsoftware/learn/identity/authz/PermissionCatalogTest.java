package com.xenopsoftware.learn.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The catalog's own invariants — no Spring, no database, because these are properties of the
 * enum and should fail in the first millisecond of a build, not after a container starts.
 */
class PermissionCatalogTest {

    @Test
    void codesAreUniqueAndWellFormed() {
        assertThat(Arrays.stream(Permission.values()).map(Permission::code).distinct())
            .hasSize(Permission.values().length);
        for (Permission permission : Permission.values()) {
            // Stable, greppable, lowercase resource:action -- these strings outlive refactors
            // because roles reference them as rows.
            assertThat(permission.code()).matches("[a-z]+:[a-z_]+");
            assertThat(permission.code())
                .isEqualTo(permission.resource() + ":" + permission.action());
        }
    }

    @Test
    void platformPermissionsHaveNoTenantShapedScope() {
        // Groups and tenant scopes are tenant-side concepts; a PLATFORM permission with a GROUP
        // floor would be a scope no assignment could ever satisfy.
        for (Permission permission : Permission.values()) {
            if (permission.side() == PermissionSide.PLATFORM) {
                assertThat(permission.minScope())
                    .as("%s is platform-side", permission.code())
                    .isEqualTo(PermissionScope.PLATFORM);
            } else {
                assertThat(permission.minScope())
                    .as("%s is tenant-side", permission.code())
                    .isNotEqualTo(PermissionScope.PLATFORM);
            }
        }
    }
}
