package com.xenopsoftware.learn.assessment.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.assessment.PostgresTestHarness;
import com.xenopsoftware.learn.assessment.bank.BankService;
import com.xenopsoftware.learn.assessment.exam.SectionService;
import com.xenopsoftware.learn.assessment.exam.TestService;
import com.xenopsoftware.learn.assessment.question.Question;
import com.xenopsoftware.learn.assessment.question.QuestionService;
import com.xenopsoftware.learn.assessment.question.QuestionVersion;
import com.xenopsoftware.learn.assessment.question.type.QuestionTypes;
import com.xenopsoftware.learn.assessment.scoring.QuestionMark;
import com.xenopsoftware.learn.assessment.scoring.Responses;
import com.xenopsoftware.learn.assessment.scoring.Scores;
import com.xenopsoftware.learn.assessment.scoring.SectionMark;
import com.xenopsoftware.learn.assessment.scoring.TestScore;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The record of what one learner was asked (T-6.5).
 *
 * <p><b>Random assembly without a recorded form is unreportable and undefendable.</b> These tests
 * are the four things the form has to make true: what was served is recoverable, two learners
 * genuinely differ, each is graded against their own, and nobody can reassemble one afterwards.
 */
@SpringBootTest
class FormAssemblyTest extends PostgresTestHarness {

    private static final String TENANT = "acme";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private BankService banks;
    @Autowired
    private QuestionService questions;
    @Autowired
    private TestService tests;
    @Autowired
    private SectionService sections;
    @Autowired
    private FormAssembler assembler;
    @Autowired
    private QuestionTypes types;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UUID bank;
    private UUID test;

    @BeforeEach
    void aTestAndABank() {
        jdbc = new JdbcTemplate(dataSource);
        emptyEveryTable(dataSource);
        inTenant(() -> {
            bank = banks.create("Fire safety", null).getId();
            test = tests.create("Annual refresher", null, 80).getId();
            return null;
        });
    }

    // ---------------------------------------------------------------- the record

    @Test
    void aFormRecordsTheVersionsServedInTheOrderTheyWereServed() {
        List<UUID> asked = inTenant(() -> {
            UUID section = sections.addFixed(test, "Everything").getId();
            List<UUID> ids = List.of(choice("one", "a").getId(), choice("two", "b").getId(),
                choice("three", "c").getId());
            sections.questionsAre(section, ids);
            return ids;
        });
        UUID attempt = UUID.randomUUID();

        Form form = inTenant(() -> assembler.assemble(test, attempt));

        assertThat(form.items()).extracting(FormItem::position).containsExactly(0, 1, 2);
        assertThat(form.items()).extracting(FormItem::questionVersionId)
            .as("the VERSION, never the question: three months later \"what exactly did this "
                + "person see\" has to be answerable from this row (ADR-0106)")
            .containsExactlyElementsOf(inTenant(() -> versionsOf(asked)));

        // And it reads back the same, from the database rather than from the object just built.
        assertThat(inTenant(() -> assembler.of(attempt)).items())
            .extracting(FormItem::questionVersionId)
            .containsExactlyElementsOf(form.items().stream().map(FormItem::questionVersionId)
                .toList());
    }

    @Test
    void servingAVersionIsWhatFreezesIt() {
        UUID question = inTenant(() -> {
            UUID section = sections.addFixed(test, "Everything").getId();
            Question one = choice("one", "a");
            sections.questionsAre(section, List.of(one.getId()));
            return one.getId();
        });
        assertThat(inTenant(() -> questions.currentVersionOf(questions.get(question))
            .getFirstServedAt()))
            .as("a draft until somebody is shown it")
            .isNull();

        inTenant(() -> assembler.assemble(test, UUID.randomUUID()));

        assertThat(inTenant(() -> questions.currentVersionOf(questions.get(question))
            .getFirstServedAt()))
            .as("and history afterwards, which is what makes an edit create a new version "
                + "(ADR-0106) instead of rewriting what this learner was asked")
            .isNotNull();
    }

    /**
     * The option order is stored, not re-derived from the seed.
     *
     * <p>Which looks redundant until you ask what re-deriving would couple: a review screen (T-6.9)
     * has to render exactly what was seen, and replaying a seed makes that depend on this shuffle
     * algorithm never changing, in any language, forever.
     */
    @Test
    void theOptionOrderIsRecordedAsServed() {
        inTenant(() -> {
            UUID section = sections.addFixed(test, "Everything").getId();
            sections.questionsAre(section, List.of(choice("one", "a").getId()));
            sections.shuffles(section, false, true);
            return null;
        });
        UUID attempt = UUID.randomUUID();

        inTenant(() -> assembler.assemble(test, attempt, 42L));
        Map<String, List<String>> order = inTenant(() -> assembler.of(attempt)).items()
            .getFirst().optionOrder();

        assertThat(order).containsOnlyKeys("choices");
        assertThat(order.get("choices"))
            .as("the same ids, in some order -- a shuffle that lost or invented one would be a "
                + "question the learner could not answer")
            .containsExactlyInAnyOrder("a", "b", "c", "d");
    }

    /**
     * Which lists may be reordered is the type's answer, not the assembler's.
     *
     * <p>T-6.3's one dispatch point, applied to one more question. A fill-in's blanks are positions
     * in the stem and reordering them detaches the answer from the sentence, so the type says no
     * and this class never learns why.
     */
    @Test
    void aTypeThatMustNotBeReorderedIsNotReordered() {
        inTenant(() -> {
            UUID section = sections.addFixed(test, "Everything").getId();
            sections.questionsAre(section, List.of(fillIn("capital").getId()));
            sections.shuffles(section, false, true);
            return null;
        });
        UUID attempt = UUID.randomUUID();

        inTenant(() -> assembler.assemble(test, attempt, 42L));

        assertThat(inTenant(() -> assembler.of(attempt)).items().getFirst().optionOrder())
            .isEmpty();
    }

    // ---------------------------------------------------------------- two learners

    /**
     * Two learners provably get different forms, and each is graded against their own.
     *
     * <p>Deterministic rather than probable: the shuffle takes the seed, so these two orders are
     * fixed and a change to the assembler that stopped shuffling would fail this test every time
     * rather than one run in a hundred.
     *
     * <p>The second half is the one that matters. Answers are given <b>by position</b> — which is
     * what a client sends — so replaying one learner's answers against the other's form marks the
     * wrong questions. If grading read the test rather than the form, both would score the same and
     * this assertion would pass for the wrong reason.
     */
    @Test
    void twoLearnersGetDifferentFormsAndAreEachGradedAgainstTheirOwn() {
        inTenant(() -> {
            UUID section = sections.addFixed(test, "Everything").getId();
            sections.questionsAre(section, List.of(choice("one", "a").getId(),
                choice("two", "b").getId(), choice("three", "c").getId(),
                choice("four", "d").getId()));
            sections.shuffles(section, true, false);
            return null;
        });

        Form first = inTenant(() -> assembler.assemble(test, UUID.randomUUID(), 1L));
        Form second = inTenant(() -> assembler.assemble(test, UUID.randomUUID(), 7L));

        assertThat(order(first)).isNotEqualTo(order(second));
        assertThat(new HashSet<>(order(first)))
            .as("the same four questions, in a different order -- a shuffle, not a different draw")
            .isEqualTo(new HashSet<>(order(second)));

        // Each answers their own form correctly and scores full marks.
        assertThat(scoreOf(first, keysOf(first)).percent()).isEqualTo(100);
        assertThat(scoreOf(second, keysOf(second)).percent()).isEqualTo(100);

        // And the first learner's answers, replayed by position against the second's form, do not.
        assertThat(scoreOf(second, keysOf(first)).percent())
            .as("if grading read the test rather than the form, this would be 100 and the test "
                + "would be passing for the wrong reason")
            .isLessThan(100);
    }

    /**
     * A pool draws differently for different learners.
     *
     * <p>Ten questions, five drawn, ten attempts. Two draws coinciding is one chance in 252; all
     * ten coinciding is one in 252<sup>9</sup>, which is about 10<sup>-22</sup>. This is not a
     * flaky test — it is a test that fails when the draw has stopped being a draw.
     */
    @Test
    void aPoolDrawsADifferentSetForDifferentLearners() {
        inTenant(() -> {
            UUID section = sections.addPool(test, "Five of ten", 5).getId();
            for (int index = 0; index < 10; index++) {
                choice("question " + index, "a");
            }
            sections.drawsFrom(section, bank, null, null, 5, List.of());
            return null;
        });

        Set<List<UUID>> drawn = new HashSet<>();
        for (int attempt = 0; attempt < 10; attempt++) {
            drawn.add(order(inTenant(() -> assembler.assemble(test, UUID.randomUUID()))));
        }

        assertThat(drawn).hasSizeGreaterThan(1);
        assertThat(drawn).allSatisfy(form -> assertThat(form).hasSize(5));
    }

    // ---------------------------------------------------------------- the refusals

    @Test
    void aPoolThatCannotBeFilledIsRefusedAtAssemblyAndNotServedShort() {
        inTenant(() -> {
            UUID id = sections.addPool(test, "Five of five", 5).getId();
            for (int index = 0; index < 5; index++) {
                choice("question " + index, "a");
            }
            sections.drawsFrom(id, bank, null, null, 5, List.of());
            return id;
        });


        // The bank shrinks after the section was saved, which authoring-time validation cannot see.
        inTenant(() -> {
            List<UUID> all = jdbc.queryForList(
                "SELECT id FROM question WHERE tenant_id = ?", UUID.class, TENANT);
            questions.delete(all.getFirst());
            questions.delete(all.get(1));
            return null;
        });

        assertThatThrownBy(() -> inTenant(() -> assembler.assemble(test, UUID.randomUUID())))
            .as("this is why there are two checks: a pool large enough in March is not large "
                + "enough in June, and nothing about editing the section notices")
            .hasMessageContaining("asks for 5 questions and can supply 3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_form", Long.class))
            .as("and nothing was written -- a refused assembly leaves no half-built form")
            .isZero();
    }

    @Test
    void aTestWithNoSectionsHasNothingToSit() {
        assertThatThrownBy(() -> inTenant(() -> assembler.assemble(test, UUID.randomUUID())))
            .hasMessageContaining("no sections");
    }

    // ---------------------------------------------------------------- reassembly

    @Test
    void oneAttemptGetsOneFormAndAsecondAssemblyIsRefused() {
        inTenant(() -> {
            UUID section = sections.addFixed(test, "Everything").getId();
            sections.questionsAre(section, List.of(choice("one", "a").getId()));
            return null;
        });
        UUID attempt = UUID.randomUUID();
        inTenant(() -> assembler.assemble(test, attempt));

        assertThatThrownBy(() -> inTenant(() -> assembler.assemble(test, attempt)))
            .as("the unique key rather than a check-then-insert, which has a window where two "
                + "starts both find nothing and both write")
            .hasMessageContaining("already has a form");
    }

    /**
     * The form cannot be edited or deleted, and the database is what says so.
     *
     * <p>Enforced there rather than in a service for the reason V2 gives about a served version: a
     * support fix applied in SQL, a migration, or a future service in another language all reach
     * this table. What a mutable form would mean, plainly: a learner sits a test, the form is
     * edited, and the report now describes an exam that was never taken.
     */
    @Test
    void aFormIsTheRecordAndTheDatabaseRefusesToChangeIt() {
        inTenant(() -> {
            UUID section = sections.addFixed(test, "Everything").getId();
            sections.questionsAre(section, List.of(choice("one", "a").getId()));
            return null;
        });
        UUID attempt = UUID.randomUUID();
        Form form = inTenant(() -> assembler.assemble(test, attempt));

        assertThatThrownBy(() -> jdbc.update("UPDATE test_form SET seed = 1 WHERE id = ?",
            form.id()))
            .hasMessageContaining("cannot be edited");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM test_form_item WHERE form_id = ?",
            form.id()))
            .hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE test_form_item SET position = 9 WHERE form_id = ?", form.id()))
            .hasMessageContaining("cannot be edited");
    }

    @Test
    void aSectionSomebodyHasSatCannotBeRemoved() {
        UUID section = inTenant(() -> {
            UUID id = sections.addFixed(test, "Everything").getId();
            sections.questionsAre(id, List.of(choice("one", "a").getId()));
            return id;
        });
        inTenant(() -> assembler.assemble(test, UUID.randomUUID()));

        assertThatThrownBy(() -> inTenant(() -> {
            sections.remove(section);
            return null;
        }))
            .as("deleting a section must not delete the record of what somebody was asked")
            .hasMessageContaining("already sat");
    }

    // ---------------------------------------------------------------- helpers

    /** The question versions of a form, in served order. */
    private static List<UUID> order(Form form) {
        return form.items().stream().map(FormItem::questionVersionId).toList();
    }

    /** The correct choice for each item of this form, by position. */
    private List<String> keysOf(Form form) {
        return inTenant(() -> form.items().stream()
            .map(item -> bodyOf(item.questionVersionId()).get("answerKey").get("correct").get(0)
                .asString())
            .toList());
    }

    /**
     * Grades a form against answers given <b>by position</b>, which is what a client sends.
     *
     * <p>Uses T-6.3 to grade and T-6.4 to score, so this asserts the whole chain rather than a
     * re-implementation of it.
     */
    private TestScore scoreOf(Form form, List<String> answersByPosition) {
        return inTenant(() -> {
            List<QuestionMark> marks = new ArrayList<>();
            for (FormItem item : form.items()) {
                JsonNode body = bodyOf(item.questionVersionId());
                JsonNode response = JSON.readTree(
                    "{\"chosen\":[\"" + answersByPosition.get(item.position()) + "\"]}");
                marks.add(Scores.award(types.grade(body, response),
                    Responses.wasAnswered(response), item.scoring(), false, false));
            }
            return Scores.compose(List.of(SectionMark.of("Everything", 1, marks)), 80);
        });
    }

    private JsonNode bodyOf(UUID versionId) {
        return JSON.readTree(jdbc.queryForObject(
            "SELECT body::text FROM question_version WHERE id = ?", String.class, versionId));
    }

    private List<UUID> versionsOf(List<UUID> questionIds) {
        return questionIds.stream()
            .map(id -> questions.currentVersionOf(questions.get(id)))
            .map(QuestionVersion::getId)
            .toList();
    }

    /** A four-choice question whose correct answer is the id given. */
    private Question choice(String name, String correct) {
        return questions.create(bank, name, JSON.readTree("""
            {"type":"single-choice","stem":"%s?",
             "options":{"choices":[{"id":"a","text":"A"},{"id":"b","text":"B"},
                                   {"id":"c","text":"C"},{"id":"d","text":"D"}]},
             "answerKey":{"correct":["%s"]}}""".formatted(name, correct)));
    }

    private Question fillIn(String name) {
        return questions.create(bank, name, JSON.readTree("""
            {"type":"fill-in","stem":"The capital of Turkey is ___.",
             "options":{"blanks":[{"id":"b1","text":"capital"}]},
             "answerKey":{"accepted":{"b1":["Ankara"]}}}"""));
    }

    private <T> T inTenant(Supplier<T> body) {
        return TenantContext.callWithUnchecked(TENANT, body);
    }

}
