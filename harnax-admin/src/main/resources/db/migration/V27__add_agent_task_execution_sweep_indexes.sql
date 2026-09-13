-- V27: index what the five-minute sweeps read on agent_task_execution
--
-- The housekeeping job put two full-range statements on a five-minute clock:
--   deleteStaleRunning  WHERE status = 0 AND create_time < ?   (a lock whose holder never came back)
--   deleteOldExecutions WHERE                 create_time < ?   (the guard table's retention)
-- V1 keyed this table on task_id, trigger_time and the (task_id, trigger_time) unique constraint, so
-- neither predicate had an index and both statements read every row. That is not just cost: InnoDB
-- takes next-key/gap locks over whatever a scanning UPDATE/DELETE walks, and the range it walks
-- includes what tryAcquireLock inserts through uk_task_trigger. Every sweep therefore contends with
-- the triggers firing around it, and whichever side rolls back loses that fire silently. This load
-- arrived with the housekeeping job, so the index has to arrive with it.
--
-- (status, create_time) answers the leaked-lock sweep with an equality prefix plus a range on the
-- column the deadline cuts; (create_time) answers the retention sweep's bare range. Named after the
-- keys this table already carries (idx_task_id, idx_trigger_time), the same way agent_task_log's
-- idx_status / idx_create_time pair is named.
--
-- Nothing is dropped here, unlike V18/V19: idx_task_id is a redundant left prefix of uk_task_trigger,
-- but no statement reads it and it is not what these sweeps scan — dropping it is its own decision.

ALTER TABLE agent_task_execution
    ADD INDEX idx_status_create_time (status, create_time),
    ADD INDEX idx_create_time (create_time);
