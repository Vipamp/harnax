-- Token 黑名单表
-- 用于存储用户主动退出的 JWT Token，在 Token 有效期内拒绝访问

CREATE TABLE IF NOT EXISTS `sys_token_blacklist` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `token` VARCHAR(512) NOT NULL COMMENT 'JWT Token（完整字符串）',
  `token_hash` VARCHAR(64) NOT NULL COMMENT 'Token 的 SHA256 哈希值（用于快速查询）',
  `username` VARCHAR(50) DEFAULT NULL COMMENT '用户名',
  `user_id` BIGINT(20) DEFAULT NULL COMMENT '用户 ID',
  `reason` VARCHAR(50) DEFAULT 'logout' COMMENT '加入黑名单原因：logout-退出登录，revoke-撤销，ban-禁用',
  `expire_time` DATETIME NOT NULL COMMENT 'Token 过期时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_ip` VARCHAR(50) DEFAULT NULL COMMENT '操作 IP',
  
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token_hash` (`token_hash`),
  KEY `idx_expire_time` (`expire_time`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token 黑名单表';

-- 可选：添加索引提升查询性能
-- CREATE INDEX idx_token_lookup ON sys_token_blacklist(token_hash, expire_time);
