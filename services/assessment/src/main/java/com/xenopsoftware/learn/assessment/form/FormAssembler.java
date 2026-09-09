package com.xenopsoftware.learn.assessment.form;

import com.xenopsoftware.learn.assessment.exam.QuestionPool;
import com.xenopsoftware.learn.assessment.exam.SectionMembers;
import com.xenopsoftware.learn.assessment.exam.SectionService;
import com.xenopsoftware.learn.assessment.exam.TestDefinition;
import com.xenopsoftware.learn.assessment.exam.TestSection;
import com.xenopsoftware.learn.assessment.exam.TestService;
import com.xenopsoftware.learn.assessment.question.Question;
import com.xenopsoftware.learn.assessment.question.QuestionService;
import com.xenopsoftware.learn.assessment.question.QuestionVersion;
import com.xenopsoftware.learn.assessment.question.ServedVersions;
import com.xenopsoftware.learn.assessment.question.type.QuestionTypeDefinition;
import com.xenopsoftware.learn.assessment.question.type.QuestionTypes;
import com.xenopsoftware.learn.assessment.scoring.QuestionScoring;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turning the instruction into the record (T-6.5).
 *
 * <p>One method that matters. {@link #assemble} runs the sections in order, draws or reads each
 * one's questions, shuffles what the section asked to be shuffled, and writes a form that never
 * changes again.
 *
 * <h2>The refusal, which is this task's stated failure mode</h2>
 *
 * <p>"A pool query that matches fewer questions than the section asks for. <b>Silently serving a
 * short test is the worst outcome</b> — the learner's score is out of a different total and nobody
 * is told." So a draw that comes up short is refused here, at attempt start, with the numbers in
 * the sentence. {@code SectionService} refuses the same thing at authoring time, through the same
 * {@link QuestionPool}, and both checks are needed: a pool large enough in March is not large
 * enough in June if somebody retired half the bank, and nothing about editing the section notices.
 *
 * <h2>Why the order is recorded rather than re-derivable</h2>
 *
 * <p>The seed is stored, but the shuffled order is stored too — which looks redundant until you ask
 * what re-deriving it would couple. A review screen (T-6.9) has to render exactly what was seen; if
 * that comes from replaying a seed, it depends on this shuffle algorithm never changing, in any
 * language, forever. The order is a few strings. The coupling is not worth saving them.
 *
 * <h2>What is not here</h2>
 *
 * <p>The attempt. {@code attemptId} arrives from T-6.6 (#65), which owns the lifecycle, the clock
 * and the limits; assembling the form is the thing starting an attempt <em>does</em>, so it is
 * written here and called from there. {@code test_form.attempt_id} is unique, so a second call for
 * the same attempt is refused by the database rather than by whichever caller remembered to look.
 */
@Service
public class FormAssembler {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom SEEDS = new SecureRandom();

    private final TestService tests;
    private final SectionService sections;
    private final SectionMembers members;
    private final QuestionPool pool;
    private final QuestionService questions;
    private final QuestionTypes types;
    private final ServedVersions served;
    private final Forms forms;

    public FormAssembler(TestService tests, SectionService sections, SectionMembers members,
            QuestionPool pool, QuestionService questions, QuestionTypes types,
            ServedVersions served, Forms forms) {
        this.tests = tests;
        this.sections = sections;
        this.members = members;
        this.pool = pool;
        this.questions = questions;
        this.types = types;
        this.served = served;
        this.forms = forms;
    }

    /**
     * Assembles one learner's form and writes it.
     *
     * <p>In one transaction with the {@code first_served_at} stamps, so a form that rolls back
     * leaves no version wrongly frozen — and a version frozen by a form that committed can never be
     * edited afterwards, which is ADR-0106's whole point.
     *
     * @param attemptId the attempt this form belongs to (T-6.6). One form per attempt, enforced by
     *                  the database
     */
    @Transactional
    public Form assemble(UUID testId, UUID attemptId) {
        return assemble(testId, attemptId, SEEDS.nextLong());
    }

    /** The same, with the seed supplied — for a test that needs two learners to differ provably. */
    @Transactional
    public Form assemble(UUID testId, UUID attemptId, long seed) {
        TestDefinition test = tests.get(testId);
        List<TestSection> ordered = sections.of(testId);
        if (ordered.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This test has no sections, so there is nothing to sit.");
        }

        Random shuffle = new Random(seed);
        List<FormItem> items = new ArrayList<>();
        int position = 0;

        for (TestSection section : ordered) {
            List<UUID> questionIds = questionsFor(section);
            if (section.isShuffleQuestions()) {
                questionIds = new ArrayList<>(questionIds);
                java.util.Collections.shuffle(questionIds, shuffle);
            }
            QuestionScoring scoring = section.scoringGiven(test.defaultScoring());

            for (UUID questionId : questionIds) {
                Question question = questions.get(questionId);
                QuestionVersion version = questions.currentVersionOf(question);
                // The version becomes history the moment it is put in front of somebody
                // (ADR-0106). Idempotent and first-wins, so two learners starting in the same
                // millisecond do not race each other into an error.
                served.markServed(version.getId());

                items.add(new FormItem(UUID.randomUUID(), position++, section.getId(),
                    version.getId(), orderOf(version, section.isShuffleOptions(), shuffle),
                    scoring));
            }
        }

        return forms.write(testId, attemptId, seed, items);
    }

    /** What a learner was asked, read back. */
    @Transactional(readOnly = true)
    public Form of(UUID attemptId) {
        return forms.ofAttempt(attemptId);
    }

    // ---------------------------------------------------------------- the draw

    private List<UUID> questionsFor(TestSection section) {
        if (section.getSelection() == TestSection.Selection.FIXED) {
            List<UUID> named = members.questionsOf(section.getId());
            if (named.isEmpty()) {
                throw shortForm(section, 0, 0);
            }
            return named;
        }
        List<UUID> tagIds = members.tagsOf(section.getId());
        int wanted = section.getDrawCount();
        List<UUID> drawn = pool.draw(section, tagIds);
        if (drawn.size() < wanted) {
            // Counted rather than inferred from what came back, so the message says how far short
            // the pool is rather than how far short this draw happened to be.
            throw shortForm(section, wanted, pool.count(section, tagIds));
        }
        return drawn;
    }

    private static ResponseStatusException shortForm(TestSection section, int wanted,
            int available) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "The section '" + section.getTitle() + "' asks for " + wanted + " questions and can "
            + "supply " + available + ". Serving what there is would score this learner out of a "
            + "different total from everybody else, and nothing in the result would say so.");
    }

    // ---------------------------------------------------------------- the shuffle

    /**
     * The option lists, in the order this learner will see them.
     *
     * <p>Which lists may be reordered is the <b>type's</b> answer
     * ({@link QuestionTypeDefinition#shufflableOptionFields()}), not this class's — T-6.3's one
     * dispatch point, applied to one more question. So an ordering question's items are shuffled
     * and a fill-in's blanks are not, and this method never learns why.
     */
    private Map<String, List<String>> orderOf(QuestionVersion version, boolean shuffleOptions,
            Random shuffle) {
        if (!shuffleOptions) {
            return Map.of();
        }
        JsonNode body = JSON.readTree(version.getBody());
        JsonNode options = body.get("options");
        if (options == null || !options.isObject()) {
            return Map.of();
        }
        QuestionTypeDefinition definition = types.find(body.get("type").asString()).orElseThrow();

        Map<String, List<String>> order = new LinkedHashMap<>();
        for (String field : definition.shufflableOptionFields()) {
            JsonNode list = options.get(field);
            if (list == null || !list.isArray() || list.isEmpty()) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode entry : list.valueStream().toList()) {
                JsonNode id = entry.get("id");
                if (id != null && id.isTextual()) {
                    ids.add(id.asString());
                }
            }
            if (ids.size() > 1) {
                java.util.Collections.shuffle(ids, shuffle);
                order.put(field, List.copyOf(ids));
            }
        }
        return order;
    }
}
