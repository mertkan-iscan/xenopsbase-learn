package com.xenopsoftware.learn.assessment.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.assessment.PostgresTestHarness;
import com.xenopsoftware.learn.assessment.bank.BankService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * A served version is refused by the DATABASE, not by the code that remembers to ask (T-6.2).
 *
 * <p>ADR-0106 asks for exactly this and names why: the repository layer is not the only thing that
 * ever writes here. A support fix applied in SQL, a migration, a future service in another
 * language — an invariant that decides whether a disputed certification is defensible has to hold
 * against all three.
 *
 * <p>So every write in this class is raw SQL, deliberately going round the service that would have
 * done the right thing. If these pass with the trigger removed, the product's guarantee is only as
 * good as the next person's memory.
 */
@SpringBootTest
class QuestionVersionIsHistoryTest extends PostgresTestHarness {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private QuestionService questions;

    @Autowired
    private BankService banks;

    @Autowired
    private ServedVersions served;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void emptyTheTables() {
        emptyEveryTable(dataSource);
    }

    @AfterEach
    void leaveNothingForTheNextClass() {
        emptyEveryTable(dataSource);
    }

    @Test
    void aServedVersionCannotBeEditedEvenInSql() {
        UUID version = servedVersion();

        assertThatThrownBy(() -> jdbc().update(
            "UPDATE question_version SET body = '{\"stem\":\"something else\"}'::jsonb WHERE id = ?",
            version))
            .hasMessageContaining("cannot be edited")
            .hasMessageContaining("ADR-0106");
    }

    /**
     * A no-op write is refused too, and that is the intent rather than an accident of the trigger
     * being coarse: a writer that believes it may write to a frozen row is one whose next statement
     * will not be a no-op.
     */
    @Test
    void evenAWriteThatChangesNothingIsRefused() {
        UUID version = servedVersion();

        assertThatThrownBy(() -> jdbc().update(
            "UPDATE question_version SET body = body WHERE id = ?", version))
            .hasMessageContaining("cannot be edited");
    }

    /**
     * The same damage by another verb. Until T-6.6 adds
     * {@code attempt_response.question_version_id ON DELETE RESTRICT}, this trigger is the only
     * thing standing in front of a {@code DELETE FROM question_version}.
     */
    @Test
    void aServedVersionCannotBeDeletedEvenInSql() {
        UUID version = servedVersion();

        assertThatThrownBy(() -> jdbc().update("DELETE FROM question_version WHERE id = ?", version))
            .hasMessageContaining("cannot be deleted")
            .hasMessageContaining("ADR-0106");
    }

    /** A draft is not history, and nothing here pretends otherwise. */
    @Test
    void aDraftVersionIsStillOrdinaryData() {
        UUID version = draftVersion();

        assertThat(jdbc().update(
            "UPDATE question_version SET body = '{\"stem\":\"a draft, corrected\"}'::jsonb WHERE id = ?",
            version)).isEqualTo(1);
    }

    /**
     * Serving is first-wins rather than an error for whoever arrives second.
     *
     * <p>Two learners can be handed the same question in the same millisecond. With a
     * read-then-write the second one's UPDATE meets a row that is now history and is refused by the
     * trigger above — a failed request for a learner who did nothing wrong. The conditional
     * statement makes the loser a no-op instead.
     */
    @Test
    void servingTwiceStampsOneTimestampAndFailsNobody() {
        UUID version = draftVersion();

        assertThat(TenantContext.callWithUnchecked("acme", () -> served.markServed(version))).isTrue();
        assertThat(TenantContext.callWithUnchecked("acme", () -> served.markServed(version)))
            .as("already history; nothing to do, and nothing to refuse")
            .isFalse();
    }

    /** A version belonging to another company is absent, not refused (ADR-0102). */
    @Test
    void anotherCompanysVersionCannotBeStamped() {
        UUID version = draftVersion();

        assertThatThrownBy(() -> TenantContext.callWithUnchecked("globex", () -> served.markServed(version)))
            .isInstanceOf(QuestionNotFound.class);
    }

    private UUID servedVersion() {
        UUID version = draftVersion();
        TenantContext.callWithUnchecked("acme", () -> served.markServed(version));
        return version;
    }

    private UUID draftVersion() {
        return TenantContext.callWithUnchecked("acme", () -> {
            UUID bank = banks.create("Fire safety", null).getId();
            Question question = questions.create(bank, "Electrical fire",
                json.readTree("{\"stem\":\"Which extinguisher?\",\"key\":1}"));
            return question.getCurrentVersionId();
        });
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }
}
