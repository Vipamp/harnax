-- Add is_required column to agent_tool table for mandatory/optional tool classification
ALTER TABLE agent_tool ADD COLUMN is_required TINYINT NOT NULL DEFAULT 0 COMMENT 'Is mandatory tool (0: optional, 1: required)' AFTER need_confirm;
