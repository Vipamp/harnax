-- 初始化默认租户数据
-- 创建默认租户：默认组织
INSERT INTO `tenant` (`id`, `name`, `status`, `creator`, `active`, `create_time`, `update_time`)
VALUES (1, '默认组织', 1, 'system', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- 将系统管理员（username='admin'）添加到默认租户，并设置为租户管理员
INSERT INTO `user_tenant` (`user_id`, `tenant_id`, `role`, `status`, `joined_at`)
SELECT u.id, 1, 'admin', 1, NOW()
FROM `sys_user` u
WHERE u.username = 'admin' AND u.is_admin = 1
ON DUPLICATE KEY UPDATE `role` = VALUES(`role`), `status` = VALUES(`status`);
