-- 为 session 表添加深度思考、联网搜索、开启计划三个字段
ALTER TABLE `session` 
ADD COLUMN `enable_think` TINYINT(1) DEFAULT 0 COMMENT '是否启用深度思考（0:否，1:是）' AFTER `model_id`,
ADD COLUMN `enable_search` TINYINT(1) DEFAULT 0 COMMENT '是否启用联网搜索（0:否，1:是）' AFTER `enable_think`,
ADD COLUMN `enable_plan` TINYINT(1) DEFAULT 0 COMMENT '是否启用计划（0:否，1:是）' AFTER `enable_search`;
