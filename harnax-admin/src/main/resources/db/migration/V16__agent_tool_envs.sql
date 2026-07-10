-- Add environment variables column to agent_tool table
ALTER TABLE `agent_tool` ADD COLUMN `envs` TEXT COMMENT 'Environment variables JSON: [{"key":"API_KEY","value":"sk-xxx","secret":true}]' AFTER `http_headers`;
