package com.xenopsoftware.learn.catalog.content;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.catalog.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * T-5.1's fourth criterion, made falsifiable: <b>adding a content type touches the payload
 * validation and the player, and nothing else.</b>
 *
 * <p>The claim is easy to assert and easy to be wrong about, so this proves it the only way that
 * means anything — by actually adding a sixth type and then checking that nothing else was needed.
 * <b>The whole of the addition is the {@code @Bean} below.</b> No migration, no enum constant, no
 * switch gaining a case, no change to the resource, the service, the entity or the search. If a
 * future change makes a seventh type require more than this, this test still passes and the claim
 * quietly becomes false — so what it really pins is that the registry is the only seam, and the
 * reviewer's job is to notice if the bean below ever needs company.
 *
 * <p>Its own class with its own profile, for {@code AuthzEvaluatorTest}'s hard-won reason: a
 * {@code @TestConfiguration} nested in a test class is on the component-scan path, so an
 * un-profiled sixth type would silently join the registry of every other context and make
 * {@code ContentItemTest}'s type-list assertion fail somewhere far from here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sixth-type")
@Import({StubTokens.class, SixthTypeTest.APodcastType.class})
class SixthTypeTest extends PostgresTestHarness {

    private static final String ACME = "acme-author~acme~TENANT";

    /** The entire cost of a new content type. */
    @TestConfiguration(proxyBeanMethods = false)
    @Profile("sixth-type")
    static class APodcastType {

        @Bean
        ContentTypeDefinition podcastContentType() {
            return new ContentTypeDefinition() {
                @Override
                public String code() {
                    return "podcast";
                }

                @Override
                public String displayName() {
                    return "Podcast episode";
                }

                @Override
                public void validate(JsonNode payload) {
                    JsonNode episode = payload == null ? null : payload.get("episodeId");
                    if (episode == null || !episode.isTextual()) {
                        throw new IllegalArgumentException("A podcast item needs an 'episodeId'");
                    }
                }
            };
        }
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void emptyTheTable() {
        new JdbcTemplate(dataSource).update("DELETE FROM content_item");
    }

    @Test
    void aSixthTypeIsCreatable() throws Exception {
        HttpResponse<String> created = post("""
            {"type":"podcast","title":"Episode 1","payload":{"episodeId":"ep-1"},"tags":["audio"]}
            """);

        assertThat(created.statusCode())
            .as("the table took a type its migration has never heard of, which is the point of a "
                + "discriminator and a jsonb payload")
            .isEqualTo(200);
        assertThat(created.body()).contains("\"type\":\"podcast\"", "\"episodeId\":\"ep-1\"");
    }

    @Test
    void theSixthTypeValidatesItsOwnPayloadAndNobodyElsesRulesApply() throws Exception {
        assertThat(post("""
            {"type":"podcast","title":"No episode","payload":{}}
            """).statusCode())
            .as("its own validator refused it")
            .isEqualTo(400);

        assertThat(post("""
            {"type":"podcast","title":"Not a video","payload":{"assetId":"a1"}}
            """).statusCode())
            .as("and video's rule did not leak into it -- assetId means nothing to a podcast")
            .isEqualTo(400);
    }

    @Test
    void everythingElseWorksOnItWithoutKnowingWhatItIs() throws Exception {
        String id = post("""
            {"type":"podcast","title":"Episode 1","payload":{"episodeId":"ep-1"},"tags":["audio"]}
            """).body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // The lifecycle, search, tags and the type picker are the four things T-5.1 says must not
        // need touching. None of them has a line about podcasts.
        assertThat(state(id, "PUBLISHED").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/content-items?type=podcast").body()).contains("Episode 1");
        assertThat(get("/api/v1/content-items?tag=audio").body()).contains("Episode 1");
        assertThat(get("/api/v1/content-items?state=PUBLISHED").body()).contains("Episode 1");
        assertThat(get("/api/v1/content-items/types").body()).contains("podcast", "Podcast episode");
    }

    private HttpResponse<String> state(String id, String state) throws Exception {
        return send(request("/api/v1/content-items/" + id + "/state")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"" + state + "\"}")));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET());
    }

    private HttpResponse<String> post(String body) throws Exception {
        return send(request("/api/v1/content-items")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(
            URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
            .header("Authorization", "Bearer " + ACME);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
