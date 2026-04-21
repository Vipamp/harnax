-- 用户表结构升级脚本
-- 从旧版本升级到新版本,添加缺失字段和索引

USE `vipclaw`;

-- 添加 is_admin 字段(如果不存在)
ALTER TABLE `sys_user` 
ADD COLUMN IF NOT EXISTS `is_admin` TINYINT(2) DEFAULT 0 COMMENT '是否是管理员(0:否,1:是)' AFTER `status`;

-- 添加 last_login_time 字段(如果不存在)
ALTER TABLE `sys_user` 
ADD COLUMN IF NOT EXISTS `last_login_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次登陆时间' AFTER `active`;

-- 修改 nickname 为 NOT NULL
ALTER TABLE `sys_user` 
MODIFY COLUMN `nickname` VARCHAR(50) NOT NULL COMMENT '昵称';

-- 修改 email 为 NOT NULL
ALTER TABLE `sys_user` 
MODIFY COLUMN `email` VARCHAR(100) NOT NULL COMMENT '邮箱';

-- 修改 phone 为 NOT NULL
ALTER TABLE `sys_user` 
MODIFY COLUMN `phone` VARCHAR(20) NOT NULL COMMENT '手机号';

-- 修改 avatar 默认值
ALTER TABLE `sys_user` 
MODIFY COLUMN `avatar` VARCHAR(255) DEFAULT '' COMMENT '头像 URL';

-- 添加 email 唯一索引(如果不存在)
ALTER TABLE `sys_user` 
ADD UNIQUE KEY IF NOT EXISTS `uk_email` (`email`);

-- 添加 phone 唯一索引(如果不存在)
ALTER TABLE `sys_user` 
ADD UNIQUE KEY IF NOT EXISTS `uk_phone` (`phone`);

-- 更新现有数据的默认值
UPDATE `sys_user` SET `nickname` = '' WHERE `nickname` IS NULL;
UPDATE `sys_user` SET `email` = '' WHERE `email` IS NULL;
UPDATE `sys_user` SET `phone` = '' WHERE `phone` IS NULL;
UPDATE `sys_user` SET `avatar` = '' WHERE `avatar` IS NULL;
UPDATE `sys_user` SET `is_admin` = 0 WHERE `is_admin` IS NULL;
UPDATE `sys_user` SET `last_login_time` = CURRENT_TIMESTAMP WHERE `last_login_time` IS NULL;
