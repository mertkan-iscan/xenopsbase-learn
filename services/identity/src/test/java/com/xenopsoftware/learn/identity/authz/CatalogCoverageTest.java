package com.xenopsoftware.learn.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.identity.PostgresTestHarness;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Both directions of T-2.1's honesty rule, held by a test instead of a review.
 *
 * <p>Forward: every endpoint under {@code /api/**} names the permission it checks, or is
 * consciously listed as authentication-only with the reason. A new endpoint in neither map
 * fails this test with instructions — which is the moment its author decides its authorization,
 * instead of the moment a pen test does.
 *
 * <p>Reverse: every catalog entry is checked somewhere or carries the task that will enforce
 * it. When T-2.4 lands the evaluator, {@link #PERMISSIONED} stops being a hand-kept map and
 * starts being read off the enforcement mechanism itself; until then these maps are the
 * declared intent the build holds us to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogCoverageTest extends PostgresTestHarness {

    /** Endpoint → the catalog permission its handler checks. Empty until T-2.4's evaluator. */
    private static final Map<String, Permission> PERMISSIONED = Map.of();

    /** Endpoint → why authentication alone is the whole check. */
    private static final Map<String, String> AUTH_ONLY = Map.of(
        "AuthInfoResource#authInfo",
            "reports what the token itself says; gating it would hide the evidence it exists to show",
        "UserResource#me",
            "provisioning IS the first login -- there is no earlier moment at which a permission could be held",
        "UserResource#user",
            "display resolution for any tenant member; T-2.4 wires user:read here when the evaluator exists");

    /** Catalog entry → the task that will make some code path check it. */
    private static final Map<Permission, String> NOT_YET_ENFORCED = Map.of(
        Permission.USER_READ, "T-2.4 -- the evaluator, wired to /users/{id}",
        Permission.USER_MANAGE, "T-1.9 -- invite and deactivate endpoints",
        Permission.GROUP_READ, "T-1.3 -- the group tree",
        Permission.GROUP_MANAGE, "T-1.3 -- the group tree",
        Permission.ROLE_READ, "T-2.2 -- roles as rows",
        Permission.ROLE_MANAGE, "T-2.2 -- roles as rows",
        Permission.ROLE_ASSIGN, "T-2.3/T-2.6 -- scoped assignment behind the no-escalation rule",
        Permission.TENANT_PROVISION, "T-1.5 -- provisioning a company is an API call",
        Permission.TENANT_SUSPEND, "T-1.4 -- suspension stops at the gateway",
        Permission.SUPPORT_IMPERSONATE, "T-2.8 -- impersonation that is always visible afterwards");

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Test
    void everyApiEndpointDeclaresItsAuthorizationDecision() {
        Set<String> unaccounted = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            if (isApi(mapping)) {
                String endpoint = endpointId(handler);
                if (!PERMISSIONED.containsKey(endpoint) && !AUTH_ONLY.containsKey(endpoint)) {
                    unaccounted.add(endpoint);
                }
            }
        });
        assertThat(unaccounted)
            .as("endpoints that decided their authorization by omission -- add each to "
                + "PERMISSIONED with its catalog entry, or to AUTH_ONLY with the reason "
                + "authentication alone is the whole check")
            .isEmpty();
    }

    @Test
    void everyCheckedPermissionExistsLiveInTheCatalogTable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (Permission permission : PERMISSIONED.values()) {
            Boolean orphaned = jdbc.queryForObject(
                "SELECT orphaned FROM permission WHERE code = ?", Boolean.class, permission.code());
            assertThat(orphaned).as("%s must be seeded and live", permission.code()).isFalse();
        }
    }

    @Test
    void everyCatalogEntryIsCheckedSomewhereOrExplained() {
        for (Permission permission : EnumSet.allOf(Permission.class)) {
            boolean checked = PERMISSIONED.containsValue(permission);
            String excuse = NOT_YET_ENFORCED.get(permission);
            assertThat(checked || (excuse != null && !excuse.isBlank()))
                .as("%s is granted-but-never-checked -- the exact failure T-2.1 forbids. "
                    + "Wire it to an endpoint or list it in NOT_YET_ENFORCED with the task "
                    + "that will.", permission.code())
                .isTrue();
            assertThat(checked && excuse != null)
                .as("%s is both checked and excused; delete its NOT_YET_ENFORCED entry",
                    permission.code())
                .isFalse();
        }
    }

    @Test
    void theCatalogRendersIntoTheApiDocs() throws Exception {
        HttpResponse<String> docs = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getProperty("local.server.port") + "/v3/api-docs")).build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(docs.statusCode()).isEqualTo(200);
        for (Permission permission : Permission.values()) {
            assertThat(docs.body()).contains(permission.code());
        }
    }

    private static boolean isApi(RequestMappingInfo mapping) {
        return mapping.getPathPatternsCondition() != null
            && mapping.getPathPatternsCondition().getPatternValues().stream()
                .anyMatch(pattern -> pattern.startsWith("/api/"));
    }

    private static String endpointId(HandlerMethod handler) {
        return handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
    }
}
