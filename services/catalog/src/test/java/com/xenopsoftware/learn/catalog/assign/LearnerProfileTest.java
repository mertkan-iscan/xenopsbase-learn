package com.xenopsoftware.learn.catalog.assign;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What catalog knows about a person, and how it learns it (T-5.6, T-9.8).
 *
 * <p>Two projections fed by identity's events, and both of them are load-bearing for deadlines: a
 * timezone decides the moment a due date expires, and the date a group started reaching somebody
 * decides when "within thirty days of joining" starts counting.
 */
@SpringBootTest
class LearnerProfileTest extends PostgresTestHarness {

    private static final String TENANT = "acme";
    private static final UUID LEARNER = UUID.randomUUID();

    @Autowired
    private LearnerProfileHandler profileHandler;
    @Autowired
    private GroupReachHandler reachHandler;
    @Autowired
    private LearnerProfiles profiles;
    @Autowired
    private LearnerGroupReach reach;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void nothingKnownAboutAnybody() {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
    }

    // ---------------------------------------------------------------- the profile

    @Test
    void aProfileArrivesAsAnEventAndIsNeverReadOutOfIdentity() {
        profileHandler.handle(profileEvent("Europe/Istanbul", "kaya@acme.test", "Kaya",
            Instant.now()));

        LearnerProfiles.Profile profile = profiles.of(TENANT, LEARNER).orElseThrow();

        assertThat(profile.timeZone()).isEqualTo("Europe/Istanbul");
        assertThat(profile.email()).isEqualTo("kaya@acme.test");
        assertThat(profile.displayName()).isEqualTo("Kaya");
        assertThat(profile.firstSeenAt())
            .as("when catalog first heard of them, which is the best answer available to \"when "
                + "did they join\" for a company-wide assignment")
            .isNotNull();
    }

    @Test
    void theSameEventTwiceLeavesTheSameRow() {
        OutboxMessage event = profileEvent("Europe/Istanbul", "kaya@acme.test", "Kaya",
            Instant.now());
        profileHandler.handle(event);
        Instant firstSeen = profiles.of(TENANT, LEARNER).orElseThrow().firstSeenAt();
        profileHandler.handle(event);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM learner_profile", Long.class))
            .as("the message carries the whole profile, so applying it twice is applying it once "
                + "-- the property an at-least-once bus needs, from the shape of the message")
            .isEqualTo(1);
        assertThat(profiles.of(TENANT, LEARNER).orElseThrow().firstSeenAt())
            .as("and the day catalog first heard of them does not move because it heard again")
            .isEqualTo(firstSeen);
    }

    @Test
    void anOlderDuplicateDoesNotOvertakeANewerOne() {
        Instant now = Instant.now();
        profileHandler.handle(profileEvent("Europe/Istanbul", "kaya@acme.test", "Kaya", now));
        // The redelivery of a message that was superseded while it sat in a queue.
        profileHandler.handle(profileEvent("UTC", "old@acme.test", "Kaya",
            now.minus(1, ChronoUnit.HOURS)));

        assertThat(profiles.of(TENANT, LEARNER).orElseThrow().timeZone())
            .as("at-least-once delivery says nothing about order, so the row refuses to go "
                + "backwards rather than trusting one")
            .isEqualTo("Europe/Istanbul");
    }

    @Test
    void aPersonWithNoTimezoneIsARealStateAndNotUtc() {
        profileHandler.handle(profileEvent(null, "kaya@acme.test", "Kaya", Instant.now()));

        assertThat(profiles.of(TENANT, LEARNER).orElseThrow().timeZone())
            .as("null keeps the people who have never set one findable; defaulting to UTC would "
                + "make them indistinguishable from everybody who chose it")
            .isNull();
    }

    @Test
    void erasureRemovesTheCopyRatherThanBlankingIt() {
        profileHandler.handle(profileEvent("Europe/Istanbul", "kaya@acme.test", "Kaya",
            Instant.now()));

        profileHandler.handle(new OutboxMessage(UUID.randomUUID(), TENANT,
            "identity.user.profile", LearnerProfileHandler.FORGOTTEN,
            "{\"tenantId\":\"" + TENANT + "\",\"userId\":\"" + LEARNER
                + "\",\"updatedAt\":\"" + Instant.now() + "\"}", null, Instant.now()));

        assertThat(profiles.of(TENANT, LEARNER))
            .as("an address kept after somebody is erased is the copy nobody remembered to delete")
            .isEmpty();
    }

    // ---------------------------------------------------------------- when a group reached them

    @Test
    void joiningASecondGroupDoesNotResetWhenTheFirstOneReachedThem() {
        UUID engineering = UUID.randomUUID();
        UUID fireWardens = UUID.randomUUID();
        reachHandler.handle(reachEvent(engineering));
        jdbc.update("UPDATE learner_group_reach SET reached_at = now() - interval '20 days'");
        Instant original = reach.reachedAtOf(TENANT, LEARNER).get(engineering);

        // Anything at all changing about their memberships republishes the whole set.
        reachHandler.handle(reachEvent(engineering, fireWardens));

        assertThat(reach.reachedAtOf(TENANT, LEARNER).get(engineering))
            .as("otherwise somebody added to an unrelated group in month eleven quietly gets a "
                + "fresh thirty days on training they were already late for")
            .isEqualTo(original);
        assertThat(reach.reachedAtOf(TENANT, LEARNER).get(fireWardens))
            .as("and the group they genuinely just joined starts counting now")
            .isAfter(original);
    }

    @Test
    void leavingAGroupRemovesItsRowAndItsDate() {
        UUID engineering = UUID.randomUUID();
        UUID fireWardens = UUID.randomUUID();
        reachHandler.handle(reachEvent(engineering, fireWardens));

        reachHandler.handle(reachEvent(engineering));

        assertThat(reach.reachedAtOf(TENANT, LEARNER)).containsOnlyKeys(engineering);
        assertThat(reach.of(TENANT, LEARNER))
            .as("a group they have left must not keep assigning them work")
            .contains(engineering)
            .doesNotContain(fireWardens);
    }

    // ---------------------------------------------------------------- plumbing

    private OutboxMessage profileEvent(String zone, String email, String name, Instant updatedAt) {
        String payload = "{\"tenantId\":\"" + TENANT + "\",\"userId\":\"" + LEARNER + "\""
            + ",\"email\":" + quoted(email)
            + ",\"displayName\":" + quoted(name)
            + ",\"timeZone\":" + quoted(zone)
            + ",\"updatedAt\":\"" + updatedAt + "\"}";
        return new OutboxMessage(UUID.randomUUID(), TENANT, "identity.user.profile",
            "user.profile.changed", payload, null, Instant.now());
    }

    private OutboxMessage reachEvent(UUID... groupIds) {
        String groups = List.of(groupIds).stream().map(id -> "\"" + id + "\"")
            .collect(java.util.stream.Collectors.joining(","));
        return new OutboxMessage(UUID.randomUUID(), TENANT, "identity.group.reach", "GroupReach",
            "{\"tenantId\":\"" + TENANT + "\",\"learnerId\":\"" + LEARNER + "\",\"groupIds\":["
                + groups + "]}", null, Instant.now());
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
