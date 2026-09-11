package com.xenopsoftware.learn.packaging.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.common.tenancy.TenantContext;
import com.xenopsoftware.learn.packaging.PostgresTestHarness;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * A conformant package commits constantly, and the database has to survive it (T-4.4).
 *
 * <h2>What is actually being asserted</h2>
 *
 * <p>Not a throughput number — this runs against a container on somebody's laptop and a number
 * measured there would mean nothing in a cluster. What is asserted is the SHAPE of the write load,
 * which is the part that decides whether it can starve anything:
 *
 * <ul>
 *   <li>five hundred commits from one learner leave <b>one row</b>, not five hundred. An append
 *       log here would be a table growing by a row every three seconds per learner in the product;
 *   <li>they leave <b>one completion event</b>, not one per commit after the first — otherwise the
 *       bus, and catalog's idempotency check behind it, carry the whole storm too;
 *   <li>the last commit's data model is the one that survives, so the coalescing and the budget
 *       that flatten the rate cost nothing.
 * </ul>
 *
 * <p>The last is what makes the other defences safe. Every commit carries the whole data model, so
 * a save that is folded, refused or dropped loses nothing the next one will not carry again — and
 * {@code Terminate} forces a commit, which makes the last one the one that counts.
 */
/*
 * THE BUDGET IS LIFTED HERE, and that is not the same as ignoring it.
 *
 * What this class measures is the SHAPE of a storm that reaches the database -- one row, one event,
 * the last commit winning. The budget is the mechanism that stops most of a storm arriving, and
 * CommitBudgetTest is where its own behaviour is pinned. Leaving it at its default here would make
 * this test's result depend on whether a developer happens to have the local Valkey running: with
 * it up, the twenty-first save is refused and the assertions are about a limit rather than a shape.
 */
@SpringBootTest(properties = "packaging.runtime.saves-per-window=1000000")
class CommitStormTest extends PostgresTestHarness {

    private static final String TENANT = "acme";

    /**
     * Enough to be a storm and not so many that this test is the slow one in the module.
     *
     * <p>Five hundred is roughly twenty-five minutes of a package committing every three seconds,
     * which is one ordinary learner in one ordinary course.
     */
    private static final int COMMITS = 500;

    @Autowired
    private RuntimeService runtimes;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID packageId;
    private final UUID learner = UUID.randomUUID();
    private final UUID node = UUID.randomUUID();

    @BeforeEach
    void aReadyPackage() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM package_runtime");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM content_package");
        packageId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO content_package (id, tenant_id, kind, state, declared_bytes, profile,
                    entry_path, created_at, updated_at)
            VALUES (?, ?, 'scorm', 'READY', 1024, 'scorm-1.2', 'index.html', now(), now())
            """, packageId, TENANT);
    }

    @Test
    @DisplayName("five hundred commits from one learner are one row and one event")
    void aStormIsOneRow() {
        TenantContext.callWithUnchecked(TENANT, () -> {
            PackageRuntime opened = runtimes.open(packageId, node, learner);
            UUID session = opened.getActiveSession();

            for (int commit = 1; commit <= COMMITS; commit++) {
                Map<String, String> data = new HashMap<>();
                // What a slide-based package looks like halfway through: a position, a growing
                // suspend blob, and a status that turns over near the end.
                data.put("cmi.core.lesson_location", String.valueOf(commit));
                data.put("cmi.suspend_data", "s".repeat(commit));
                data.put("cmi.core.session_time", "0000:00:" + String.format("%02d", commit % 60) + ".00");
                data.put("cmi.core.lesson_status",
                    commit > COMMITS / 2 ? "completed" : "incomplete");
                runtimes.save(packageId, node, learner, session, data, 3);
            }
            return null;
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_runtime", Integer.class))
            .as("one registration is one row, however often it is written")
            .isEqualTo(1);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE topic = ?", Integer.class,
            RuntimeService.COMPLETED_SUBJECT))
            .as("the completion is announced once, not on every commit that repeats it")
            .isEqualTo(1);

        assertThat(jdbc.queryForObject(
            "SELECT data ->> 'cmi.core.lesson_location' FROM package_runtime", String.class))
            .as("the last commit is the one that survives, which is what makes folding safe")
            .isEqualTo(String.valueOf(COMMITS));
    }

    @Test
    @DisplayName("a save from a superseded launch is refused rather than merged")
    void theOlderTabIsRefused() {
        TenantContext.callWithUnchecked(TENANT, () -> {
            UUID firstTab = runtimes.open(packageId, node, learner).getActiveSession();
            runtimes.save(packageId, node, learner, firstTab,
                Map.of("cmi.core.lesson_location", "20"), 60);

            // The learner presses reload, or opens the course in a second tab. Same person, same
            // registration, and a data model that starts empty.
            UUID secondTab = runtimes.open(packageId, node, learner).getActiveSession();
            assertThat(secondTab).isNotEqualTo(firstTab);

            /*
             * THE WRITE THAT USED TO WIN. The first tab is still open and still committing, and
             * without the rule its stale map -- twenty slides behind, or empty -- would replace
             * what the second tab has done, with nothing anywhere recording that it happened.
             */
            assertThatThrownBy(() -> runtimes.save(packageId, node, learner, firstTab,
                Map.of("cmi.core.lesson_location", "1"), 60))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(refusal -> ((ResponseStatusException) refusal).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

            runtimes.save(packageId, node, learner, secondTab,
                Map.of("cmi.core.lesson_location", "21"), 60);
            return null;
        });

        assertThat(jdbc.queryForObject(
            "SELECT data ->> 'cmi.core.lesson_location' FROM package_runtime", String.class))
            .isEqualTo("21");
    }

    @Test
    @DisplayName("an over-long element is refused whole, and the last good state is still there")
    void anOversizeSuspendBlobIsRefusedNotTruncated() {
        TenantContext.callWithUnchecked(TENANT, () -> {
            UUID session = runtimes.open(packageId, node, learner).getActiveSession();
            runtimes.save(packageId, node, learner, session,
                Map.of("cmi.suspend_data", "good", "cmi.core.lesson_location", "3"), 0);

            assertThatThrownBy(() -> runtimes.save(packageId, node, learner, session,
                Map.of("cmi.suspend_data", "x".repeat(RuntimeService.MAX_VALUE_LENGTH + 1)), 0))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(refusal -> ((ResponseStatusException) refusal).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            return null;
        });

        // NOT half a blob. A package handed back a truncated suspend blob does not resume badly --
        // it fails to start, on the next launch, with nothing naming the cause.
        assertThat(jdbc.queryForObject(
            "SELECT data ->> 'cmi.suspend_data' FROM package_runtime", String.class))
            .isEqualTo("good");
    }

    @Test
    @DisplayName("the total time a package can read back is the one it reported")
    void totalTimeComesBackInThePackagesOwnVocabulary() {
        Map<String, String> seeded = TenantContext.callWithUnchecked(TENANT, () -> {
            UUID session = runtimes.open(packageId, node, learner).getActiveSession();
            runtimes.save(packageId, node, learner, session,
                Map.of("cmi.core.session_time", "0000:25:00.00"), 0);
            PackageRuntime reopened = runtimes.open(packageId, node, learner);
            return runtimes.seeded(reopened, "scorm-1.2");
        });

        // Read-only and LMS-supplied: a package cannot know what happened in the sessions before
        // this one, which is exactly why the standard makes this the LMS's answer.
        //
        // BOTH SPELLINGS, because the declared profile is not a promise about which one the
        // JavaScript inside will ask for -- and because the wrapper reads `scorm-2004` alone when
        // it decides its own defaults, so a cmi5 package sent only the 2004 name would default the
        // other to nought and read a total of zero.
        assertThat(seeded)
            .containsEntry("cmi.core.total_time", "0000:25:00.00")
            .containsEntry("cmi.total_time", "PT0H25M0S");
    }
}
