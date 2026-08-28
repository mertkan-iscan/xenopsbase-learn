package com.xenopsoftware.learn.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.xenopsoftware.learn.identity.PostgresTestHarness;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Both directions of T-2.1's honesty rule, held by a test instead of a review — and since
 * T-2.4, read off the real enforcement mechanism rather than a hand-kept map: an endpoint's
 * permissions are whatever its {@code @PreAuthorize} actually checks.
 *
 * <p>Forward (T-2.4's closed-by-default criterion): every endpoint under {@code /api/**} either
 * carries a {@code hasPermission} check or is consciously listed as authentication-only with
 * the reason. A new endpoint with neither fails this test with instructions — its authorization
 * is decided at write time, not discovered at pen-test time.
 *
 * <p>Reverse: every catalog entry is checked by some endpoint or carries the task that will
 * enforce it, with a tripwire against being both.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogCoverageTest extends PostgresTestHarness {

    private static final Pattern HAS_PERMISSION =
        Pattern.compile("hasPermission\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*\\)");

    /**
     * The group endpoints (T-1.3) want group:read and group:manage, and BOTH the catalog entry
     * and the evaluator already exist — what does not exist is anything that can HOLD a
     * permission, because roles and scoped assignments are T-2.2 and T-2.3. Annotating now
     * would deny every caller including the product own admins, so this is a real gap with a
     * named owner rather than an oversight: until then any authenticated tenant member can
     * restructure their tenant tree.
     */
    private static final String GROUP_GAP =
        "T-2.2/T-2.3 -- group:read and group:manage are catalogued and the evaluator is live, "
        + "but nothing can hold a grant yet; annotating would deny everyone";

    /**
     * Role management (T-2.2) has the same shape of gap as the group endpoints, and it is the
     * sharpest one: the endpoints that BUILD authorization are themselves unauthorized, so any
     * authenticated tenant member can grant their tenant any tenant-side permission. T-2.3 is
     * what closes it, for these endpoints and every other one on this list at once.
     */
    private static final String ROLE_GAP =
        "T-2.3 -- role:read and role:manage are catalogued and the evaluator is live, but "
        + "nothing can hold a grant until assignments exist; annotating would deny everyone";

    /**
     * Assignments (T-2.3) are the last piece of the bootstrap, not a missing check: role:assign
     * can finally be HELD now that grants exist, but the first grant has nobody to grant it
     * (T-2.7 seeds the starting point) and the rule that must guard this path -- nobody grants
     * what they do not hold -- is T-2.6. Annotating before both would lock the product out of
     * its own authorization.
     */
    private static final String ASSIGN_GAP =
        "T-2.6/T-2.7 -- grants exist now, but the seeded starting point and the no-escalation "
        + "rule do not; annotating would leave nobody able to make the first assignment";

    /** Endpoint → why authentication alone is the whole check. */
    private static final Map<String, String> AUTH_ONLY = Map.ofEntries(
        Map.entry("AuthInfoResource#authInfo",
            "reports what the token itself says; gating it would hide the evidence it exists to show"),
        Map.entry("UserResource#me",
            "provisioning IS the first login -- there is no earlier moment at which a permission could be held"),
        Map.entry("UserResource#user",
            "display resolution for any tenant member; T-2.3 grants let user:read be wired here"),
        Map.entry("GroupResource#roots", GROUP_GAP),
        Map.entry("GroupResource#children", GROUP_GAP),
        Map.entry("GroupResource#reach", GROUP_GAP),
        Map.entry("GroupResource#create", GROUP_GAP),
        Map.entry("GroupResource#move", GROUP_GAP),
        Map.entry("GroupResource#delete", GROUP_GAP),
        Map.entry("GroupResource#addMember", GROUP_GAP),
        Map.entry("GroupResource#removeMember", GROUP_GAP),
        Map.entry("RoleResource#all", ROLE_GAP),
        Map.entry("RoleResource#get", ROLE_GAP),
        Map.entry("RoleResource#create", ROLE_GAP),
        Map.entry("RoleResource#rename", ROLE_GAP),
        Map.entry("RoleResource#setPermissions", ROLE_GAP),
        Map.entry("RoleResource#delete", ROLE_GAP),
        Map.entry("AssignmentResource#all", ASSIGN_GAP),
        Map.entry("AssignmentResource#ofRole", ASSIGN_GAP),
        Map.entry("AssignmentResource#grant", ASSIGN_GAP),
        Map.entry("AssignmentResource#revoke", ASSIGN_GAP),
        Map.entry("AssignmentResource#reach",
            "shows the caller their OWN reach and nothing about anyone else; there is no wider "
            + "answer to gate, and a caller with no grants sees an empty reach"));

    /** Catalog entry → the task that will make some code path check it. */
    private static final Map<Permission, String> NOT_YET_ENFORCED = Map.of(
        Permission.USER_READ, "T-2.2/T-2.3 -- the evaluator is live but no grant source can hold this yet",
        Permission.USER_MANAGE, "T-1.9 -- invite and deactivate endpoints",
        Permission.GROUP_READ, "T-2.2/T-2.3 -- GroupResource exists; grants to check against do not",
        Permission.GROUP_MANAGE, "T-2.2/T-2.3 -- GroupResource exists; grants to check against do not",
        Permission.ROLE_READ, "T-2.3 -- RoleResource exists; grants to check against do not",
        Permission.ROLE_MANAGE, "T-2.3 -- RoleResource exists; grants to check against do not",
        Permission.ROLE_ASSIGN, "T-2.6/T-2.7 -- AssignmentResource exists; the no-escalation rule and the seeded first grant do not",
        Permission.TENANT_PROVISION, "T-1.5 -- provisioning a company is an API call",
        Permission.TENANT_SUSPEND, "T-1.4 -- suspension stops at the gateway",
        Permission.SUPPORT_IMPERSONATE, "T-2.8 -- impersonation that is always visible afterwards");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Test
    void everyApiEndpointDeclaresItsAuthorizationDecision() {
        Set<String> unaccounted = new TreeSet<>();
        forEachApiEndpoint((endpoint, handler) -> {
            if (!AUTH_ONLY.containsKey(endpoint) && checkedPermissions(endpoint, handler).isEmpty()) {
                unaccounted.add(endpoint);
            }
        });
        assertThat(unaccounted)
            .as("endpoints that decided their authorization by omission -- add a "
                + "@PreAuthorize(\"hasPermission('resource', 'action')\") check, or list the "
                + "endpoint in AUTH_ONLY with the reason authentication alone is the whole check")
            .isEmpty();
    }

    @Test
    void everyCheckedPermissionExistsLiveInTheCatalogTable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        forEachApiEndpoint((endpoint, handler) -> {
            for (Permission permission : checkedPermissions(endpoint, handler)) {
                Boolean orphaned = jdbc.queryForObject(
                    "SELECT orphaned FROM permission WHERE code = ?", Boolean.class, permission.code());
                assertThat(orphaned)
                    .as("%s (checked by %s) must be seeded and live", permission.code(), endpoint)
                    .isFalse();
            }
        });
    }

    @Test
    void everyCatalogEntryIsCheckedSomewhereOrExplained() {
        Set<Permission> checked = EnumSet.noneOf(Permission.class);
        forEachApiEndpoint((endpoint, handler) -> checked.addAll(checkedPermissions(endpoint, handler)));

        for (Permission permission : EnumSet.allOf(Permission.class)) {
            String excuse = NOT_YET_ENFORCED.get(permission);
            assertThat(checked.contains(permission) || (excuse != null && !excuse.isBlank()))
                .as("%s is granted-but-never-checked -- the exact failure T-2.1 forbids. "
                    + "Wire it to an endpoint or list it in NOT_YET_ENFORCED with the task "
                    + "that will.", permission.code())
                .isTrue();
            assertThat(checked.contains(permission) && excuse != null)
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

    /**
     * The permissions an endpoint really checks, read from its merged {@code @PreAuthorize}.
     * An expression that exists but names no {@code hasPermission} is its own failure: checks
     * name permissions (the ArchUnit rule separately rejects role names).
     */
    private Set<Permission> checkedPermissions(String endpoint, HandlerMethod handler) {
        PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        }
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        if (annotation == null) {
            return permissions;
        }
        Matcher checks = HAS_PERMISSION.matcher(annotation.value());
        while (checks.find()) {
            String code = checks.group(1) + ":" + checks.group(2);
            permissions.add(Permission.byCode(code).orElseGet(() -> {
                fail("%s checks '%s', which is not in the Permission catalog", endpoint, code);
                return null;
            }));
        }
        if (permissions.isEmpty()) {
            fail("%s has @PreAuthorize(\"%s\") with no hasPermission check -- checks name a "
                + "catalog permission", endpoint, annotation.value());
        }
        return permissions;
    }

    private void forEachApiEndpoint(java.util.function.BiConsumer<String, HandlerMethod> visit) {
        Map<String, HandlerMethod> endpoints = new TreeMap<>();
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            if (isApi(mapping)) {
                endpoints.put(endpointId(handler), handler);
            }
        });
        endpoints.forEach(visit);
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
