-- 检查 admin 用户的 isAdmin 字段值
-- 执行日期: 2026-04-21

USE `vipclaw`;

-- 1. 查看 admin 用户的完整信息
SELECT 
    id, 
    username, 
    nickname, 
    is_admin, 
    status,
    active,
    create_time
FROM sys_user 
WHERE username = 'admin';

-- 2. 查看所有用户的 isAdmin 状态
SELECT 
    id, 
    username, 
    nickname, 
    is_admin, 
    status
FROM sys_user 
WHERE active = 1
ORDER BY id;

-- 3. 如果 admin 用户的 is_admin 不是 1,执行以下语句修复:
-- UPDATE sys_user SET is_admin = 1 WHERE username = 'admin';

-- 4. 确认修复结果
-- SELECT username, is_admin FROM sys_user WHERE username = 'admin';
