-- Forward increment, the same exception V2 records: the rule is "edit the baseline and recreate the
-- database", but this database holds a model provider API key that only a human can re-enter, so it
-- cannot be rebuilt as part of a schema change.
-- The conversation layer no longer has a per-agent switch — every session of an agent with memory keeps
-- its own layer, and merging it into the long-term one is decided by a human on the review queue — so the
-- column that carried the answer has no reader left. Baseline and test schema already dropped it; folding
-- it back out of V1 is the right move the next time the database is rebuilt anyway.
ALTER TABLE `agent`
    DROP COLUMN `session_memory_enabled`;
