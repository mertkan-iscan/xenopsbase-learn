package com.xenopsoftware.learn.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xenopsoftware.learn.identity.authz.AuthzFixtures;
import com.xenopsoftware.learn.identity.authz.Permission;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two companies, the same-looking data in both, and every endpoint in the OpenAPI document
 * attempted across the boundary (T-1.6).
 *
 * <p><b>Why this is its own test rather than a case inside the tenancy work.</b> ADR-0102 moved
 * isolation out of Keycloak's per-realm schemas and into our own code. That trade is only sound
 * while the isolation is proved continuously — and a test written once during the tenancy work
 * and never extended produces confidence proportional to its age rather than its coverage.
 *
 * <p><b>So nothing here is a hand-written list of endpoints.</b> The document at
 * {@code /v3/api-docs} is the list, so an endpoint added tomorrow is probed tomorrow, and one
 * this walker cannot construct a request for <em>fails the build</em> rather than being skipped
 * quietly. That is the difference between a test that covers the API and a test that covered the
 * API on the day it was written.
 *
 * <p><b>The caller holds everything.</b> Both companies' probing admins hold every tenant-side
 * permission at TENANT scope, which is what makes a refusal mean something: it cannot be
 * "you lack the permission", so it can only be "that row is not in your company".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubTokens.class)
class CrossTenantIsolationTest extends PostgresTestHarness {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");

    /**
     * Path variables that are not identifiers. Anything else non-UUID fails the walk rather than
     * being guessed at — a new one is a decision, and a guess would silently probe nothing.
     */
    private static final Map<String, String> LITERALS = Map.of(
        "resource", "group",
        "action", "read");

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private JdbcTemplate jdbc;
    private JsonNode spec;
    private Company acme;
    private Company globex;

    /**
     * One company's rows, and the identifiers that must never appear in the other company's
     * responses.
     */
    private record Company(String tenant, String token, UUID group, UUID childGroup, UUID role,
                           UUID user, UUID assignment, String chiefEmail) {

        Set<String> identifiers() {
            return new LinkedHashSet<>(List.of(group.toString(), childGroup.toString(),
                role.toString(), user.toString(), assignment.toString(), chiefEmail));
        }
    }

    @BeforeEach
    void twoCompaniesWithDeliberatelyIndistinguishableData() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        removeEverything();
        // Same group names, same role name, same display name, same local part of the email at
        // a different domain. Nothing here is distinguishable by looking at it, so a test that
        // passes cannot be passing because the data happened to differ.
        acme = company("acme");
        globex = company("globex");
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        removeEverything();
    }

    @Test
    void noEndpointInTheDocumentReachesAnotherCompanysRows() throws Exception {
        // Both directions, because "A cannot see B" is half a boundary. They hold identical
        // shapes, so a rule that works one way and not the other is a real possibility.
        List<String> problems = new ArrayList<>();
        Walk out = walkEveryEndpoint(globex, acme, problems);
        Walk back = walkEveryEndpoint(acme, globex, problems);

        assertThat(problems).as("cross-tenant reachability").isEmpty();

        // Without these two, the test above passes on an empty document, and it passes on a
        // walk that quietly stopped planting identifiers -- both of which look exactly like a
        // boundary that holds.
        assertThat(out.probes() + back.probes())
            .as("every operation in the document, both directions")
            .isGreaterThanOrEqualTo(20);
        assertThat(out.addressed() + back.addressed())
            .as("requests that actually carried another company's identifier")
            .isGreaterThanOrEqualTo(20);
    }

    /** What one direction of the walk did: how many requests, and how many carried a real id. */
    private record Walk(int probes, int addressed) {}

    /**
     * The control the enumeration cannot provide for itself: the same requests, against the
     * caller's own company, must succeed. Without this, an API that answered 404 to everything
     * would pass the test above perfectly.
     */
    @Test
    void theSameRequestsAgainstTheCallersOwnCompanyStillWork() throws Exception {
        assertThat(as(acme.token(), "GET", "/api/v1/roles/" + acme.role(), null).statusCode())
            .isEqualTo(200);
        assertThat(as(acme.token(), "GET", "/api/v1/groups/" + acme.group() + "/children", null)
            .body()).contains(acme.childGroup().toString());
        assertThat(as(acme.token(), "GET", "/api/v1/users/" + acme.user(), null).statusCode())
            .isEqualTo(200);
        assertThat(as(acme.token(), "GET", "/api/v1/roles/" + acme.role() + "/assignments", null)
            .body()).contains(acme.assignment().toString());
        assertThat(as(acme.token(), "GET", "/api/v1/groups/" + acme.group() + "/reach", null)
            .body()).contains(acme.user().toString());
    }

    /**
     * Walks every operation in the document as {@code caller}, addressing {@code victim}'s rows.
     * Returns how many requests it made; appends one line per problem found.
     */
    private Walk walkEveryEndpoint(Company caller, Company victim, List<String> problems)
            throws Exception {
        if (spec == null) {
            spec = json.readTree(get("/v3/api-docs"));
        }
        JsonNode paths = spec.path("paths");
        assertThat(paths.isMissingNode()).as("/v3/api-docs has no paths").isFalse();

        int probes = 0;
        int addressed = 0;
        for (var pathEntry : paths.properties()) {
            String template = pathEntry.getKey();
            for (var operation : pathEntry.getValue().properties()) {
                String method = operation.getKey().toUpperCase(Locale.ROOT);
                if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                    continue;
                }

                Set<String> planted = new LinkedHashSet<>();
                String path = fillPath(template, victim, planted);
                String body = buildBody(operation.getValue(), victim, planted);

                HttpResponse<String> response = as(caller.token(), method, path, body);
                probes++;
                if (!planted.isEmpty()) {
                    addressed++;
                }
                inspect(method + " " + template, caller, victim, planted, response, problems);
            }
        }
        return new Walk(probes, addressed);
    }

    private void inspect(String operation, Company caller, Company victim, Set<String> planted,
            HttpResponse<String> response, List<String> problems) {
        String what = operation + " as " + caller.tenant() + " against " + victim.tenant();

        if (!planted.isEmpty()) {
            if (response.statusCode() / 100 == 2) {
                problems.add(what + " answered " + response.statusCode()
                    + " for another company's " + planted + " -- it must not exist here");
            } else if (operation.startsWith("GET") && response.statusCode() != 404) {
                // The disclosure rule (T-2.4): the caller holds the read permission, so a 403
                // would confirm the row exists somewhere. Only 404 says nothing.
                problems.add(what + " answered " + response.statusCode()
                    + " where existence is itself information -- reads must be 404");
            }
        }

        // What was sent back may echo what was sent -- an id in the request path appears in a
        // 404's body. Only the OTHER company's identifiers, the ones nothing in this request
        // mentioned, would have to have been read out of its rows.
        Set<String> mustNotAppear = new LinkedHashSet<>(victim.identifiers());
        mustNotAppear.removeAll(planted);
        for (String identifier : mustNotAppear) {
            if (response.body() != null && response.body().contains(identifier)) {
                problems.add(what + " returned " + victim.tenant() + "'s " + identifier);
            }
        }
    }

    /** Substitutes real identifiers for {@code {placeholders}}, recording what it planted. */
    private String fillPath(String template, Company victim, Set<String> planted) {
        Matcher variables = PATH_VARIABLE.matcher(template);
        StringBuilder path = new StringBuilder();
        while (variables.find()) {
            String name = variables.group(1);
            String literal = LITERALS.get(name);
            String value = literal != null ? literal
                : identifierFor(name, previousSegment(template, variables.start()), victim);
            if (literal == null) {
                planted.add(value);
            }
            variables.appendReplacement(path, Matcher.quoteReplacement(value));
        }
        variables.appendTail(path);
        return path.toString();
    }

    /**
     * Which row a path variable names: {@code {roleId}} is a role, and a bare {@code {id}} is
     * whatever the collection before it holds. An unmappable variable fails the build — a
     * skipped endpoint is exactly the silent gap this issue exists to prevent.
     */
    private UUID identifier(String variable, String collection, Company victim) {
        String subject = variable.equals("id") ? collection : variable.replaceAll("(?i)id$", "");
        return switch (subject.toLowerCase(Locale.ROOT)) {
            case "group", "groups", "parent" -> victim.group();
            case "role", "roles" -> victim.role();
            case "user", "users" -> victim.user();
            case "assignment", "assignments" -> victim.assignment();
            case "scope" -> victim.group();
            default -> fail("The isolation walk cannot address '" + variable + "' under '"
                + collection + "'. Teach it which row that names, or the endpoint using it is "
                + "silently unprobed (T-1.6).");
        };
    }

    private String identifierFor(String variable, String collection, Company victim) {
        // A tenant is addressed by its slug, not by a UUID, so it cannot go through the row
        // mapping above. Naming the victim's own slug is the sharper probe anyway: it asks
        // whether one company can suspend another, which is the worst thing a platform-shaped
        // endpoint could let a customer do (T-1.4).
        if (variable.equalsIgnoreCase("tenantId") || variable.equalsIgnoreCase("tenant")) {
            return victim.tenant();
        }
        return identifier(variable, collection, victim).toString();
    }

    /** The path segment before a variable: {@code /api/v1/groups/{id}} → {@code groups}. */
    private static String previousSegment(String template, int variableStart) {
        String before = template.substring(0, variableStart);
        String[] segments = before.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isBlank() && !segments[i].startsWith("{")) {
                return segments[i];
            }
        }
        return "";
    }

    /**
     * A request body from the operation's own schema, with every identifier in it pointing at
     * the other company. The body is where a cross-tenant reference is easiest to miss: nothing
     * about {@code POST /assignments} looks like it reads a row, and it reads three.
     */
    private String buildBody(JsonNode operation, Company victim, Set<String> planted) {
        JsonNode schemaRef = operation.path("requestBody").path("content")
            .path("application/json").path("schema").path("$ref");
        if (schemaRef.isMissingNode()) {
            return operation.path("requestBody").isMissingNode() ? null : "{}";
        }
        JsonNode schema = resolve(schemaRef.asText());
        ObjectNode body = json.createObjectNode();
        for (var property : schema.path("properties").properties()) {
            String name = property.getKey();
            JsonNode definition = property.getValue();
            String type = definition.path("type").asText("object");
            if ("string".equals(type) && "uuid".equals(definition.path("format").asText())) {
                String value = identifierFor(name, "", victim);
                planted.add(value);
                body.put(name, value);
            } else if ("string".equals(type)) {
                body.put(name, literalFor(name));
            } else if ("boolean".equals(type)) {
                body.put(name, false);
            } else if ("integer".equals(type) || "number".equals(type)) {
                body.put(name, 1);
            } else if ("array".equals(type)) {
                body.putArray(name).add(Permission.GROUP_READ.code());
            } else {
                fail("The isolation walk cannot fill '" + name + "' (" + type + ") in a request "
                    + "body. Teach it, or the endpoint using it is silently unprobed (T-1.6).");
            }
        }
        return body.toString();
    }

    /**
     * Values for the string fields that mean something. A field this does not know gets a
     * harmless string: it cannot carry a cross-tenant reference, because a reference is a UUID
     * and those are all planted above.
     */
    private static String literalFor(String name) {
        return switch (name) {
            case "scopeType" -> "TENANT";
            case "adminEmail" -> "probe@cross-tenant.test";
            case "tenantId" -> "cross-tenant-probe";
            default -> "cross-tenant probe";
        };
    }

    private JsonNode resolve(String ref) {
        JsonNode node = spec;
        for (String step : ref.replace("#/", "").split("/")) {
            node = node.path(step);
        }
        return node;
    }

    private HttpResponse<String> as(String token, String method, String path, String body)
            throws Exception {
        HttpRequest.BodyPublisher content = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .method(method, content)
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).build(),
            HttpResponse.BodyHandlers.ofString()).body();
    }

    private String base() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }

    private Company company(String tenant) throws Exception {
        String admin = tenant + "-admin";
        // The one thing that cannot be done over the API: the first grant. It comes from
        // outside the tenant's own rules (T-2.6), which is exactly why T-1.5 made provisioning
        // an endpoint -- and why a test bootstraps it in SQL rather than pretending otherwise.
        AuthzFixtures.bootstrapAdmin(jdbc, tenant, admin);
        UUID chief = chief(tenant);
        String token = token(admin, tenant);

        UUID group = id(post(token, "/api/v1/groups", "{\"name\":\"Engineering\"}"));
        UUID child = id(post(token, "/api/v1/groups",
            "{\"name\":\"Platform\",\"parentId\":\"" + group + "\"}"));
        expect(200, as(token, "POST", "/api/v1/groups/" + child + "/members/" + chief, null));

        UUID role = id(post(token, "/api/v1/roles",
            "{\"name\":\"Reader\",\"description\":\"Reads things\"}"));
        expect(200, as(token, "PUT", "/api/v1/roles/" + role + "/permissions",
            "{\"permissions\":[\"" + Permission.GROUP_READ.code() + "\"]}"));

        UUID assignment = id(post(token, "/api/v1/assignments", "{\"roleId\":\"" + role
            + "\",\"userId\":\"" + chief + "\",\"scopeType\":\"TENANT\"}"));

        return new Company(tenant, token, group, child, role, chief, assignment,
            "chief@" + tenant + ".test");
    }

    private UUID id(String body) throws Exception {
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private String post(String token, String path, String body) throws Exception {
        return expect(200, as(token, "POST", path, body)).body();
    }

    private HttpResponse<String> expect(int status, HttpResponse<String> response) {
        assertThat(response.statusCode())
            .as("fixture request failed: %s", response.body())
            .isEqualTo(status);
        return response;
    }

    /** The same person, filed the same way, in each company. */
    private UUID chief(String tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_user (id, tenant_id, email, display_name, status, idp_sub, created_at, updated_at)
            VALUES (?, ?, ?, 'Dana Chief', 'ACTIVE', ?, now(), now())
            """, id, tenant, "chief@" + tenant + ".test", "sub-" + tenant + "-chief");
        return id;
    }

    private static String token(String username, String tenant) {
        return username + "~" + tenant + "~TENANT";
    }

    private void removeEverything() {
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM app_role");
        jdbc.update("DELETE FROM group_membership");
        jdbc.update("DELETE FROM user_group");
        jdbc.update("DELETE FROM app_user");
    }
}
