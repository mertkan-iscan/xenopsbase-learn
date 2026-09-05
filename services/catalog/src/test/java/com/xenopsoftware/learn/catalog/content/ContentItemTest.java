package com.xenopsoftware.learn.catalog.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.catalog.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Content items through the real filter chain (T-5.1).
 *
 * <p>The two criteria worth naming: the state machine's refusals, and the claim that adding a
 * content type touches the payload validation and the player and nothing else — which
 * {@link SixthTypeTest} proves separately, because proving it needs a sixth type to exist and a
 * type that exists in this context would make every count here wrong.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class ContentItemTest extends PostgresTestHarness {

    private static final String ACME = "acme-author~acme~TENANT";
    private static final String GLOBEX = "globex-author~globex~TENANT";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void emptyTheTable() {
        emptyEveryTable(dataSource);
    }

    @org.junit.jupiter.api.AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
    }

    @Test
    void anItemIsCreatedAsADraftWhateverTheCallerWanted() throws Exception {
        HttpResponse<String> created = post("/api/v1/content-items", ACME, """
            {"type":"video","title":"Fire safety","payload":{"assetId":"asset-1"},
             "state":"PUBLISHED","tags":["Safety"," safety ","Onboarding"]}
            """);

        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.body())
            .as("no request field can create something already published")
            .contains("\"state\":\"DRAFT\"");
        assertThat(created.body())
            .as("tags are normalised on the way in, so one label is not two")
            .contains("\"tags\":[\"safety\",\"onboarding\"]");
    }

    @Test
    void aPayloadThatItsTypeCannotMeanIsRefused() throws Exception {
        assertThat(post("/api/v1/content-items", ACME, """
            {"type":"video","title":"No asset","payload":{}}
            """).statusCode()).isEqualTo(400);

        assertThat(post("/api/v1/content-items", ACME, """
            {"type":"hologram","title":"Not a type","payload":{}}
            """).statusCode())
            .as("an unknown type is the caller asking for something that does not exist")
            .isEqualTo(400);

        assertThat(count()).isZero();
    }

    @Test
    void publishedNeverGoesBackToDraft() throws Exception {
        UUID id = idOf(post("/api/v1/content-items", ACME, """
            {"type":"video","title":"Fire safety","payload":{"assetId":"asset-1"}}
            """));

        assertThat(state(id, ACME, "PUBLISHED").statusCode()).isEqualTo(200);

        HttpResponse<String> back = state(id, ACME, "DRAFT");
        assertThat(back.statusCode())
            .as("409 not 400: the request is fine, the item's state is the conflict")
            .isEqualTo(409);
        assertThat(back.body()).contains("T-5.7");

        // Archived, then restored -- the one round trip that IS allowed.
        assertThat(state(id, ACME, "ARCHIVED").statusCode()).isEqualTo(200);
        assertThat(state(id, ACME, "PUBLISHED").statusCode()).isEqualTo(200);
    }

    @Test
    void onlyPublishedItemsAcceptNewReferences() {
        // The rule T-5.2, T-5.3 and T-5.5 will all ask about, asserted on the enum so that three
        // callers cannot each grow their own copy of it.
        assertThat(ContentState.DRAFT.acceptsNewReferences()).isFalse();
        assertThat(ContentState.PUBLISHED.acceptsNewReferences()).isTrue();
        assertThat(ContentState.ARCHIVED.acceptsNewReferences())
            .as("archived keeps working for references that exist; it accepts no new ones")
            .isFalse();
    }

    @Test
    void anotherCompanyCannotSeeOrEditTheItemAtAll() throws Exception {
        UUID id = idOf(post("/api/v1/content-items", ACME, """
            {"type":"video","title":"Fire safety","payload":{"assetId":"asset-1"}}
            """));

        assertThat(get("/api/v1/content-items/" + id, GLOBEX).statusCode()).isEqualTo(404);
        assertThat(state(id, GLOBEX, "PUBLISHED").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/content-items", GLOBEX).body()).isEqualTo("[]");
        assertThat(get("/api/v1/content-items/" + id, ACME).statusCode()).isEqualTo(200);
    }

    @Test
    void searchNarrowsByTextTypeStateAndEveryRequestedTag() throws Exception {
        post("/api/v1/content-items", ACME, """
            {"type":"video","title":"Fire safety basics","payload":{"assetId":"a1"},
             "tags":["safety","onboarding"]}
            """);
        post("/api/v1/content-items", ACME, """
            {"type":"scorm","title":"Fire drill procedure","payload":{"packageId":"p1"},
             "tags":["safety"]}
            """);
        post("/api/v1/content-items", ACME, """
            {"type":"video","title":"Expense policy","payload":{"assetId":"a2"},"tags":["finance"]}
            """);

        assertThat(bodyOf("/api/v1/content-items?q=fire")).contains("Fire safety basics", "Fire drill");
        assertThat(bodyOf("/api/v1/content-items?q=FIRE"))
            .as("a person typing a title fragment does not think about case")
            .contains("Fire safety basics");
        assertThat(bodyOf("/api/v1/content-items?type=video")).contains("Fire safety basics", "Expense policy")
            .doesNotContain("Fire drill");
        assertThat(bodyOf("/api/v1/content-items?state=PUBLISHED"))
            .as("everything is still a draft")
            .isEqualTo("[]");

        // Two tags means BOTH: a person narrowing by two tags is narrowing.
        assertThat(bodyOf("/api/v1/content-items?tag=safety&tag=onboarding"))
            .contains("Fire safety basics").doesNotContain("Fire drill");
    }

    @Test
    void theTypeListIsWhateverTheRegistryHolds() throws Exception {
        String types = bodyOf("/api/v1/content-items/types");

        assertThat(types).contains("video", "scorm", "cmi5", "slides", "test");
        assertThat(types)
            .as("the picker is derived from the registry, so it cannot drift from what validates")
            .doesNotContain("hologram");
    }

    private long count() {
        Long rows = new JdbcTemplate(dataSource)
            .queryForObject("SELECT count(*) FROM content_item", Long.class);
        return rows == null ? 0 : rows;
    }

    private static UUID idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(200);
        return UUID.fromString(response.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
    }

    private String bodyOf(String path) throws Exception {
        return get(path, ACME).body();
    }

    private HttpResponse<String> state(UUID id, String token, String state) throws Exception {
        return send(request("/api/v1/content-items/" + id + "/state", token)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"" + state + "\"}")));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(request(path, token).GET());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return send(request(path, token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpRequest.Builder request(String path, String token) {
        return HttpRequest.newBuilder(
            URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
            .header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
