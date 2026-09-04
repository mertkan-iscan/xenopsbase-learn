package com.xenopsoftware.learn.streaming.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.streaming.PostgresTestHarness;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The webhook through the real filter chain (T-3.3): unauthenticated by design, refused unless
 * signed, and idempotent all the way through the HTTP layer rather than only in the service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookEndpointTest extends PostgresTestHarness {

    @Autowired
    private VideoUploadService uploads;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();
    private JdbcTemplate jdbc;
    private String ref;

    @BeforeEach
    void anAssetWaitingToEncode() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM provider_event");
        jdbc.update("DELETE FROM video_asset");
        ref = TenantContext.callWith("acme",
            () -> uploads.createVideo(3600, 900).target().providerRef());
    }

    @Test
    void anUnsignedDeliveryIsRefusedAndChangesNothing() throws Exception {
        HttpResponse<String> response = post(readyBody(), null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(state()).isEqualTo("PENDING_UPLOAD");
        // Nothing recorded either: an unverifiable body is not an event that happened.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_event", Long.class)).isZero();
    }

    @Test
    void aSignedDeliveryMovesTheAssetAndRepeatsAreStillSuccesses() throws Exception {
        assertThat(post(readyBody(), "local-development-only").statusCode()).isEqualTo(200);
        assertThat(state()).isEqualTo("READY");

        // A provider retries until it gets a 2xx, so a duplicate has to be a success -- and
        // still only one transition and one recorded event.
        assertThat(post(readyBody(), "local-development-only").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_event", Long.class)).isEqualTo(1);
    }

    @Test
    void theEndpointNeedsNoTokenAtAll() throws Exception {
        // Deliberate: the provider holds no credential of ours, and the signature IS the
        // credential. A 401 here would mean we had issued Cloudflare a token.
        HttpResponse<String> response = post(readyBody(), "local-development-only");
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private String readyBody() {
        return "{\"eventId\":\"evt-1\",\"providerRef\":\"" + ref
            + "\",\"state\":\"READY\",\"durationSeconds\":542.5}";
    }

    private HttpResponse<String> post(String body, String signature) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + environment.getProperty("local.server.port") + "/webhooks/media"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            request.header("X-Fake-Signature", signature);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String state() {
        return jdbc.queryForObject(
            "SELECT state FROM video_asset WHERE provider_ref = ?", String.class, ref);
    }
}
