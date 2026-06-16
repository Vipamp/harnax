-- V2: Optimize indexes and add cleanup support

-- agent_instance: drop redundant index (UNIQUE constraint already covers instance_id)
ALTER TABLE agent_instance DROP INDEX idx_instance_id;

-- agent_instance: replace single-column status index with composite (status, active)
ALTER TABLE agent_instance DROP INDEX idx_status;
ALTER TABLE agent_instance ADD INDEX idx_status_active (status, active);

-- session_mapping: drop redundant index (UNIQUE constraint already covers session_id)
ALTER TABLE session_mapping DROP INDEX idx_session_id;

-- session_mapping: add composite index for load-balancing queries
ALTER TABLE session_mapping ADD INDEX idx_instance_active (instance_id, active);
