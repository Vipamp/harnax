-- V31: the last sync result becomes data on the source
--
-- Every install path already computed a full report (saved / failed / flagged) and then dropped it
-- into a log line plus one browser toast, so a source that had been failing since yesterday looked
-- exactly like one that synced cleanly. These three columns keep the newest result queryable.
--
-- No history table: the question the list page asks is "is this source usable now", and nothing
-- consumes a trajectory of past runs (spec 2.1, D2).
--
-- `last_sync_detail` is MEDIUMTEXT rather than TEXT for the reason V9 records: the JSON carries one
-- entry per failed skill with a reason up to 500 characters, and a TEXT column would cut that mid
-- value, leaving the front end an unparseable fragment instead of a report.

ALTER TABLE skill_repository
    ADD COLUMN last_sync_time   DATETIME      NULL COMMENT 'When the last sync finished, NULL until the first run',
    ADD COLUMN last_sync_status VARCHAR(16)   NULL COMMENT 'SUCCESS / PARTIAL / FAILED / EMPTY, NULL until the first run',
    ADD COLUMN last_sync_detail MEDIUMTEXT    NULL COMMENT 'Last sync report as JSON: saved/installed/updated/failed/flagged/error';
