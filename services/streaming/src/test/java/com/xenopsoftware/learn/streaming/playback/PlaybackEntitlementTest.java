package com.xenopsoftware.learn.streaming.playback;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.streaming.PostgresTestHarness;
import com.xenopsoftware.learn.streaming.StubTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T-3.4's central claim, as a test: <b>every one of the checks is load-bearing.</b>
 *
 * <p>The shape of each test is the same — put the caller one step from a token, break exactly
 * one check, and watch the request fail. A chain of checks is easy to write and easy to
 * accidentally short-circuit, and the only durable evidence that the third one still runs is a
 * test that fails when it stops.
 *
 * <p>The second thing asserted throughout is the audit, and it is doing more work than it looks
 * like. Three of these refusals are deliberately indistinguishable to the caller: not-permitted,
 * not-assigned and no-such-node all answer a bare 404 so that nobody can probe the difference.
 * That makes the response body useless as evidence about which check fired, so the audit row is
 * what the test reads — and that is exactly the situation an administrator is in when a customer
 * says "it says I cannot watch this".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({StubTokens.class, PlaybackTestBeans.class})
class PlaybackEntitlementTest extends PostgresTestHarness {

    /** A real Valkey: the rate limiter counts in it and T-1.4's status entry is read from it. */
    private static final GenericContainer<?> VALKEY =
        new GenericContainer<>(DockerImageName.parse("valkey/valkey:8.1-alpine"))
            .withCommand("valkey-server", "--save", "", "--appendonly", "no")
            .withExposedPorts(6379);

    static {
        VALKEY.start();
    }

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    private static final String LEARNER = "acme-learner~acme~TENANT";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);

    @Autowired
    private PlaybackTokenService playbackTokens;

    @Autowired
    private StubEntitlement catalog;

    @Autowired
    private StubViewerPermissions permissions;

    @Autowired
    private StubViewerDirectory directory;

    @Autowired
    private MutableClock clock;

    @Autowired
    private StringRedisTemplate valkey;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    private JdbcTemplate jdbc;
    private UUID node;
    private UUID asset;

    @BeforeEach
    void aLearnerOneStepFromAToken() {
        jdbc = new JdbcTemplate(dataSource);
        // The module's Postgres is shared across test classes, so start from a known table
        // rather than from whatever another class left (the mystery-409 lesson from T-3.2).
        jdbc.update("DELETE FROM playback_refusal");
        jdbc.update("DELETE FROM video_asset");
        valkey.getConnectionFactory().getConnection().serverCommands().flushAll();

        clock.reset();
        permissions.allow(true);
        directory.resolvesTo(StubViewerDirectory.LEARNER_ID);
        catalog.clear();

        node = UUID.randomUUID();
        asset = readyVideo("acme", Duration.ofHours(2));
        catalog.put(new NodeEntitlement(node, asset, true, true, null));
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    void anEntitledLearnerGetsAShortTokenBoundToThemAndTheAsset() throws Exception {
        HttpResponse<String> response = mint(node, LEARNER);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
            .as("the token names the asset it was signed for and the viewer it was decided for")
            .contains(providerRefOf(asset))
            .contains("sub-acme-learner");
        assertThat(expiresAt(response.body()))
            .as("the TTL is the revocation window and it is the configured five minutes")
            .isEqualTo(clock.instant().plus(TOKEN_TTL));
        assertThat(renewAfter(response.body()))
            .as("the player is told to renew before expiry, not at it")
            .isBefore(expiresAt(response.body()));
        assertThat(field(response.body(), "manifestUrl"))
            .as("and somewhere to play it that is not this service (ADR-0101)")
            .doesNotContain("localhost")
            .contains("/manifest/video.m3u8");
        assertThat(refusals()).isEmpty();
    }

    @Test
    void theTokenNeverOutlivesTheCeilingNoMatterWhatIsConfigured() {
        // The ceiling is enforced where it cannot be forgotten -- in the grant's constructor,
        // so no caller anywhere can ask for a long-lived entitlement even by accident.
        assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> new com.xenopsoftware.learn.streaming.media.PlaybackGrant("sub", Duration.ofHours(4))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be revoked");
    }

    // ---------------------------------------------------------------- one broken check each

    @Test
    void aSuspendedCompanyIsRefusedAndTheReasonIsNamed() throws Exception {
        valkey.opsForValue().set("status:tenant:acme", "SUSPENDED");

        HttpResponse<String> response = mint(node, LEARNER);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("ACCOUNT_SUSPENDED");
    }

    @Test
    void aReadOnlyCompanyKeepsItsReadsAndLosesItsNewTokens() throws Exception {
        valkey.opsForValue().set("status:tenant:acme", "READ_ONLY");

        HttpResponse<String> response = mint(node, LEARNER);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("ACCOUNT_READ_ONLY");
    }

    /**
     * The status check that belongs to the DECISION, asked directly.
     *
     * <p>Over HTTP this cannot be seen: {@code StatusGateFilter} refuses a POST from a stopped
     * account at the edge and the decision is never reached, which is the correct layering and
     * is why the two tests above assert only what the caller sees. The check here is not
     * redundant with the filter — it is what keeps the decision complete on its own, and what
     * writes the audit row a filter refusal does not.
     */
    @Test
    void theDecisionRefusesAStoppedAccountItselfNotOnlyAtTheEdge() {
        valkey.opsForValue().set("status:tenant:acme", "SUSPENDED");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> asAcmeLearner(node)))
            .isInstanceOfSatisfying(PlaybackRefusedException.class, refused ->
                assertThat(refused.reason()).isEqualTo(RefusalReason.ACCOUNT_SUSPENDED));
        assertThat(refusals()).containsExactly(RefusalReason.ACCOUNT_SUSPENDED.name());
    }

    @Test
    void theDecisionRefusesAReadOnlyAccountItself() {
        valkey.opsForValue().set("status:tenant:acme", "READ_ONLY");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> asAcmeLearner(node)))
            .isInstanceOfSatisfying(PlaybackRefusedException.class, refused ->
                assertThat(refused.reason()).isEqualTo(RefusalReason.ACCOUNT_READ_ONLY));
        assertThat(refusals()).containsExactly(RefusalReason.ACCOUNT_READ_ONLY.name());
    }

    @Test
    void aCallerWhoHoldsNoContentPermissionIsRefused() throws Exception {
        permissions.allow(false);

        assertThat(mint(node, LEARNER).statusCode()).isEqualTo(404);
        assertThat(refusals()).containsExactly(RefusalReason.NO_PERMISSION.name());
    }

    @Test
    void contentThatWasNeverAssignedIsRefused() throws Exception {
        catalog.put(new NodeEntitlement(node, asset, false, true, null));

        assertThat(mint(node, LEARNER).statusCode()).isEqualTo(404);
        assertThat(refusals()).containsExactly(RefusalReason.NOT_ASSIGNED.name());
    }

    @Test
    void aClosedGateIsRefusedAndTheLearnerIsToldWhy() throws Exception {
        catalog.put(new NodeEntitlement(node, asset, true, false,
            "Finish \"Fire safety, part 1\" first."));

        HttpResponse<String> response = mint(node, LEARNER);

        // 403 and not 404: they are assigned this, so they already know it exists, and T-5.3
        // requires the rule that stops them to be readable by them.
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body())
            .contains("CONTENT_GATED")
            .contains("Fire safety, part 1");
        assertThat(refusals()).containsExactly(RefusalReason.GATED.name());
    }

    /**
     * The audit names the person, as {@code app_user.id} and never as a {@code sub} — the
     * schema test refuses the column outright, and this is the behaviour behind that rule.
     */
    @Test
    void aRefusalNamesTheLearnerByTheirDurableId() throws Exception {
        permissions.allow(false);

        assertThat(mint(node, LEARNER).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject(
            "SELECT actor_user_id FROM playback_refusal", UUID.class))
            .isEqualTo(StubViewerDirectory.LEARNER_ID);
    }

    /**
     * Identity being unreachable must not cost the record. It is the reason the refusal
     * happened in the first place — {@code mayViewContent} fails closed — so losing the row
     * would delete the evidence of an outage at exactly the moment it mattered.
     */
    @Test
    void aRefusalIsStillRecordedWhenIdentityCannotNameTheLearner() throws Exception {
        permissions.allow(false);
        directory.resolvesTo(null);

        assertThat(mint(node, LEARNER).statusCode()).isEqualTo(404);
        assertThat(refusals()).containsExactly(RefusalReason.NO_PERMISSION.name());
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM playback_refusal", UUID.class))
            .isNull();
    }

    @Test
    void aNodeThatCatalogDoesNotKnowIsRefused() throws Exception {
        assertThat(mint(UUID.randomUUID(), LEARNER).statusCode()).isEqualTo(404);
        assertThat(refusals()).containsExactly(RefusalReason.UNKNOWN_NODE.name());
    }

    /**
     * The three refusals a caller must not be able to tell apart, side by side. If any of them
     * ever grows a distinguishing body or status, an id space becomes enumerable by asking.
     */
    @Test
    void theThreeUndisclosedRefusalsAreIdenticalToTheCaller() throws Exception {
        permissions.allow(false);
        HttpResponse<String> noPermission = mint(node, LEARNER);

        permissions.allow(true);
        HttpResponse<String> unknownNode = mint(UUID.randomUUID(), LEARNER);

        catalog.put(new NodeEntitlement(node, asset, false, true, null));
        HttpResponse<String> notAssigned = mint(node, LEARNER);

        assertThat(List.of(noPermission.statusCode(), unknownNode.statusCode(), notAssigned.statusCode()))
            .as("one status between them, so the status says nothing")
            .containsOnly(404);
        assertThat(List.of(noPermission.body(), unknownNode.body(), notAssigned.body()))
            .as("and no body between them, so the body says nothing either")
            .containsOnly("");
        assertThat(refusals())
            .as("and every one of them distinguishable in the audit, which is the point")
            .containsExactlyInAnyOrder(RefusalReason.NO_PERMISSION.name(),
                RefusalReason.UNKNOWN_NODE.name(), RefusalReason.NOT_ASSIGNED.name());
    }

    @Test
    void anEntitledLearnerStillCannotPlayAVideoThatIsNotEncodedYet() throws Exception {
        jdbc.update("UPDATE video_asset SET state = 'PROCESSING' WHERE id = ?", asset);

        // 409 rather than 404: they may see it, it simply is not ready. Hiding it here would
        // make a normal wait indistinguishable from a permission problem.
        assertThat(mint(node, LEARNER).statusCode()).isEqualTo(409);
        assertThat(refusals()).containsExactly(RefusalReason.NOT_PLAYABLE.name());
    }

    @Test
    void aCatalogAnswerNamingAnotherCompanysAssetSignsNothing() throws Exception {
        UUID foreign = readyVideo("globex", Duration.ofHours(1));
        catalog.put(new NodeEntitlement(node, foreign, true, true, null));

        // The persistence discriminator (T-1.1) is the backstop: even a catalog that answered
        // wrongly, or was compromised, cannot get this service to sign for another tenant.
        assertThat(mint(node, LEARNER).statusCode()).isEqualTo(409);
        assertThat(refusals()).containsExactly(RefusalReason.NOT_PLAYABLE.name());
    }

    // ---------------------------------------------------------------- renewal

    /**
     * A two-hour video plays through more than one token (T-3.4's third criterion).
     *
     * <p>The reason this is a requirement rather than an optimisation: the TTL is only a real
     * revocation window if nothing holds a token for longer than it. A player that fetched one
     * token and cached it for the session would make the five minutes decorative, and the
     * suspension bound would silently become "the length of the video".
     */
    @Test
    void aTwoHourVideoPlaysThroughManyTokens() throws Exception {
        Duration video = Duration.ofHours(2);
        assertThat(TOKEN_TTL).as("if one token covered the video there would be nothing to renew")
            .isLessThan(video);

        Instant startedAt = clock.instant();
        List<String> tokens = new ArrayList<>();
        Instant currentExpiry = null;

        while (Duration.between(startedAt, clock.instant()).compareTo(video) < 0) {
            HttpResponse<String> response = mint(node, LEARNER);
            assertThat(response.statusCode())
                .as("playback must not be interrupted at %s into the video",
                    Duration.between(startedAt, clock.instant()))
                .isEqualTo(200);

            if (currentExpiry != null) {
                assertThat(clock.instant())
                    .as("the player renews while the previous token is still valid, so a "
                        + "failed renewal is retried rather than seen")
                    .isBefore(currentExpiry);
            }
            tokens.add(token(response.body()));
            currentExpiry = expiresAt(response.body());

            // The player does what the server told it to, which is the contract under test.
            clock.advance(Duration.between(clock.instant(), renewAfter(response.body())));
        }

        assertThat(new HashSet<>(tokens))
            .as("every renewal is a fresh decision, not the same token handed back")
            .hasSameSizeAs(tokens);
        assertThat(tokens.size())
            .as("two hours at a three-minute renewal cadence")
            .isGreaterThan(1)
            .isGreaterThanOrEqualTo(40);
    }

    @Test
    void revokingEntitlementStopsTheNextTokenAndNothingElse() throws Exception {
        assertThat(mint(node, LEARNER).statusCode()).isEqualTo(200);

        // The entitlement goes away mid-playback. The token already out there keeps working --
        // that is ADR-0101's bargain and it cannot be taken back -- but the renewal fails, so
        // the learner stops within one TTL.
        catalog.put(new NodeEntitlement(node, asset, false, true, null));
        clock.advance(Duration.ofMinutes(3));

        assertThat(mint(node, LEARNER).statusCode()).isEqualTo(404);
        assertThat(refusals()).containsExactly(RefusalReason.NOT_ASSIGNED.name());
    }

    // ---------------------------------------------------------------- rate limit

    @Test
    void oneViewerCannotFarmTokens() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int attempt = 0; attempt < 30; attempt++) {
            statuses.add(mint(node, LEARNER).statusCode());
        }

        assertThat(statuses).as("a loop is stopped, not served").contains(429);
        assertThat(statuses.stream().filter(status -> status == 200).count())
            .as("the configured twenty per window, and no more")
            .isEqualTo(20);
        assertThat(refusals()).contains(RefusalReason.RATE_LIMITED.name());
    }

    @Test
    void theLimitIsPerPersonSoOneLearnerCannotStopAnother() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            mint(node, LEARNER);
        }

        // Same company, same office, same address -- a different person. T-8.7's rule, needed
        // here first: a limit per address throttles a whole customer behind one NAT.
        assertThat(mint(node, "acme-other~acme~TENANT").statusCode()).isEqualTo(200);
    }

    @Test
    void theWindowMovesOnSoALearnerIsNotLockedOutForever() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            mint(node, LEARNER);
        }
        assertThat(mint(node, LEARNER).statusCode()).isEqualTo(429);

        clock.advance(Duration.ofMinutes(5));

        assertThat(mint(node, LEARNER).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The decision without the filter chain in front of it, with the tenant and the caller bound
     * the way {@code TenantFilter} and the security chain would have bound them.
     */
    private PlaybackTokenService.IssuedPlayback asAcmeLearner(UUID nodeId) {
        org.springframework.security.oauth2.jwt.Jwt jwt =
            org.springframework.security.oauth2.jwt.Jwt.withTokenValue(LEARNER)
                .header("alg", "none")
                .subject("sub-acme-learner")
                .claim("tenant_id", "acme")
                .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication
                    .JwtAuthenticationToken(jwt));
        com.xenopsoftware.learn.common.tenancy.TenantContext.set("acme");
        try {
            return playbackTokens.mint(nodeId);
        } finally {
            com.xenopsoftware.learn.common.tenancy.TenantContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private HttpResponse<String> mint(UUID nodeId, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + environment.getProperty("local.server.port")
                    + "/api/v1/me/nodes/" + nodeId + "/playback-token"))
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** A video that finished encoding, written directly: getting there through the upload and
     *  webhook paths is T-3.2's and T-3.3's subject, and is noise here. */
    private UUID readyVideo(String tenant, Duration duration) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO video_asset (id, tenant_id, provider, provider_ref, state,
                                     duration_seconds, size_bytes, max_duration_seconds,
                                     created_at, updated_at)
            VALUES (?, ?, 'fake', ?, 'READY', ?, 1024, 7200, now(), now())
            """, id, tenant, "ref-" + id, (double) duration.toSeconds());
        return id;
    }

    private String providerRefOf(UUID assetId) {
        return jdbc.queryForObject("SELECT provider_ref FROM video_asset WHERE id = ?",
            String.class, assetId);
    }

    private List<String> refusals() {
        return jdbc.queryForList(
            "SELECT reason FROM playback_refusal ORDER BY created_at, reason", String.class);
    }

    private static String token(String body) {
        return field(body, "token");
    }

    private static Instant expiresAt(String body) {
        return Instant.parse(field(body, "expiresAt"));
    }

    private static Instant renewAfter(String body) {
        return Instant.parse(field(body, "renewAfter"));
    }

    private static String field(String body, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\"" + name + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("%s in %s", name, body).isTrue();
        return matcher.group(1);
    }
}
