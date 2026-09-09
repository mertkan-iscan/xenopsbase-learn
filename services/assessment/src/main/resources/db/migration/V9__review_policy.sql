-- V9 — what a learner may see after submitting, and when (T-6.9).
--
-- WHY IT IS A POLICY AND NOT A SETTING, in the issue's words: "for a formative
-- quiz, showing the correct answers immediately is the entire point. For a
-- certification exam drawn from a bank, it hands the bank to anyone willing to
-- sit the test once and screenshot it. Both are legitimate; the product has to
-- express both, and the default has to be the safe one."

ALTER TABLE test
    -- SCORE_ONLY, SCORE_AND_WHICH_WRONG, or FULL.
    --
    -- DEFAULT SCORE_ONLY, which is the restrictive option the criterion asks
    -- for. Opening it up is a deliberate act by the author, and it is the axis
    -- that actually carries the risk: the failure the issue names -- handing the
    -- bank to anybody who sits the test once -- is entirely a question of
    -- visibility.
    ADD COLUMN review_visibility varchar(24) NOT NULL DEFAULT 'SCORE_ONLY',

    -- IMMEDIATELY, AFTER_ALL_ATTEMPTS, or AFTER_DATE.
    --
    -- DEFAULT IMMEDIATELY, and that is not a relaxation. Timing gates what
    -- visibility permits, and under SCORE_ONLY there is nothing to gate: a
    -- learner seeing their own score the moment they finish discloses nothing
    -- and is the minimum a person is owed after sitting an exam. Defaulting to
    -- "you may not see your score until you have used every attempt" would be
    -- restrictive in a way that protects nothing and that every author would
    -- turn off -- which is how a safe default becomes a habit of overriding
    -- defaults.
    ADD COLUMN review_timing varchar(24) NOT NULL DEFAULT 'IMMEDIATELY',

    -- AFTER_DATE only. When review opens.
    ADD COLUMN review_after timestamptz,

    ADD CONSTRAINT ck_test_review_visibility CHECK (
        review_visibility IN ('SCORE_ONLY', 'SCORE_AND_WHICH_WRONG', 'FULL')),
    ADD CONSTRAINT ck_test_review_timing CHECK (
        review_timing IN ('IMMEDIATELY', 'AFTER_ALL_ATTEMPTS', 'AFTER_DATE')),

    -- AFTER_DATE without a date is a review that opens at an unspecified moment,
    -- which in practice means whichever way the null comparison happens to fall.
    -- Refused where it is cheap to refuse.
    ADD CONSTRAINT ck_test_review_after CHECK (
        (review_timing = 'AFTER_DATE' AND review_after IS NOT NULL)
        OR (review_timing <> 'AFTER_DATE' AND review_after IS NULL));

-- ---------------------------------------------------------------------------
-- WHAT IS DELIBERATELY NOT A COLUMN
-- ---------------------------------------------------------------------------
--
-- PER-QUESTION FEEDBACK. The criterion asks for it "authored on the question
-- version, so it stays correct across edits" -- and the question version's
-- content is one jsonb document (T-6.2), frozen once served (ADR-0106). So
-- feedback belongs INSIDE that document, beside the stem it explains, where the
-- immutability trigger already protects it.
--
-- A column here, or a table keyed by question, would be feedback that could be
-- edited after somebody read it -- which is the precise thing "stays correct
-- across edits" is asking to prevent. It is validated by QuestionTypes like
-- every other field of the body, and it needed no migration at all.
--
-- AFTER_ALL_ATTEMPTS ON A TEST WITH NO ATTEMPT LIMIT. Not expressible, and
-- refused by TestService rather than by a constraint, because the two columns
-- live on the same row and a CHECK could state it -- but the sentence an author
-- needs ("this would mean never") is not something a constraint can say.
