-- V10 — where a completion came from (ADR-0107, T-4.4).
--
-- WHY THIS COLUMN EXISTS NOW AND NOT BEFORE. Until T-4.4 every row in this
-- table came from one place: streaming, having measured coverage against an
-- encoded duration. A column recording that would have had one value, which is
-- the definition of a column not worth having.
--
-- That stopped being true the moment a SCORM package could say "this learner
-- finished" and be believed. ADR-0107 is explicit about what follows:
--
--   "Every export and every compliance view carries the source. A report that
--    mixes measured and self-reported completions without distinguishing them
--    is exactly the report this ADR exists to prevent -- it would be a claim
--    about a package's honesty presented as a claim about a person's
--    attendance."
--
-- So the column is added in the same change that creates the second kind of
-- row, rather than left for whoever writes the first compliance export.
--
--   DERIVED        this platform measured it (streaming, ADR-0107)
--   SELF_REPORTED  a package or a cmi5 statement said so (T-4.4, T-4.6)
--   MANUAL         a person with the permission marked it, audited (not yet)
--
-- DEFAULT 'DERIVED' AND THEN NOT NULL, in that order, because every row that
-- exists when this runs came from streaming and genuinely is derived. The
-- default stays on the column afterwards deliberately: a writer that forgets
-- to say gets the STRICTER answer, and a row claiming to be measured when it
-- was not is a bug somebody will find, where the reverse would quietly
-- downgrade real evidence.
ALTER TABLE node_completion
    ADD COLUMN source varchar(16) NOT NULL DEFAULT 'DERIVED';

ALTER TABLE node_completion
    ADD CONSTRAINT ck_node_completion_source
    CHECK (source IN ('DERIVED', 'SELF_REPORTED', 'MANUAL'));

-- NOT part of uq_node_completion. One learner finishing one node once is one
-- completion however it was evidenced -- adding the source to the key would let
-- a package assert a completion beside a measured one for the same node, and a
-- compliance count would then report the same person twice.
COMMENT ON COLUMN node_completion.source IS
    'DERIVED | SELF_REPORTED | MANUAL. Every compliance view must carry it (ADR-0107).';
