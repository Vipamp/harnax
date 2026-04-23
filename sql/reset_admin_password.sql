-- 重置 admin 用户密码
-- 问题：初始化的 admin 用户密码只进行了 BCrypt 加密，没有先进行 SHA-256 加密
-- 解决：重新设置密码为 BCrypt(SHA-256('admin123'))

-- SHA-256('admin123') = '240be518fabd2724ddb6f04eeb1da4275713e874d3f3b588e22f9c8f8a6f6b'
-- BCrypt('240be518fabd2724ddb6f04eeb1da4275713e874d3f3b588e22f9c8f8a6f6') 需要后端生成

-- 方法1：通过后端重新初始化（推荐）
-- 1. 删除现有的 admin 用户
DELETE FROM sys_user WHERE username = 'admin';

-- 2. 重启应用，AdminUserInitializer 会自动创建正确的 admin 用户
-- 新的 admin 用户密码将是 BCrypt(SHA-256('admin123'))

-- 方法2：手动更新密码（需要先从后端获取 BCrypt 哈希值）
-- UPDATE sys_user SET password = '$2a$10$...' WHERE username = 'admin';

-- 验证查询
SELECT id, username, LEFT(password, 20) as password_prefix, nickname, status, active 
FROM sys_user 
WHERE username = 'admin';
