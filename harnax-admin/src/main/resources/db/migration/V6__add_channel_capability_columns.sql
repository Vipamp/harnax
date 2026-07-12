-- Add per-session capability toggle columns to channel table
ALTER TABLE channel ADD COLUMN enable_think  TINYINT NOT NULL DEFAULT 0 COMMENT 'Enable thinking mode (0:no, 1:yes)';
ALTER TABLE channel ADD COLUMN enable_search TINYINT NOT NULL DEFAULT 0 COMMENT 'Enable web search (0:no, 1:yes)';
ALTER TABLE channel ADD COLUMN enable_plan   TINYINT NOT NULL DEFAULT 0 COMMENT 'Enable plan mode (0:no, 1:yes)';
