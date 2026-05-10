-- 为 skill_repository 和 model_provider 表添加 tenant_id 字段
-- 执行时间: 2026-05-10

-- 1. 为 skill_repository 表添加 tenant_id 字段
ALTER TABLE `skill_repository` 
ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属租户ID' AFTER `id`;

-- 2. 为 skill_repository 表的 tenant_id 添加索引
ALTER TABLE `skill_repository` 
ADD INDEX `idx_tenant_id` (`tenant_id`);

-- 3. 为 model_provider 表添加 tenant_id 字段
ALTER TABLE `model_provider` 
ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属租户ID' AFTER `id`;

-- 4. 为 model_provider 表的 tenant_id 添加索引
ALTER TABLE `model_provider` 
ADD INDEX `idx_tenant_id` (`tenant_id`);

-- 注意：
-- 1. 默认值为 1，表示归属于默认租户
-- 2. 如果已有数据，需要根据实际情况更新 tenant_id
-- 3. 添加索引以提高基于租户的查询性能
