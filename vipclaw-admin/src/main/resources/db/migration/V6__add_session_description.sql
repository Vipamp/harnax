-- 为 session 表添加会话描述字段
ALTER TABLE `session` ADD COLUMN `session_description` TEXT DEFAULT NULL COMMENT '会话描述' AFTER `title`;
