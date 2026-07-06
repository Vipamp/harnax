-- Add headers and envs columns to mcp_server table
ALTER TABLE `mcp_server`
  ADD COLUMN `headers` TEXT DEFAULT NULL COMMENT 'HTTP headers JSON: [{"key":"Authorization","value":"Bearer xxx","secret":true}]',
  ADD COLUMN `envs` TEXT DEFAULT NULL COMMENT 'Env vars JSON: [{"key":"API_KEY","value":"sk-xxx","secret":true}]';
