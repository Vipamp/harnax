-- ==========================================
-- 重新初始化 admin 管理员用户
-- ==========================================
-- 重要说明：
-- 由于密码采用了前端 SHA-256 + 后端 BCrypt 的双重加密策略，
-- 不建议直接使用 SQL 插入用户，因为很难手动计算正确的密码哈希。
--
-- 推荐方式：
-- 1. 启动后端应用，会自动创建 admin 用户（通过 AdminUserInitializer）
-- 2. 或者通过前端创建用户接口创建 admin 用户
--
-- 默认管理员账号：
-- 用户名：admin
-- 密码：admin123
-- ==========================================

USE `harnax`;

-- 如果需要重置 admin 用户，可以执行以下删除操作
-- 然后重启应用，会自动重新创建
DELETE FROM `sys_user` WHERE `username` = 'admin';

-- 插入 admin 用户（密码：admin123）
-- 加密流程：前端 SHA-256("admin123") → 后端 BCrypt(SHA-256哈希)
-- SHA-256("admin123") = 240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9
-- BCrypt 哈希 = $2a$10$esqm4yYiXlpoCQsUOcjGIubYyUU0irYEcLJpCQBpkAtP/Pmm6XphS
INSERT INTO `sys_user` (
    `username`, 
    `password`, 
    `nickname`, 
    `email`, 
    `phone`, 
    `gender`, 
    `avatar`, 
    `status`, 
    `is_admin`, 
    `active`,
    `last_login_time`,
    `create_time`, 
    `update_time`
) VALUES (
    'admin',
    '$2a$10$esqm4yYiXlpoCQsUOcjGIubYyUU0irYEcLJpCQBpkAtP/Pmm6XphS',
    '系统管理员',
    'admin@harnax.com',
    '13800138000',
    1,
    '',
    1,
    1,
    1,
    NULL,
    NOW(),
    NOW()
);

-- 查看当前用户列表
SELECT 
    id,
    username,
    nickname,
    email,
    is_admin,
    status,
    active,
    create_time
FROM `sys_user` 
ORDER BY id;
