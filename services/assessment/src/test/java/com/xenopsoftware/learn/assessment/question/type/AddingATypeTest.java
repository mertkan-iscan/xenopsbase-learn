package com.xenopsoftware.learn.assessment.question.type;

import static org.assertj.core.api.Assertions.assertThat;

import com.xenopsoftware.learn.assessment.PostgresTestHarness;
import com.xenopsoftware.learn.assessment.bank.BankService;
import com.xenopsoftware.learn.assessment.question.Question;
import com.xenopsoftware.learn.assessment.question.QuestionService;
import com.xenopsoftware.learn.common.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-6.3's fourth criterion, as a demonstration rather than a claim (T-6.3).
 *
 * <p>"Adding a type is a documented, bounded change; demonstrated by adding one in a test." So this
 * adds an eleventh — a confidence-weighted true/false, invented for the test — and the only thing
 * it touches is the class below. No registry edit, no enum, no switch, no migration, and no change
 * to authoring, because the body is a {@code jsonb} document (T-6.2) and the registry finds beans
 * (T-6.3).
 *
 * <p>The assertion that carries the weight is the last one: a question of a type this build learned
 * five seconds ago is created, versioned and read back through the ordinary service. If adding a
 * type needed anything else, that call is where it would fail.
 */
@SpringBootTest
@Import(AddingATypeTest.AnEleventhType.class)
class AddingATypeTest extends PostgresTestHarness {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The whole change. One bean.
     *
     * <p>It is deliberately a type nobody asked for: a true/false where the learner also says how
     * sure they are, which is a real assessment technique and is emphatically not one of the ten.
     * Picking a plausible-but-absent type is what makes this a test of the mechanism rather than a
     * test of a type that was going to exist anyway.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class AnEleventhType {

        @Bean
        QuestionTypeDefinition confidenceWeightedQuestionType() {
            return new QuestionTypeDefinition() {

                @Override
                public String code() {
                    return "confidence-true-false";
                }

                @Override
                public String displayName() {
                    return "True or false, with confidence";
                }

                @Override
                public void validateAsked(JsonNode options, JsonNode answerKey) {
                    JsonNode correct = answerKey == null ? null : answerKey.get("correct");
                    if (correct == null || !correct.isBoolean()) {
                        throw new IllegalArgumentException("The key needs a boolean 'correct'");
                    }
                }

                @Override
                public void validateResponse(JsonNode options, JsonNode response) {
                    JsonNode confidence = response == null ? null : response.get("confidence");
                    if (confidence != null && !confidence.isNumber()) {
                        throw new IllegalArgumentException("'confidence' is a number");
                    }
                }

                @Override
                public Optional<Correctness> grade(JsonNode options, JsonNode answerKey,
                        JsonNode response) {
                    JsonNode chosen = response == null ? null : response.get("chosen");
                    boolean right = chosen != null && chosen.isBoolean()
                        && chosen.asBoolean() == answerKey.get("correct").asBoolean();
                    return Optional.of(Correctness.of(right));
                }
            };
        }
    }

    @Autowired
    private QuestionTypes types;

    @Autowired
    private QuestionService questions;

    @Autowired
    private BankService banks;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void emptyTheTables() {
        emptyEveryTable(dataSource);
    }

    @Test
    void theRegistryFoundItWithoutBeingTold() {
        assertThat(types.all()).extracting(QuestionTypeDefinition::code)
            .contains("confidence-true-false")
            .as("and the ten that ship are still there")
            .contains("single-choice", "hotspot", "essay");
    }

    @Test
    void anAuthorCanSaveOneAndAskItAgain() {
        Question saved = TenantContext.callWithUnchecked("acme", () -> {
            UUID bank = banks.create("Fire safety", null).getId();
            return questions.create(bank, "Confidence check",
                JSON.readTree("""
                    {"type":"confidence-true-false","stem":"Water conducts electricity.",
                     "answerKey":{"correct":true}}"""));
        });

        JsonNode readBack = TenantContext.callWithUnchecked("acme",
            () -> JSON.readTree(questions.currentVersionOf(questions.get(saved.getId())).getBody()));

        assertThat(readBack.get("type").asString()).isEqualTo("confidence-true-false");
        assertThat(types.grade(readBack, JSON.readTree("{\"chosen\":true,\"confidence\":0.9}")))
            .contains(new Correctness(1, 1));
    }

    @Test
    void andItsOwnRulesAreEnforcedOnSaveLikeAnyOther() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            TenantContext.callWithUnchecked("acme", () -> {
                UUID bank = banks.create("Fire safety", null).getId();
                return questions.create(bank, "Broken", JSON.readTree("""
                    {"type":"confidence-true-false","stem":"?","answerKey":{"correct":"yes"}}"""));
            }))
            .hasMessageContaining("boolean 'correct'");
    }
}
