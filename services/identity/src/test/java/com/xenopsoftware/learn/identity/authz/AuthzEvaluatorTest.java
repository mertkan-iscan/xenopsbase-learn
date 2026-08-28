package com.xenopsoftware.learn.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.identity.PostgresTestHarness;
import com.xenopsoftware.learn.identity.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The whole T-2.4 surface through a real server: catalog-checked {@code hasPermission},
 * once-per-request resolution, the loud failure on a check the catalog does not know, and the
 * disclosure rule's four corners.
 *
 * <p>The probe controller and a grants-by-test resolver live here in test sources — production
 * has no grant source until T-2.2/T-2.3, and a permissive stub in main code is exactly the
 * mistake {@code UngrantedResolver}'s javadoc refuses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({StubTokens.class, AuthzEvaluatorTest.Grants.class, AuthzEvaluatorTest.Probe.class})
@org.springframework.test.context.ActiveProfiles("authz-probe")
class AuthzEvaluatorTest extends PostgresTestHarness {

    /**
     * Profile-gated for the same reason the probe controller is, and it is worth stating twice:
     * a @TestConfiguration nested in a test class is still a class on the scan path, so this
     * @Primary resolver was silently replacing the real one in every OTHER test context —
     * which made T-2.3's assignments resolve to nothing and looked exactly like a bug in the
     * resolver. If a test class contributes beans, gate them on its profile.
     */
    @org.springframework.context.annotation.Profile("authz-probe")
    @TestConfiguration(proxyBeanMethods = false)
    static class Grants {
        static final Map<String, GrantedPermissions> BY_SUBJECT = new ConcurrentHashMap<>();
        static final AtomicInteger RESOLUTIONS = new AtomicInteger();

        @Bean
        @Primary
        PermissionsResolver grantsBySubject() {
            return caller -> {
                RESOLUTIONS.incrementAndGet();
                return BY_SUBJECT.getOrDefault(caller.getSubject(), GrantedPermissions.none());
            };
        }

        @Bean
        Collaborator collaborator() {
            return new Collaborator();
        }
    }

    static class Collaborator {
        @PreAuthorize("hasPermission('group', 'read')")
        public String groups() {
            return "groups";
        }
    }

    // Profile-gated because component scanning walks test-classes too: without the guard this
    // probe -- ghost check and all -- registers itself into every other test's context, and
    // CatalogCoverageTest rightly fails on an endpoint checking a permission that does not exist.
    @org.springframework.context.annotation.Profile("authz-probe")
    @RestController
    @RequestMapping("/api/authz-probe")
    static class Probe {
        private final Collaborator collaborator;

        Probe(Collaborator collaborator) {
            this.collaborator = collaborator;
        }

        @GetMapping("/view")
        @PreAuthorize("hasPermission('user', 'read')")
        public Map<String, String> view() {
            return Map.of("saw", "users");
        }

        @PostMapping("/edit")
        @PreAuthorize("hasPermission('user', 'manage')")
        public Map<String, String> edit() {
            return Map.of("did", "edit");
        }

        /** One request, two checks — the resolver must still be asked exactly once. */
        @GetMapping("/two-checks")
        @PreAuthorize("hasPermission('user', 'read')")
        public Map<String, String> twoChecks() {
            return Map.of("also", collaborator.groups());
        }

        @GetMapping("/ghost")
        @PreAuthorize("hasPermission('ghost', 'haunt')")
        public Map<String, String> ghost() {
            return Map.of("never", "reached");
        }
    }

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void clearGrants() {
        Grants.BY_SUBJECT.clear();
    }

    @Test
    void aGrantedCheckPasses() throws Exception {
        grant("viewer", Permission.USER_READ);
        HttpResponse<String> response = get("/view", "viewer~acme~TENANT");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("users");
    }

    @Test
    void aDeniedReadIs404BecauseTheReadIsTheDisclosureGate() throws Exception {
        HttpResponse<String> response = get("/view", "blind~acme~TENANT");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void aDeniedWriteIs403ForACallerWhoCanRead() throws Exception {
        grant("reader", Permission.USER_READ);
        HttpResponse<String> response = post("/edit", "reader~acme~TENANT");
        // They can see users, so hiding the endpoint now would be theater; what they lack is
        // the manage permission, and 403 says exactly that.
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void aDeniedWriteIs404ForACallerWhoCannotEvenRead() throws Exception {
        HttpResponse<String> response = post("/edit", "blind~acme~TENANT");
        // A 403 here would confirm to a blind caller that what they guessed at is real.
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void theWrongSideIsDeniedBeforeGrantsAreEvenConsulted() throws Exception {
        // A platform token whose subject somehow has tenant grants: the side pre-filter
        // (ADR-0103) must deny anyway.
        grant("ops", Permission.USER_READ);
        HttpResponse<String> response = get("/view", "ops~~PLATFORM");
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void aCheckAgainstANonexistentPermissionFailsLoudlyNeverSilently() throws Exception {
        grant("viewer", Permission.USER_READ);
        HttpResponse<String> response = get("/ghost", "viewer~acme~TENANT");
        // The AuthoritiesConstants lesson: a check no token could ever satisfy must be a 500
        // with a name in it, not a deny that reads as security working.
        assertThat(response.statusCode()).isEqualTo(500);
    }

    @Test
    void twoChecksInOneRequestResolveThePermissionSetOnce() throws Exception {
        Grants.BY_SUBJECT.put("sub-viewer", new GrantedPermissions(Map.of(
            Permission.USER_READ, Set.of(ScopeGrant.tenantWide()),
            Permission.GROUP_READ, Set.of(ScopeGrant.tenantWide()))));

        int before = Grants.RESOLUTIONS.get();
        HttpResponse<String> response = get("/two-checks", "viewer~acme~TENANT");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("groups");
        assertThat(Grants.RESOLUTIONS.get() - before)
            .as("controller check plus collaborator check must share one resolution")
            .isEqualTo(1);
    }

    private static void grant(String username, Permission permission) {
        Grants.BY_SUBJECT.put("sub-" + username,
            new GrantedPermissions(Map.of(permission, Set.of(ScopeGrant.tenantWide()))));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(request(path, token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return http.send(request(path, token).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String token) {
        return HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getProperty("local.server.port") + "/api/authz-probe" + path))
            .header("Authorization", "Bearer " + token);
    }
}
