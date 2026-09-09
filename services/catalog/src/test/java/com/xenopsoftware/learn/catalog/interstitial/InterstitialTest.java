package com.xenopsoftware.learn.catalog.interstitial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.catalog.PostgresTestHarness;
import com.xenopsoftware.learn.common.messaging.OutboxMessage;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Where a learner is held, and what moves them (T-5.4).
 *
 * <p>Every test here is about one integer — {@link InterstitialService#frontierOf}, the furthest
 * second this learner may be credited for. That integer is the whole of what crosses the boundary
 * to streaming, which enforces it in the coverage accounting (T-3.7): the questions, the blocking
 * flags and the answers all collapse into it here so that nothing over there has to learn what an
 * interstitial is.
 *
 * <p>Answers arrive as events, because that is the only way they can arrive. Assessment observes
 * the attempt and owns the record of it (ADR-0109), and there is deliberately no endpoint a learner
 * could post one to — so these tests deliver the event, which is exactly what assessment will do.
 */
@SpringBootTest
class InterstitialTest extends PostgresTestHarness {

    private static final String TENANT = "acme";
    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID SOMEBODY_ELSE = UUID.randomUUID();

    @Autowired
    private InterstitialService interstitials;
    @Autowired
    private InterstitialAnsweredHandler answers;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID node;

    @BeforeEach
    void aCourseWithOneVideoInIt() {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        UUID item = UUID.randomUUID();
        UUID course = UUID.randomUUID();
        UUID module = UUID.randomUUID();
        node = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO content_item (id, tenant_id, type, title, state, created_at, updated_at)
            VALUES (?, ?, 'video', 'Fire safety', 'PUBLISHED', now(), now())
            """, item, TENANT);
        jdbc.update("""
            INSERT INTO course (id, tenant_id, title, created_at, updated_at)
            VALUES (?, ?, 'Onboarding', now(), now())
            """, course, TENANT);
        jdbc.update("""
            INSERT INTO course_module (id, tenant_id, course_id, title, ordinal, created_at,
                    updated_at)
            VALUES (?, ?, ?, 'Module 1', 1, now(), now())
            """, module, TENANT, course);
        jdbc.update("""
            INSERT INTO course_node (id, tenant_id, module_id, content_item_id, ordinal, required,
                    created_at, updated_at)
            VALUES (?, ?, ?, ?, 1, true, now(), now())
            """, node, TENANT, module, item);
    }

    @AfterEach
    void tidy() {
        emptyEveryTable(dataSource);
    }

    // ---------------------------------------------------------------- the frontier

    @Test
    void aNodeWithNoInterstitialsHoldsNobody() {
        assertThat(frontier()).isNull();
    }

    @Test
    void theEarliestUnansweredBlockingOneIsTheFrontier() {
        add(300, true);
        add(120, true);
        add(500, true);

        assertThat(frontier())
            .as("the first one in the way, not the last one added or the last one authored")
            .isEqualTo(120);
    }

    /**
     * A non-blocking marker is shown, may be answered, is reported — and holds nobody.
     *
     * <p>That is what "blocking and non-blocking modes" has to mean for it to be a mode rather than
     * a label: the difference has to be visible in the number the accounting enforces.
     */
    @Test
    void aNonBlockingOneMovesNoFrontier() {
        add(120, false);
        add(300, true);

        assertThat(frontier()).isEqualTo(300);
    }

    @Test
    void answeringTheFirstOneMovesTheFrontierToTheNext() {
        UUID first = add(120, true).getId();
        add(300, true);

        answers.handle(answered(first, LEARNER, null));

        assertThat(frontier()).isEqualTo(300);
    }

    @Test
    void answeringAllOfThemLetsTheLearnerThrough() {
        UUID first = add(120, true).getId();
        UUID second = add(300, true).getId();

        answers.handle(answered(first, LEARNER, null));
        answers.handle(answered(second, LEARNER, null));

        assertThat(frontier()).isNull();
    }

    @Test
    void oneLearnersAnswerDoesNotReleaseAnother() {
        UUID only = add(120, true).getId();

        answers.handle(answered(only, SOMEBODY_ELSE, null));

        assertThat(frontier())
            .as("a frontier is about a person; a shared one would be a course-wide skip button")
            .isEqualTo(120);
    }

    // ---------------------------------------------------------------- repeat viewings

    /**
     * T-5.4's fourth criterion, the backward half, and its fifth in the same assertion.
     *
     * <p><b>The behaviour, decided:</b> an answered interstitial is answered. Seeking backwards
     * over it does not re-ask it, and neither does coming back tomorrow — because the alternative
     * punishes exactly the behaviour the product wants, which is a learner re-watching a section
     * they did not follow. Re-asking is available and it is the author's to ask for.
     */
    @Test
    void anAnsweredOneIsNotAskedAgainByRewatchingOrReturning() {
        UUID once = add(120, true).getId();
        answers.handle(answered(once, LEARNER, "viewing-1"));

        assertThat(frontier("viewing-1"))
            .as("still in the same viewing, having seeked back over it")
            .isNull();
        assertThat(frontier("viewing-2"))
            .as("and a week later, in a new one")
            .isNull();
    }

    @Test
    void oneTheAuthorMarkedAskAgainComesBackInTheNextViewing() {
        UUID everyTime = add(120, true, true).getId();
        answers.handle(answered(everyTime, LEARNER, "viewing-1"));

        assertThat(frontier("viewing-1"))
            .as("answered in this viewing, so not asked twice within it")
            .isNull();
        assertThat(frontier("viewing-2"))
            .as("a new viewing asks again, which is what the author asked for")
            .isEqualTo(120);
    }

    // ---------------------------------------------------------------- authoring

    @Test
    void twoAtTheSameSecondAreRefusedRatherThanOrderedByChance() {
        add(300, true);

        assertThatThrownBy(() -> add(300, true))
            .as("two questions at one instant have no order a player could show them in")
            .hasMessageContaining("already has an interstitial at 300s");
    }

    /**
     * Nudging a marker is not asking a new question.
     *
     * <p>An author moving one from 300s to 305s has changed where it is shown, not what it asks, so
     * a learner who answered it must not meet it again. Asking afresh is remove-and-add, which is
     * two deliberate requests rather than a surprise inside one.
     */
    @Test
    void movingOneKeepsTheAnswersAlreadyGivenForIt() {
        UUID marker = add(300, true).getId();
        answers.handle(answered(marker, LEARNER, null));

        TenantContext.callWithUnchecked(TENANT, () -> interstitials.move(marker, 305));

        assertThat(frontier()).isNull();
        assertThat(TenantContext.callWithUnchecked(TENANT, () -> interstitials.on(node)))
            .singleElement()
            .satisfies(moved -> assertThat(moved.getPositionSeconds()).isEqualTo(305));
    }

    @Test
    void removingOneTakesItsAnswersWithIt() {
        UUID marker = add(300, true).getId();
        answers.handle(answered(marker, LEARNER, null));

        TenantContext.callWithUnchecked(TENANT, () -> {
            interstitials.remove(marker);
            return null;
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM interstitial_response", Long.class))
            .as("an answer to a question nobody asks any more is not a record of anything")
            .isZero();
    }

    // ---------------------------------------------------------------- the bus

    @Test
    void theSameAnswerTwiceIsOneRow() {
        UUID marker = add(120, true).getId();
        OutboxMessage message = answered(marker, LEARNER, null);

        answers.handle(message);
        answers.handle(message);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM interstitial_response", Long.class))
            .as("the bus re-sends by design, and the unique key is what survives it")
            .isEqualTo(1);
    }

    @Test
    void anAnswerToAnInterstitialThisCompanyDoesNotHaveIsDroppedRatherThanRetriedForever() {
        answers.handle(answered(UUID.randomUUID(), LEARNER, null));

        // Removed between the learner answering it and this arriving, which is ordinary. A failing
        // insert would put a poison message at the head of the queue and hold up every other
        // learner's answer behind it.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interstitial_response", Long.class))
            .isZero();
    }

    @Test
    void theAnswerIsRecordedWhenItWasGivenAndNotWhenItArrived() {
        UUID marker = add(120, true).getId();
        Instant answeredAt = Instant.parse("2026-09-05T10:00:00Z");

        answers.handle(answered(marker, LEARNER, null, answeredAt));

        assertThat(jdbc.queryForObject("SELECT answered_at FROM interstitial_response",
            Instant.class))
            .as("an answer that sat in a backlog for an hour was not given an hour late")
            .isEqualTo(answeredAt);
    }

    /**
     * The attempt is nullable, and that is T-6.6's half of this task showing through.
     *
     * <p>Attempts do not exist yet (#65), so nothing can carry one here. The frontier does not need
     * it — being answered is what moves a learner on. What needs it is the last criterion, that an
     * interstitial's result reaches reporting as an ordinary attempt, and this column is where that
     * will land without a migration.
     */
    @Test
    void anAnswerCarriesItsAttemptWhenThereIsOneToCarry() {
        UUID marker = add(120, true).getId();
        UUID attempt = UUID.randomUUID();

        answers.handle(answered(marker, LEARNER, null, Instant.now(), attempt));

        assertThat(jdbc.queryForObject("SELECT attempt_id FROM interstitial_response", UUID.class))
            .isEqualTo(attempt);
        assertThat(frontier()).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private Interstitial add(int second, boolean blocking) {
        return add(second, blocking, false);
    }

    private Interstitial add(int second, boolean blocking, boolean askAgain) {
        return TenantContext.callWithUnchecked(TENANT, () ->
            interstitials.add(node, second, UUID.randomUUID(), blocking, askAgain));
    }

    private Integer frontier() {
        return frontier(null);
    }

    private Integer frontier(String viewing) {
        return TenantContext.callWithUnchecked(TENANT, () ->
            interstitials.frontierOf(node, LEARNER, viewing));
    }

    private OutboxMessage answered(UUID interstitialId, UUID learnerId, String viewing) {
        return answered(interstitialId, learnerId, viewing, Instant.now());
    }

    private OutboxMessage answered(UUID interstitialId, UUID learnerId, String viewing,
            Instant answeredAt) {
        return answered(interstitialId, learnerId, viewing, answeredAt, null);
    }

    private OutboxMessage answered(UUID interstitialId, UUID learnerId, String viewing,
            Instant answeredAt, UUID attemptId) {
        String payload = """
            {"tenantId":"%s","interstitialId":"%s","learnerId":"%s","attemptId":%s,
             "viewing":%s,"answeredAt":"%s"}
            """.formatted(TENANT, interstitialId, learnerId, quoted(attemptId), quoted(viewing),
            answeredAt);
        return new OutboxMessage(UUID.randomUUID(), TENANT, "assessment.interstitial.answered",
            "InterstitialAnswered", payload, null, Instant.now());
    }

    private static String quoted(Object value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
