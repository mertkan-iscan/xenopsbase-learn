-- V3 — a test, and the scoring policy that decides what its result means (T-6.4).
--
-- THE COLUMN THIS TABLE EXISTS FOR IS `pass_mark_percent`, and the reason it is
-- a WHOLE PERCENT is the criterion about the pass boundary.
--
-- A pass rule written against raw points breaks the moment a section changes
-- length or a question's weight is edited, and it breaks quietly: the test still
-- runs, the pass mark just means something different than it did last month. So
-- gates and reports read a SCALED score, 0..1, and the pass mark is expressed on
-- the same scale.
--
-- Why a whole percent rather than a numeric fraction. The result a learner is
-- SHOWN is a percentage, and the verdict is a comparison. If those two round
-- differently, sooner or later a learner reads "80%" next to "failed" against a
-- pass mark of 80 -- which is what happens when the score is 0.795 and the
-- display rounds half up. There is no explaining that to anybody.
--
-- The rule that makes it impossible: the displayed percentage is the scaled
-- score FLOORED to a whole percent, and the verdict is that same integer
-- compared with this column. They are not two roundings that agree; they are one
-- number used twice. Keeping the pass mark a whole percent is what lets that be
-- true, and it is the whole reason this is a smallint.

CREATE TABLE test (
    id        uuid         PRIMARY KEY,
    tenant_id varchar(64)  NOT NULL,

    title     varchar(512) NOT NULL,
    description text,

    -- 0..100. Zero is a legitimate value -- a practice quiz nobody can fail is a
    -- real thing -- and it is not the default, because a test with no pass mark
    -- that silently passes everybody is the failure this column exists to make
    -- visible.
    pass_mark_percent smallint NOT NULL,

    -- NEGATIVE MARKING, OFF BY DEFAULT. It is a deterrent against guessing and
    -- it is only meaningful where guessing has computable odds -- a fixed set of
    -- choices. Applying it to a typed answer punishes a typo rather than a
    -- guess, which is why the QUESTION TYPE has the last word (see
    -- QuestionTypeDefinition.guessable) and this flag only turns the mechanism
    -- on at all.
    negative_marking boolean NOT NULL DEFAULT false,

    -- What a question in this test is worth, and how its partial correctness is
    -- turned into marks, unless a section says otherwise (T-6.5).
    --
    -- SCORING LIVES ON THE TEST AND NEVER IN THE ANSWER KEY, and that is the
    -- decision behind the "per-option weights" idea this task's scope raises.
    -- An answer key is immutable once served (ADR-0106), deliberately, so a
    -- weight written into one could not be corrected without a new version of
    -- the question -- and a new version orphans every attempt's form. Correcting
    -- a mistaken weight and rescoring is a thing this product must be able to
    -- do, so weights live where they can be edited.
    default_points numeric(9, 4) NOT NULL DEFAULT 1,
    CONSTRAINT ck_test_default_points CHECK (default_points > 0),

    -- ALL_OR_NOTHING or PARTIAL_CREDIT. A varchar rather than an enum type for
    -- the reason every other status column here is one: adding a value to a
    -- Postgres enum is a migration that cannot run inside a transaction with
    -- other statements, and this is a vocabulary that will grow.
    default_mode varchar(24) NOT NULL DEFAULT 'ALL_OR_NOTHING',

    -- Deducted from a question answered and wholly wrong, when negative marking
    -- is on and the type allows it. Never applied to an unanswered question:
    -- penalising a blank is a tax on honesty, and it converts "I do not know"
    -- into a worse outcome than a guess, which is the opposite of the point.
    penalty_points numeric(9, 4) NOT NULL DEFAULT 0,

    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT ck_test_pass_mark CHECK (pass_mark_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_test_penalty CHECK (penalty_points >= 0),
    CONSTRAINT ck_test_default_mode
        CHECK (default_mode IN ('ALL_OR_NOTHING', 'PARTIAL_CREDIT'))
);

CREATE INDEX ix_test_tenant ON test (tenant_id, updated_at DESC);

-- WHAT IS NOT HERE, AND WHOSE IT IS.
--
--   * `test_section` and `test_form` are T-6.5's (#64). This table is what they
--     hang from, and it carries only what a SCORE means -- not what is asked.
--   * `score_raw` and `score_scaled` belong on the attempt, which is T-6.6's
--     (#65). Both are computed here (Scores) and both must be stored there;
--     everything downstream reads the scaled one.
--   * A lifecycle (draft, published, withdrawn) is deliberately absent, for the
--     reason course has none: publishing a new version of a test must not
--     rewrite the history of learners who sat the old one, which is a versioning
--     design rather than a state column.
