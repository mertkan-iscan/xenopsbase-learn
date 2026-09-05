package com.xenopsoftware.learn.catalog.structure;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.catalog.StubTokens;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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
 * The course tree through the real filter chain (T-5.2).
 *
 * <p>What is worth pinning here rather than trusting: that a reorder writes ONE row, that an
 * optional node is excluded from what a gate waits for, that one content item can be in several
 * courses at once, and that deleting an item a course points at is refused by the database rather
 * than by a check somebody has to remember.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class CourseStructureTest extends PostgresTestHarness {

    private static final String ACME = "acme-author~acme~TENANT";
    private static final String GLOBEX = "globex-author~globex~TENANT";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyEverything() {
        jdbc = new JdbcTemplate(dataSource);
        // Foreign-key order: nodes point at modules and content items, modules at courses.
        jdbc.update("DELETE FROM course_node");
        jdbc.update("DELETE FROM course_module");
        jdbc.update("DELETE FROM course");
        jdbc.update("DELETE FROM content_item");
    }

    @Test
    void aReorderWritesOneRowWhateverTheModuleSize() throws Exception {
        UUID course = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Onboarding\"}"));
        UUID module = idOf(post("/api/v1/courses/" + course + "/modules", ACME,
            "{\"title\":\"Week one\"}"));
        List<UUID> nodes = tenNodesIn(module);

        // Every node's updated_at before the move, so "one row changed" is measured rather than
        // asserted about the API's return value.
        long touchedBefore = jdbc.queryForObject(
            "SELECT count(*) FROM course_node WHERE updated_at > now() - interval '1 second'",
            Long.class);
        assertThat(touchedBefore).isEqualTo(10);
        jdbc.update("UPDATE course_node SET updated_at = now() - interval '1 hour'");

        // Move the last node to the front: the worst case for dense integers, which would rewrite
        // all ten.
        assertThat(send(request("/api/v1/courses/nodes/" + nodes.getLast() + "/position", ACME)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"afterNodeId\":null}"))).statusCode())
            .isEqualTo(200);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM course_node WHERE updated_at > now() - interval '1 minute'",
            Long.class))
            .as("a reorder is a single-row write; dense ordinals would have rewritten ten")
            .isEqualTo(1);

        assertThat(nodeIdsInOrder(course))
            .as("and the moved node really is first now")
            .startsWith(nodes.getLast());
    }

    @Test
    void aNodeCanBePlacedBetweenTwoOthersWithoutDisturbingThem() throws Exception {
        UUID course = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Onboarding\"}"));
        UUID module = idOf(post("/api/v1/courses/" + course + "/modules", ACME, "{\"title\":\"M\"}"));
        UUID first = idOf(addNode(module, publishedItem("A"), null));
        UUID third = idOf(addNode(module, publishedItem("C"), first));

        UUID second = idOf(addNode(module, publishedItem("B"), first));

        assertThat(nodeIdsInOrder(course)).containsExactly(first, second, third);
    }

    @Test
    void optionalNodesAreVisibleAndNeverBlockAGate() throws Exception {
        UUID course = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Onboarding\"}"));
        UUID module = idOf(post("/api/v1/courses/" + course + "/modules", ACME, "{\"title\":\"M\"}"));
        UUID required = idOf(addNode(module, publishedItem("Required"), null));
        UUID optional = idOf(send(request("/api/v1/courses/modules/" + module + "/nodes", ACME)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"contentItemId\":\"" + publishedItem("Optional") + "\",\"required\":false,"
                + "\"afterNodeId\":\"" + required + "\"}"))));

        // Both are in the tree a learner sees...
        assertThat(nodeIdsInOrder(course)).containsExactly(required, optional);
        // ...and only one of them is what a gate waits for.
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM course_node WHERE required", Long.class)).isEqualTo(1);
        assertThat(get("/api/v1/courses/" + course, ACME).body())
            .contains("\"required\":true", "\"required\":false");
    }

    @Test
    void oneContentItemAppearsInSeveralCoursesWithoutBeingCopied() throws Exception {
        UUID item = publishedItem("Fire safety");
        UUID onboarding = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Onboarding\"}"));
        UUID refresher = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Annual refresher\"}"));
        UUID first = idOf(post("/api/v1/courses/" + onboarding + "/modules", ACME, "{\"title\":\"M\"}"));
        UUID second = idOf(post("/api/v1/courses/" + refresher + "/modules", ACME, "{\"title\":\"M\"}"));

        addNode(first, item, null);
        addNode(second, item, null);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM course_node WHERE content_item_id = ?", Long.class, item))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_item", Long.class))
            .as("two references, one item -- copying it would give two that drift")
            .isEqualTo(1);
    }

    @Test
    void deletingAContentItemACourseReferencesIsRefusedByTheDatabase() throws Exception {
        UUID item = publishedItem("Fire safety");
        UUID course = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Onboarding\"}"));
        UUID module = idOf(post("/api/v1/courses/" + course + "/modules", ACME, "{\"title\":\"M\"}"));
        addNode(module, item, null);

        // There is no delete endpoint -- withdrawal is ARCHIVED (T-5.1). This proves the backstop
        // for the day somebody adds one: the foreign key refuses, rather than nodes vanishing out
        // of a course a learner is part-way through.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> jdbc.update("DELETE FROM content_item WHERE id = ?", item)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // Archiving is the supported path and leaves the course intact.
        assertThat(send(request("/api/v1/content-items/" + item + "/state", ACME)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"ARCHIVED\"}"))).statusCode())
            .isEqualTo(200);
        assertThat(nodeIdsInOrder(course)).hasSize(1);
    }

    @Test
    void onlyPublishedItemsCanBeAddedToACourse() throws Exception {
        UUID draft = idOf(post("/api/v1/content-items", ACME,
            "{\"type\":\"video\",\"title\":\"Unfinished\",\"payload\":{\"assetId\":\"a1\"}}"));
        UUID course = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Onboarding\"}"));
        UUID module = idOf(post("/api/v1/courses/" + course + "/modules", ACME, "{\"title\":\"M\"}"));

        HttpResponse<String> refused = addNode(module, draft, null);

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body())
            .as("the refusal tells the author what to do about it")
            .contains("PUBLISHED", "publish it first");
    }

    @Test
    void anotherCompanySeesNoneOfTheTree() throws Exception {
        UUID course = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Onboarding\"}"));
        UUID module = idOf(post("/api/v1/courses/" + course + "/modules", ACME, "{\"title\":\"M\"}"));
        addNode(module, publishedItem("A"), null);

        assertThat(get("/api/v1/courses/" + course, GLOBEX).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/courses", GLOBEX).body()).isEqualTo("[]");
        assertThat(send(request("/api/v1/courses/" + course + "/modules", GLOBEX)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Theirs\"}"))).statusCode())
            .isEqualTo(404);
    }

    @Test
    void aNodeMovesBetweenModulesWithoutBeingCopied() throws Exception {
        UUID course = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Onboarding\"}"));
        UUID one = idOf(post("/api/v1/courses/" + course + "/modules", ACME, "{\"title\":\"One\"}"));
        UUID two = idOf(post("/api/v1/courses/" + course + "/modules", ACME,
            "{\"title\":\"Two\",\"afterModuleId\":\"" + one + "\"}"));
        UUID node = idOf(addNode(one, publishedItem("A"), null));

        assertThat(send(request("/api/v1/courses/nodes/" + node + "/position", ACME)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"moduleId\":\"" + two + "\"}"))).statusCode())
            .isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM course_node", Long.class))
            .as("moved, not copied")
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT module_id FROM course_node WHERE id = ?", UUID.class, node)).isEqualTo(two);
    }

    @Test
    void rebalancingRenumbersOneModuleAndIsSomethingSomebodyAsksFor() throws Exception {
        UUID course = idOf(post("/api/v1/courses", ACME, "{\"title\":\"Onboarding\"}"));
        UUID module = idOf(post("/api/v1/courses/" + course + "/modules", ACME, "{\"title\":\"M\"}"));
        UUID first = idOf(addNode(module, publishedItem("A"), null));
        UUID last = idOf(addNode(module, publishedItem("Z"), first));
        // Thirty insertions at the same point, which is what grows an ordinal long.
        for (int i = 0; i < 30; i++) {
            addNode(module, publishedItem("mid" + i), first);
        }
        String longest = jdbc.queryForObject(
            "SELECT max(length(ordinal::text)) FROM course_node", String.class);
        assertThat(Integer.parseInt(longest))
            .as("subdividing one point really does grow the number")
            .isGreaterThan(10);

        assertThat(post("/api/v1/courses/modules/" + module + "/rebalance", ACME, "{}").body())
            .contains("\"renumbered\":32");

        // Every ordinal is now a whole multiple of 1000, so the longest is the last one -- 32000
        // for 32 nodes. Asserted as "short and round" rather than as a fixed width, because the
        // width is a function of how many nodes the fixture happens to make.
        assertThat(jdbc.queryForObject(
            "SELECT max(ordinal) FROM course_node", BigDecimal.class))
            .isEqualByComparingTo("32000");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM course_node WHERE ordinal % 1000 <> 0", Long.class))
            .as("renumbering leaves no fractions behind")
            .isZero();
        assertThat(nodeIdsInOrder(course))
            .as("and renumbering preserved the order it found")
            .startsWith(first).endsWith(last);
    }

    private List<UUID> tenNodesIn(UUID module) throws Exception {
        List<UUID> ids = new java.util.ArrayList<>();
        UUID previous = null;
        for (int i = 0; i < 10; i++) {
            previous = idOf(addNode(module, publishedItem("Item " + i), previous));
            ids.add(previous);
        }
        return ids;
    }

    /** A published content item, since only PUBLISHED accepts new references (T-5.1). */
    private UUID publishedItem(String title) throws Exception {
        UUID id = idOf(post("/api/v1/content-items", ACME,
            "{\"type\":\"video\",\"title\":\"" + title + "\",\"payload\":{\"assetId\":\"a\"}}"));
        send(request("/api/v1/content-items/" + id + "/state", ACME)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"state\":\"PUBLISHED\"}")));
        return id;
    }

    private HttpResponse<String> addNode(UUID module, UUID item, UUID after) throws Exception {
        String body = "{\"contentItemId\":\"" + item + "\""
            + (after == null ? "" : ",\"afterNodeId\":\"" + after + "\"") + "}";
        return post("/api/v1/courses/modules/" + module + "/nodes", ACME, body);
    }

    private List<UUID> nodeIdsInOrder(UUID course) {
        return jdbc.queryForList("""
            SELECT n.id FROM course_node n
              JOIN course_module m ON m.id = n.module_id
             WHERE m.course_id = ?
             ORDER BY m.ordinal, m.id, n.ordinal, n.id
            """, UUID.class, course);
    }

    private static UUID idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(200);
        return UUID.fromString(response.body().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1"));
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
