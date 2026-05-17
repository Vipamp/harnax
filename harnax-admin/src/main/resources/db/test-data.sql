-- 用户表测试数据
USE `harnax`;

-- 插入测试用户（密码为明文，实际项目中应该加密）
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `email`, `phone`, `gender`, `avatar`, `status`) VALUES
('admin', 'admin123', '超级管理员', 'admin@example.com', '13800138000', 1, 'https://example.com/avatar/admin.jpg', 1),
('zhangsan', 'zhang123456', '张三', 'zhangsan@example.com', '13900139000', 1, 'https://example.com/avatar/zhangsan.jpg', 1),
('lisi', 'li123456', '李四', 'lisi@example.com', '13700137000', 0, 'https://example.com/avatar/lisi.jpg', 1),
('wangwu', 'wang123456', '王五', 'wangwu@example.com', '13600136000', 1, NULL, 1),
('test', 'test123456', '测试用户', 'test@example.com', '13500135000', 2, NULL, 0);

-- 注意：登录时使用用户名和密码进行验证
-- 测试账号：admin / admin123
