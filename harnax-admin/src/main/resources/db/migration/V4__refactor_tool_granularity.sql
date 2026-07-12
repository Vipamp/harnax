-- V4: Refactor tool granularity to per-method + rename env terminology to env_param

-- ============================================
-- 1. Rename agent_tool_env → agent_tool_env_param
-- ============================================
RENAME TABLE `agent_tool_env` TO `agent_tool_env_param`;

-- Rename column env_name → env_param_name
ALTER TABLE `agent_tool_env_param`
    CHANGE COLUMN `env_name` `env_param_name` varchar(200) NOT NULL COMMENT 'Environment parameter name';

-- Drop and recreate unique key with new column name
ALTER TABLE `agent_tool_env_param`
    DROP INDEX `uk_tool_env_name`,
    ADD UNIQUE KEY `uk_tool_env_param_name` (`tool_id`, `env_param_name`);

-- ============================================
-- 2. Add method_name column to agent_tool
-- ============================================
ALTER TABLE `agent_tool`
    ADD COLUMN `method_name` varchar(100) DEFAULT NULL COMMENT 'Java method name (one record per @Tool method)'
    AFTER `bean_name`;

-- ============================================
-- 3. Rename env columns in agent_tool
-- ============================================
ALTER TABLE `agent_tool`
    CHANGE COLUMN `envs` `env_params` text COMMENT 'Environment parameters configuration JSON';

ALTER TABLE `agent_tool`
    CHANGE COLUMN `required_env_keys` `required_env_param_keys` varchar(1000) DEFAULT NULL COMMENT 'Required environment parameter keys, JSON array';

-- ============================================
-- 4. Update unique key: from (tenant_id, name, active) to (tenant_id, bean_name, method_name, active)
-- ============================================
ALTER TABLE `agent_tool`
    DROP INDEX `uk_tenant_name`,
    ADD UNIQUE KEY `uk_tenant_bean_method` (`tenant_id`, `bean_name`, `method_name`, `active`);

-- ============================================
-- 5. Rename mcp_server.envs → env_params
-- ============================================
ALTER TABLE `mcp_server`
    CHANGE COLUMN `envs` `env_params` text DEFAULT NULL COMMENT 'Environment parameters JSON';
