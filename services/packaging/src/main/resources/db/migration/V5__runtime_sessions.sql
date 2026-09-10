-- V5 — the two things V3 left to last-write-wins: time, and two tabs (T-4.4).
--
-- ==========================================================================
-- SESSION TIME AND TOTAL TIME ARE DIFFERENT NUMBERS
-- ==========================================================================
--
-- V3 stored `seconds_spent`, which is wall clock measured by the browser
-- between one save and the next. That is corroboration and stays exactly what
-- it was (ADR-0107) -- but it is not what the standard means by time, and it is
-- not what a package reads back.
--
-- SCORM has two elements and they answer two questions. `session_time` is how
-- long THIS launch has lasted and the package writes it. `total_time` is every
-- session added together, the LMS writes it, and the package can only read it.
-- A course that says "you have spent 3h 20m on this" is displaying the second
-- one, and until now this platform had no value to give it.
--
-- WHY A BASE COLUMN RATHER THAN ADDING EACH REPORT TO A RUNNING TOTAL
--
-- `session_time` is cumulative WITHIN a launch. A package commits at slide three
-- saying twelve minutes and at slide six saying thirty, meaning thirty in total
-- and not forty-two. Adding each report to `total_seconds` would count the first
-- twelve minutes twice, and a chatty package -- which is the normal kind -- would
-- inflate a learner's recorded time by roughly the number of times it committed.
--
-- So a launch records the total it STARTED at, and every save recomputes
-- `total_seconds = session_base_seconds + session_seconds`. Re-sending the same
-- commit produces the same total, which is what a PUT should mean.

ALTER TABLE package_runtime
    -- What the package says the CURRENT launch has lasted. Reset by the next
    -- launch; kept because a report that can only say "3h 20m in total" cannot
    -- answer "did they finish it in one sitting".
    ADD COLUMN session_seconds      integer NOT NULL DEFAULT 0,
    -- Every session added up, in the platform's own unit. Formatted back into
    -- whichever vocabulary the package speaks when the wrapper is seeded.
    ADD COLUMN total_seconds        integer NOT NULL DEFAULT 0,
    -- The value of `total_seconds` when this launch began. See above: without
    -- it, accumulation double-counts every commit after the first.
    ADD COLUMN session_base_seconds integer NOT NULL DEFAULT 0;

-- ==========================================================================
-- TWO TABS ARE NOT ONE LEARNER TWICE
-- ==========================================================================
--
-- Until now, opening the same course in a second tab produced two wrappers, two
-- CMI maps and one row, and whichever tab committed last decided what the row
-- said. The learner who worked through slides one to twenty in the first tab
-- and then clicked Refresh got their progress replaced by the empty map the
-- second tab started with -- silently, and with no way to tell afterwards that
-- it had happened.
--
-- THE RULE, WRITTEN DOWN RATHER THAN EMERGENT: the most recent launch owns the
-- registration. Opening the package mints a session id and stores it here; a
-- save quoting any other session is refused. The refused tab is TOLD, stops
-- committing, and says so on screen -- which is the part that matters, because
-- the failure this replaces was invisible.
--
-- WHY NOT A VERSION COLUMN AND A MERGE. Optimistic locking is the right answer
-- when two writers race on one field and the loser can retry. Here the two
-- writers hold entire divergent data models -- different suspend blobs,
-- different interaction arrays -- and there is no merge of them that means
-- anything to the package that wrote them. One of the two has to lose, so the
-- design chooses which one, and tells it.
--
-- NULLABLE, because every row that existed before this migration was written by
-- a launch that had no session id, and a save from one of those is accepted
-- (see RuntimeService): a learner mid-course when this deploys is not asked to
-- start again.
ALTER TABLE package_runtime
    ADD COLUMN active_session uuid;
