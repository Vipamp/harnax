-- 为现有业务表添加 tenant_id 字段
-- 实现多租户数据隔离

-- 1. sys_user 表添加 tenant_id（主要租户ID）
ALTER TABLE `sys_user`
    ADD COLUMN `tenant_id` BIGINT DEFAULT NULL COMMENT '所属租户ID（主要租户）' AFTER `id`,
    ADD INDEX `idx_tenant_id` (`tenant_id`);

-- 2. agent 表添加 tenant_id
ALTER TABLE `agent`
    ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属租户ID' AFTER `id`,
    ADD INDEX `idx_tenant_id` (`tenant_id`);

-- 3. mcp_server 表添加 tenant_id
ALTER TABLE `mcp_server`
    ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属租户ID' AFTER `id`,
    ADD INDEX `idx_tenant_id` (`tenant_id`);

-- 4. skill 表添加 tenant_id
ALTER TABLE `skill`
    ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属租户ID' AFTER `id`,
    ADD INDEX `idx_tenant_id` (`tenant_id`);

-- 5. model 表添加 tenant_id
ALTER TABLE `model`
    ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属租户ID' AFTER `id`,
    ADD INDEX `idx_tenant_id` (`tenant_id`);

-- 6. channel 表添加 tenant_id
ALTER TABLE `channel`
    ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属租户ID' AFTER `id`,
    ADD INDEX `idx_tenant_id` (`tenant_id`);

-- 7. session 表添加 tenant_id
ALTER TABLE `session`
    ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属租户ID' AFTER `id`,
    ADD INDEX `idx_tenant_id` (`tenant_id`);

-- 8. job 表添加 tenant_id
ALTER TABLE `job`
    ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属租户ID' AFTER `id`,
    ADD INDEX `idx_tenant_id` (`tenant_id`);

-- 说明：
-- 1. sys_user.tenant_id 允许NULL，因为用户可能属于多个租户（通过user_tenant表）
-- 2. 其他业务表的tenant_id默认为1（默认租户），保证现有数据可用
-- 3. 所有tenant_id字段都添加了索引，优化查询性能
-- 4. 后续可以通过MyBatis拦截器自动添加WHERE tenant_id条件
