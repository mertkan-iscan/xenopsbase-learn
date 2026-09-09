-- V10 — how far a learner may be credited, when something inside the item is
-- waiting to be answered (T-5.4).
--
-- WHAT MAKES A BLOCKING INTERSTITIAL REAL. A pause a player performs is a pause
-- a player can decline to perform, and T-5.4 asks for one enforced "by the same
-- interval accounting that measures progress, not by client honesty". This
-- column is that enforcement: coverage past it is not credited, so a learner
-- whose player skipped the question can watch the rest of the video and still
-- not complete the item. There is no version of this that a browser can move.
--
-- IT IS THE ONE PIECE OF POLICY HERE THAT IS ABOUT THE LEARNER. The threshold
-- and the seek rule belong to the item and may be assumed unchanged for an hour
-- (`policy_seen_at`); this one changes the instant the learner answers. Caching
-- it on the same terms would leave somebody staring at a video that will not
-- credit them for up to an hour after they did what was asked.
--
-- So it is cached and RE-ASKED ON DEMAND: `ProgressService` asks catalog again
-- when a batch would actually cross the cached value, which happens once per
-- interstitial rather than once per heartbeat. The stale value is only ever
-- used to decide whether asking is worth a hop.
ALTER TABLE learner_node_progress
    ADD COLUMN blocked_after_second integer;

COMMENT ON COLUMN learner_node_progress.blocked_after_second IS
    'Catalog''s earliest blocking interstitial this learner has not answered (T-5.4), or NULL '
    'when nothing blocks them. Coverage past it is not credited. Re-read from catalog whenever a '
    'batch would cross it, because unlike the other copied policy this one is about the learner.';
