-- 为各表添加 is_public 和 creator 字段

-- 1. 模型供应商表
ALTER TABLE `model_provider` ADD COLUMN `is_public` TINYINT(2) DEFAULT 0 COMMENT '是否公开（0:否，1:是）' AFTER `status`;
ALTER TABLE `model_provider` ADD COLUMN `creator` VARCHAR(100) DEFAULT NULL COMMENT '创建人' AFTER `is_public`;

-- 2. 模型表
ALTER TABLE `model` ADD COLUMN `is_public` TINYINT(2) DEFAULT 0 COMMENT '是否公开（0:否，1:是）' AFTER `status`;
ALTER TABLE `model` ADD COLUMN `creator` VARCHAR(100) DEFAULT NULL COMMENT '创建人' AFTER `is_public`;

-- 3. MCP 服务表
ALTER TABLE `mcp_server` ADD COLUMN `is_public` TINYINT(2) DEFAULT 0 COMMENT '是否公开（0:否，1:是）' AFTER `status`;
ALTER TABLE `mcp_server` ADD COLUMN `creator` VARCHAR(100) DEFAULT NULL COMMENT '创建人' AFTER `is_public`;

-- 4. 技能仓库表
ALTER TABLE `skill_repository` ADD COLUMN `is_public` TINYINT(2) DEFAULT 0 COMMENT '是否公开（0:否，1:是）' AFTER `status`;
ALTER TABLE `skill_repository` ADD COLUMN `creator` VARCHAR(100) DEFAULT NULL COMMENT '创建人' AFTER `is_public`;

-- 5. 技能表
ALTER TABLE `skill` ADD COLUMN `is_public` TINYINT(2) DEFAULT 0 COMMENT '是否公开（0:否，1:是）' AFTER `status`;
ALTER TABLE `skill` ADD COLUMN `creator` VARCHAR(100) DEFAULT NULL COMMENT '创建人' AFTER `is_public`;

-- 6. 智能体表
ALTER TABLE `agent` ADD COLUMN `is_public` TINYINT(2) DEFAULT 0 COMMENT '是否公开（0:否，1:是）' AFTER `status`;
ALTER TABLE `agent` ADD COLUMN `creator` VARCHAR(100) DEFAULT NULL COMMENT '创建人' AFTER `is_public`;

-- 7. 定时任务表
ALTER TABLE `sys_job` ADD COLUMN `is_public` TINYINT(2) DEFAULT 0 COMMENT '是否公开（0:否，1:是）' AFTER `description`;
ALTER TABLE `sys_job` ADD COLUMN `creator` VARCHAR(100) DEFAULT NULL COMMENT '创建人' AFTER `is_public`;

-- 8. 定时任务日志表（只添加 creator，日志不需要 is_public）
ALTER TABLE `sys_job_log` ADD COLUMN `creator` VARCHAR(100) DEFAULT NULL COMMENT '创建人' AFTER `end_time`;
