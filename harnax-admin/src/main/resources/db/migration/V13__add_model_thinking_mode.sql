-- Add thinking_mode column to model table.
-- 0 = not supported, 1 = optional (can toggle), 2 = required (always on).
-- Existing models with support_reasoning = 1 default to 1 (optional);
-- models that require thinking (e.g. qwen3.7-max-2026-05-17) must be
-- switched to 2 manually in the model management page.
ALTER TABLE model
    ADD COLUMN thinking_mode TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Thinking mode (0:not supported, 1:optional, 2:required)' AFTER support_reasoning;

UPDATE model SET thinking_mode = 1 WHERE support_reasoning = 1;
