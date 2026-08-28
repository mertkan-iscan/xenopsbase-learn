package com.xenopsoftware.learn.identity.config;

import com.xenopsoftware.learn.identity.authz.Permission;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Renders the permission catalog into the API docs (T-2.1). Integrators granting machine
 * credentials (T-8.4) need the list of grantable permissions, and the docs are where they look —
 * built from the enum at runtime, so the rendered list can never drift from the catalog the way
 * a hand-maintained page would.
 */
@Configuration(proxyBeanMethods = false)
public class PermissionCatalogDocs {

    @Bean
    OpenApiCustomizer permissionCatalog() {
        return openApi -> {
            StringBuilder catalog = new StringBuilder("""

                ## Permission catalog

                Every grantable permission, `resource:action`. Side says who can hold it \
                (tenant members or platform staff); minimum scope is the narrowest assignment \
                that makes sense for it.

                | Permission | Side | Minimum scope |
                |---|---|---|
                """);
            for (Permission permission : Permission.values()) {
                catalog.append("| `").append(permission.code()).append("` | ")
                    .append(permission.side()).append(" | ")
                    .append(permission.minScope()).append(" |\n");
            }
            if (openApi.getInfo() == null) {
                openApi.setInfo(new Info().title("identity"));
            }
            String description = openApi.getInfo().getDescription();
            openApi.getInfo().setDescription((description == null ? "" : description) + catalog);
        };
    }
}
