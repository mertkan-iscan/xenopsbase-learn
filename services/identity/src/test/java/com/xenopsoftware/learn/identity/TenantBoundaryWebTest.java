package com.xenopsoftware.learn.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * The tenant boundary, tested at the real entry point (T-1.1).
 *
 * <p>Real server, real filter chain, real pooled request threads — because the failure this
 * boundary exists to prevent lives in exactly the machinery MockMvc replaces with the calling
 * thread.
 *
 * <p>The one substitution is the {@link JwtDecoder}: tokens here are decoded by a stub instead of
 * verified against Keycloak. That is the right seam, not a shortcut — signature, issuer and expiry
 * checks are Spring Security's contract, and everything this test owns starts <i>after</i> a token
 * has been verified: which claims are read, what a forged header cannot do, and what a pooled
 * thread carries into the next request.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        // A deliberately tiny request pool. Two threads serving two tenants' interleaved
        // requests is the highest-pressure version of the leak T-1.1 warns about: every thread
        // is reused by the other tenant almost immediately.
        "server.tomcat.threads.max=2",
        "server.tomcat.threads.min-spare=2"
    })
@Import(StubTokens.class)
class TenantBoundaryWebTest extends PostgresTestHarness {

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void unauthenticatedRequestsGetNothing() throws Exception {
        HttpResponse<String> response = get(null);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void aForgedHeaderCannotChangeTheResolvedTenant() throws Exception {
        // The convenience someone reasonable will one day propose, sent by an attacker instead.
        HttpResponse<String> response = get("acme-learner~acme~TENANT", "X-Tenant-Id", "globex");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"tenant\":\"acme\"");
        assertThat(response.body()).doesNotContain("globex");
    }

    @Test
    void aPlatformTokenBindsThePlatformsOwnTenant() throws Exception {
        HttpResponse<String> response = get("platform-admin~~PLATFORM");
        assertThat(response.statusCode()).isEqualTo(200);
        // This assertion used to read "null", and the change is deliberate (T-1.5). Binding
        // nothing was right while the platform had no rows: a sentinel with nothing behind it
        // is a filter that matches nothing, silently. The platform now has its own tenant and
        // its own people in it (ADR-0104 applies to staff too), so the sentinel matches
        // exactly the platform's data -- which is what makes binding it correct rather than
        // dangerous.
        assertThat(response.body()).contains("\"tenant\":\"__platform\"");
    }

    @Test
    void aPlatformTokenCarryingACustomersTenantClaimNeverReachesThatCustomer() throws Exception {
        // The part that has NOT changed, and the part that matters: a platform token claiming
        // to be acme is still not acme. Staff are bound to the platform's own tenant, never to
        // a customer's -- reaching into a customer's data is a deliberate, audited act
        // (provisioning, and impersonation in T-2.8), never a side effect of being staff.
        HttpResponse<String> response = get("confused-platform-admin~acme~PLATFORM");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"tenant\":\"__platform\"");
        assertThat(response.body()).doesNotContain("acme");
    }

    @Test
    void interleavedTenantsOnTwoServerThreadsNeverCrossContaminate() throws Exception {
        int requests = 200;
        ExecutorService clients = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<String>> failures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                String tenant = i % 2 == 0 ? "acme" : "globex";
                String token = tenant + "-learner~" + tenant + "~TENANT";
                failures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        HttpResponse<String> response = get(token);
                        if (response.statusCode() != 200) {
                            return "status " + response.statusCode() + " for " + tenant;
                        }
                        if (!response.body().contains("\"tenant\":\"" + tenant + "\"")) {
                            return "asked as " + tenant + ", answered " + response.body();
                        }
                        return null;
                    } catch (Exception e) {
                        return e.toString();
                    }
                }, clients));
            }
            List<String> crossed = failures.stream()
                .map(CompletableFuture::join)
                .filter(java.util.Objects::nonNull)
                .toList();
            assertThat(crossed).isEmpty();
        } finally {
            clients.shutdown();
        }
    }

    private HttpResponse<String> get(String token, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
            "http://localhost:" + environment.getProperty("local.server.port") + "/api/v1/auth-info"));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
