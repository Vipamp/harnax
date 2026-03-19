-- 为 sys_user 表添加 is_admin 字段
ALTER TABLE `sys_user` ADD COLUMN `is_admin` TINYINT(2) DEFAULT 0 COMMENT '是否是管理员（0:否，1:是）' AFTER `status`;

-- 更新第一个用户为管理员（通常是 admin 用户）
UPDATE `sys_user` SET `is_admin` = 1 WHERE `username` = 'admin';
