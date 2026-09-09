package com.xenopsoftware.learn.assessment.form;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one learner was asked, in the order they were asked it (T-6.5).
 *
 * <p><b>Random assembly without a recorded form is unreportable and undefendable.</b> You cannot
 * answer what a learner saw, you cannot compute item statistics, and you cannot rescore. This is
 * what turns randomisation from a liability into a feature — and it is why the record is written in
 * the same transaction as the draw rather than derived afterwards from a seed.
 *
 * @param seed the seed the shuffle used, recorded so a form can be <em>checked</em> and not so it
 *             can be regenerated. The items are the record and they are frozen by a trigger; this
 *             answers the support question "is this really what the assembler produces", which is
 *             otherwise unanswerable
 */
public record Form(UUID id, UUID testId, UUID attemptId, long seed, Instant createdAt,
                   List<FormItem> items) {

    public Form {
        items = List.copyOf(items);
    }
}
