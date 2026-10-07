-- Forward increment instead of a baseline fold, which db/migration/README.md documents as the exception:
-- the rule is "edit the baseline and recreate the database", but this database holds a model provider
-- API key that only a human can re-enter, so it cannot be rebuilt as part of a schema change.
-- The column is dropped from the baseline so V1 keeps the checksum already recorded in the history
-- table; folding it back into V1 is the right move the next time the database is rebuilt anyway.
ALTER TABLE `agent`
    ADD COLUMN `session_memory_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Whether the agent also keeps a per-session memory layer that is later promoted into the long-term one (0: no, 1: yes)' AFTER `memory_enabled`;
