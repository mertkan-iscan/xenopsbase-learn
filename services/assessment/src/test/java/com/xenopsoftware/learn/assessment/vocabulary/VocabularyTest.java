package com.xenopsoftware.learn.assessment.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.assessment.PostgresTestHarness;
import com.xenopsoftware.learn.assessment.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * The tag and difficulty vocabularies (T-6.1).
 *
 * <p>What is being protected here is a <i>draw</i>, not a list screen. "Five medium questions
 * tagged fire-safety" resolves against whatever rows match, so two spellings of one tag are two
 * populations and an author who mistypes gets a shorter exam rather than an error — discovered
 * after it has been sat. Every refusal below exists to make that a rejected write at authoring
 * time instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class VocabularyTest extends PostgresTestHarness {

    private static final String ACME = "acme-author~acme~TENANT";
    private static final String GLOBEX = "globex-author~globex~TENANT";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void emptyTheTables() {
        emptyEveryTable(dataSource);
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
    }

    @Test
    void aTagIsAddedOnceAndOnlyOnce() throws Exception {
        assertThat(post("/api/v1/vocabulary/tags", ACME, "{\"tag\":\"fire-safety\"}").statusCode())
            .isEqualTo(201);

        HttpResponse<String> again =
            post("/api/v1/vocabulary/tags", ACME, "{\"tag\":\"Fire-Safety\"}");

        // Case-insensitively, because a vocabulary holding both has stopped being a vocabulary.
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(get("/api/v1/vocabulary/tags", ACME).body()).contains("fire-safety");
    }

    @Test
    void aVocabularyBelongsToOneCompany() throws Exception {
        post("/api/v1/vocabulary/tags", ACME, "{\"tag\":\"fire-safety\"}");

        assertThat(get("/api/v1/vocabulary/tags", GLOBEX).body()).doesNotContain("fire-safety");
        // And the other company may use the same word, which is the whole reason it is per tenant.
        assertThat(post("/api/v1/vocabulary/tags", GLOBEX, "{\"tag\":\"fire-safety\"}").statusCode())
            .isEqualTo(201);
    }

    @Test
    void difficultiesComeBackInRankOrderRatherThanAlphabetically() throws Exception {
        post("/api/v1/vocabulary/difficulties", ACME, "{\"code\":\"Easy\",\"rank\":1}");
        post("/api/v1/vocabulary/difficulties", ACME, "{\"code\":\"Hard\",\"rank\":3}");
        post("/api/v1/vocabulary/difficulties", ACME, "{\"code\":\"Medium\",\"rank\":2}");

        String levels = get("/api/v1/vocabulary/difficulties", ACME).body();

        // Alphabetically this would be Easy, Hard, Medium — which is exactly the order that makes
        // "medium or harder" mean the wrong thing.
        assertThat(levels.indexOf("Easy")).isLessThan(levels.indexOf("Medium"));
        assertThat(levels.indexOf("Medium")).isLessThan(levels.indexOf("Hard"));
    }

    @Test
    void twoLevelsMayNotShareARank() throws Exception {
        post("/api/v1/vocabulary/difficulties", ACME, "{\"code\":\"Medium\",\"rank\":2}");

        HttpResponse<String> clash =
            post("/api/v1/vocabulary/difficulties", ACME, "{\"code\":\"Moderate\",\"rank\":2}");

        assertThat(clash.statusCode()).isEqualTo(409);
        assertThat(clash.body()).contains("ambiguous");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
