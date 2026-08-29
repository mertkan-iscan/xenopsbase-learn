package com.xenopsoftware.learn.identity.config;

import com.xenopsoftware.learn.identity.authz.Permission;
import com.xenopsoftware.learn.identity.authz.SystemRole;
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
            catalog.append("""

                ## Role templates

                Every customer starts with these. Clone one to edit it: the templates are
                owned by the platform and re-projected from code, so a change here reaches
                every customer and no clone.

                | Role | Side | What it can do |
                |---|---|---|
                """);
            for (SystemRole role : SystemRole.values()) {
                catalog.append("| ").append(role.displayName()).append(" | ")
                    .append(role.side()).append(" | ")
                    .append(role.reach()).append(" |\n");
            }
            catalog.append("""

                ## Granting rules

                **Nobody grants what they do not hold.** Integrators hit this, so it is stated
                rather than discovered:

                - Putting a permission into a role — editing it, or cloning a template —
                  requires holding that permission yourself, anywhere.
                - Assigning a role requires holding everything it carries **at that scope or
                  wider**. Granting at `TENANT` requires holding at `TENANT` or `PLATFORM`;
                  holding at `GROUP` is never enough.
                - A tenant role can never carry a platform-side permission. That is checked
                  separately from the rule above, so it holds even for a caller who somehow
                  holds one.
                - Refusals answer `403` and are recorded in the audit log, including what was
                  missing.

                A tenant with no assignments yet cannot grant anything at all, including to its
                own first administrator: the first grant arrives with provisioning, not from
                inside the tenant.
                """);
            if (openApi.getInfo() == null) {
                openApi.setInfo(new Info().title("identity"));
            }
            String description = openApi.getInfo().getDescription();
            openApi.getInfo().setDescription((description == null ? "" : description) + catalog);
        };
    }
}
