-- V8 — what has already been announced about a learner's progress (T-5.8).
--
-- WHY THERE IS STATE HERE AT ALL. Completion is announced once, on a transition
-- (T-3.7), and a transition needs no bookkeeping: it happened or it did not.
-- Progress is a number that moves every ten seconds, and the learner home screen
-- needs it -- so the question is not whether to publish it but HOW OFTEN, and
-- the answer has to be remembered per row or it cannot be enforced.
--
-- The alternative is publishing every heartbeat: ~500 outbox rows per second at
-- 5,000 concurrent learners, on the hot path, which is exactly the volume
-- ADR-0107 keeps off it. These two columns are what make "at most one event per
-- ten percent, and no more than one per five minutes" a fact rather than a hope.
ALTER TABLE learner_node_progress ADD COLUMN announced_percent smallint;
ALTER TABLE learner_node_progress ADD COLUMN announced_at timestamptz;

COMMENT ON COLUMN learner_node_progress.announced_percent IS
    'Coverage as a percentage of the extent at the last progress event, or null if none';
COMMENT ON COLUMN learner_node_progress.announced_at IS
    'When that event was written to the outbox';
