-- Forward increment, for the same reason V7 is one: this database holds a model provider API key that only a
-- human can re-enter, so it cannot be rebuilt as part of a schema change, and V1..V7 are applied and
-- checksum-verified on every boot.
-- One candidate now writes more than the owner's long-term `MEMORY.md`. A conversation's dated ledgers merge
-- into the agent's own ledger for the same day, so approving one row applies the conclusion layer AND up to K
-- daily files, each with its own store version as precondition. `targets` carries those extras: a JSON array of
-- {path, expectedVersion, baseText, mergedText} naming each `memory/<YYYY-MM-DD>.md` of the agent's layer, the
-- version this merge read there, the text found at that version, and the complete new text.
-- Still one row per conversation, but that row is one decision rather than one object. `merged_md`, `base_md`
-- and `base_version` keep naming the conclusion layer alone — the daily files carry their own preconditions
-- inside `targets`, because a second text written on the strength of `base_version` would have no version to
-- be checked against and an approval could overwrite a daily file nobody had read.
-- `targets` is NULL when a merge proposed no daily file, which is the natural shape of a candidate that only
-- curated the conclusion layer, not a compatibility marker.
ALTER TABLE `memory_draft`
  ADD COLUMN `targets` mediumtext COMMENT 'JSON array of {path, expectedVersion, baseText, mergedText}: each daily file this candidate writes in the agent''s long-term layer, with the version and bytes it was merged against; NULL when the candidate has no daily target' AFTER `sources`,
  MODIFY COLUMN `base_version` bigint NOT NULL COMMENT 'Version of the conclusion object the merge read; 0 means it did not exist, so the approval is a create. Daily targets carry their own version in `targets`',
  MODIFY COLUMN `sources` mediumtext NOT NULL COMMENT 'JSON array of {path, content}: every conversation-layer object this candidate actually merged, with the bytes it read there - objects left out stay in the conversation for a later candidate';
