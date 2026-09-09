package com.xenopsoftware.learn.assessment.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xenopsoftware.learn.assessment.PostgresTestHarness;
import com.xenopsoftware.learn.assessment.bank.BankService;
import com.xenopsoftware.learn.assessment.question.Question;
import com.xenopsoftware.learn.assessment.question.QuestionService;
import com.xenopsoftware.learn.assessment.vocabulary.VocabularyService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a pool section draws from, and the refusal that stops a short exam (T-6.5).
 *
 * <p>The failure this task names as the worst outcome: <b>a pool that matches fewer questions than
 * the section asks for.</b> Serving what there is scores the learner out of a different total from
 * everybody else, and nothing in the result says so. It is refused twice — here at authoring time,
 * and again at assembly ({@code FormAssemblyTest}) — through one predicate, so the number an author
 * was shown and the rows a learner is handed cannot come from two different queries.
 */
@SpringBootTest
class SectionDrawTest extends PostgresTestHarness {

    private static final String TENANT = "acme";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private BankService banks;
    @Autowired
    private QuestionService questions;
    @Autowired
    private VocabularyService vocabulary;
    @Autowired
    private TestService tests;
    @Autowired
    private SectionService sections;
    @Autowired
    private SectionMembers members;
    @Autowired
    private DataSource dataSource;

    private UUID bank;
    private UUID otherBank;
    private UUID easy;
    private UUID medium;
    private UUID hard;
    private UUID fire;
    private UUID electrical;
    private UUID test;

    @BeforeEach
    void aBankWithAVocabulary() {
        emptyEveryTable(dataSource);
        inTenant(() -> {
            bank = banks.create("Fire safety", null).getId();
            otherBank = banks.create("Manual handling", null).getId();
            easy = vocabulary.addDifficulty("easy", 1).getId();
            medium = vocabulary.addDifficulty("medium", 2).getId();
            hard = vocabulary.addDifficulty("hard", 3).getId();
            fire = vocabulary.addTag("fire").getId();
            electrical = vocabulary.addTag("electrical").getId();
            test = tests.create("Annual refresher", null, 80).getId();
            return null;
        });
    }

    // ---------------------------------------------------------------- the population

    @Test
    void aPoolWithNoFiltersIsEveryQuestionInTheCompany() {
        inTenant(() -> {
            question("one", bank, medium, List.of(fire));
            question("two", otherBank, easy, List.of());
            return null;
        });

        assertThat(poolOf(pool("Anything", 1, null, null, null, List.of()))).isEqualTo(2);
    }

    @Test
    void aBankNarrowsIt() {
        inTenant(() -> {
            question("one", bank, medium, List.of());
            question("two", otherBank, medium, List.of());
            return null;
        });

        assertThat(poolOf(pool("Fire only", 1, bank, null, null, List.of()))).isEqualTo(1);
    }

    /**
     * "Medium or harder" is a rank range, and that is why difficulty is not another tag.
     *
     * <p>A range keeps meaning what the author meant when the company inserts a level in the middle
     * of its scale. A stored set of level ids would quietly stop including the new one, and nobody
     * would be told — which is the shape of every bug this issue is about.
     */
    @Test
    void difficultyIsARangeAndNotASetOfLevels() {
        inTenant(() -> {
            question("easy one", bank, easy, List.of());
            question("medium one", bank, medium, List.of());
            question("hard one", bank, hard, List.of());
            return null;
        });

        assertThat(poolOf(pool("Medium or harder", 1, null, 2, null, List.of())))
            .isEqualTo(2);
        assertThat(poolOf(pool("Easy only", 1, null, null, 1, List.of())))
            .isEqualTo(1);
        assertThat(poolOf(pool("Medium exactly", 1, null, 2, 2, List.of())))
            .isEqualTo(1);
    }

    @Test
    void aQuestionNobodyGradedIsExcludedTheMomentASectionAsksAboutDifficulty() {
        inTenant(() -> {
            question("ungraded", bank, null, List.of());
            question("medium one", bank, medium, List.of());
            return null;
        });

        assertThat(poolOf(pool("Anything", 1, null, null, null, List.of())))
            .as("a section that does not ask does not care")
            .isEqualTo(2);
        assertThat(poolOf(pool("Anything graded", 1, null, 1, null, List.of())))
            .as("and one that does cannot include a question nobody graded -- the correct answer, "
                + "and an unwelcome one, which is why the count is shown before publishing")
            .isEqualTo(1);
    }

    /**
     * Two tags means questions carrying <b>both</b>.
     *
     * <p>The narrower reading, chosen because it can only ever make a pool smaller — and a pool
     * that is too small is refused loudly. "Any of these" widens silently, and it is expressible as
     * two sections anyway.
     */
    @Test
    void aQuestionMustCarryEveryTagTheSectionNames() {
        inTenant(() -> {
            question("both", bank, medium, List.of(fire, electrical));
            question("one of them", bank, medium, List.of(fire));
            question("neither", bank, medium, List.of());
            return null;
        });

        assertThat(poolOf(pool("Fire", 1, null, null, null, List.of(fire)))).isEqualTo(2);
        assertThat(poolOf(pool("Both", 1, null, null, null, List.of(fire, electrical))))
            .isEqualTo(1);
    }

    @Test
    void aRetiredQuestionLeavesEveryFutureDraw() {
        UUID retired = inTenant(() -> {
            question("staying", bank, medium, List.of());
            Question going = question("going", bank, medium, List.of());
            return going.getId();
        });
        assertThat(poolOf(pool("Anything", 1, null, null, null, List.of()))).isEqualTo(2);

        // Served first, so "delete" retires rather than deletes outright (T-6.2).
        inTenant(() -> {
            UUID version = questions.currentVersionOf(questions.get(retired)).getId();
            servedVersions.markServed(version);
            questions.delete(retired);
            return null;
        });

        assertThat(poolOf(pool("Anything", 1, null, null, null, List.of())))
            .as("it stays for the attempts that already used it and leaves every future draw")
            .isEqualTo(1);
    }

    // ---------------------------------------------------------------- the refusal

    @Test
    void aSectionThatCannotBeFilledIsRefusedWithBothNumbers() {
        inTenant(() -> {
            question("one", bank, medium, List.of(fire));
            question("two", bank, medium, List.of(fire));
            return null;
        });

        assertThatThrownBy(() -> pool("Five of them", 5, null, null, null, List.of(fire)))
            .as("the count is in the message, because \"not enough questions\" without one leaves "
                + "an author guessing whether they are two short or two hundred")
            .hasMessageContaining("asks for 5 questions and its pool has 2");
    }

    @Test
    void narrowingAFilterUntilThePoolEmptiesIsRefusedTheSameWay() {
        inTenant(() -> {
            question("one", bank, easy, List.of());
            question("two", bank, easy, List.of());
            return null;
        });
        UUID section = pool("Two easy", 2, null, null, 1, List.of());

        assertThatThrownBy(() -> inTenant(() ->
            sections.drawsFrom(section, null, 3, null, 2, List.of())))
            .as("nothing is hard, so this section could only ever serve an empty form")
            .hasMessageContaining("its pool has 0");
    }

    @Test
    void anAuthorCanWatchTheCountBeforeSaving() {
        inTenant(() -> {
            question("one", bank, medium, List.of(fire));
            question("two", bank, hard, List.of(fire));
            return null;
        });
        UUID section = pool("Two of them", 2, null, null, null, List.of(fire));

        assertThat(inTenant(() -> sections.poolSize(section)))
            .as("the same predicate the draw uses, so the number an author sees is the number a "
                + "learner will get")
            .isEqualTo(2);
    }

    @Test
    void aFixedSectionHasNoPoolAndSaysSo() {
        UUID section = inTenant(() -> sections.addFixed(test, "Named").getId());

        assertThatThrownBy(() -> inTenant(() -> sections.poolSize(section)))
            .hasMessageContaining("holds the questions the author named");
    }

    @Test
    void aSectionThatDrawsNothingIsRefusedBeforeItCanServeAnEmptyForm() {
        assertThatThrownBy(() -> inTenant(() -> sections.addPool(test, "Nothing", 0)))
            .hasMessageContaining("serves an empty form");
    }

    @Test
    void aTagNobodyDefinedIsRefusedRatherThanDrawnFrom() {
        assertThatThrownBy(() -> pool("Made up", 1, null, null, null, List.of(UUID.randomUUID())))
            .as("the vocabulary is what turns a mistyped tag from a shorter exam into a refused "
                + "write (T-6.1)")
            .hasMessageContaining("no tag");
    }

    // ---------------------------------------------------------------- helpers

    @Autowired
    private com.xenopsoftware.learn.assessment.question.ServedVersions servedVersions;

    private UUID pool(String title, int drawCount, UUID bankId, Integer minRank, Integer maxRank,
            List<UUID> tagIds) {
        return inTenant(() -> {
            TestSection section = sections.addPool(test, title, drawCount);
            return sections.drawsFrom(section.getId(), bankId, minRank, maxRank, drawCount, tagIds)
                .getId();
        });
    }

    private int poolOf(UUID sectionId) {
        return inTenant(() -> sections.poolSize(sectionId));
    }

    private Question question(String name, UUID bankId, UUID difficultyId, List<UUID> tagIds) {
        Question question = questions.create(bankId, name, JSON.readTree("""
            {"type":"true-false","stem":"%s?","answerKey":{"correct":["true"]}}
            """.formatted(name)));
        questions.describe(question.getId(), difficultyId, tagIds);
        return question;
    }

    private <T> T inTenant(Supplier<T> body) {
        return TenantContext.callWithUnchecked(TENANT, body);
    }
}
