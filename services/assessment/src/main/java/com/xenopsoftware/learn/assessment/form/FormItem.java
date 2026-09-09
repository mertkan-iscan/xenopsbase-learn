package com.xenopsoftware.learn.assessment.form;

import com.xenopsoftware.learn.assessment.scoring.QuestionScoring;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One question, exactly as one learner was served it (T-6.5).
 *
 * @param position   where it came in the form, across every section. The order the learner met them
 *                   in, which is what a review screen renders
 * @param sectionId  which section produced it, so a per-section score (T-6.4) can be composed from
 *                   the form alone
 * @param questionVersionId <b>the version</b>, never the question. Three months later "what exactly
 *                   did this person see" has to be answerable from this row, and a question id
 *                   answers a different question — what we ask now (ADR-0106)
 * @param optionOrder the option lists as served: field name to ids in order,
 *                   {@code {"choices":["c","a","b"]}}. Empty when nothing here was shufflable or
 *                   the section did not ask for it
 * @param scoring    what it was worth when it was served, copied rather than looked up — an author
 *                   raising a weight next month must not silently rescore an exam already sat
 */
public record FormItem(UUID id, int position, UUID sectionId, UUID questionVersionId,
                       Map<String, List<String>> optionOrder, QuestionScoring scoring) {

    public FormItem {
        optionOrder = optionOrder == null ? Map.of() : Map.copyOf(optionOrder);
    }
}
