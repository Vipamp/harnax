-- V3: Add version column for optimistic locking and DRAINING status support

ALTER TABLE session_mapping ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version';

ALTER TABLE agent_instance MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'UP' COMMENT 'Instance status: UP, DOWN, DRAINING';
