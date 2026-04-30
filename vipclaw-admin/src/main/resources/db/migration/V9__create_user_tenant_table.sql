-- 用户-租户关联表
DROP TABLE IF EXISTS `user_tenant`;
CREATE TABLE `user_tenant`
(
    `id`        BIGINT(20)  NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `user_id`   BIGINT(20)  NOT NULL COMMENT '用户ID',
    `tenant_id` BIGINT(20)  NOT NULL COMMENT '租户ID',
    `role`      VARCHAR(50) DEFAULT 'member' COMMENT '角色（admin/member）',
    `status`    TINYINT(1)  DEFAULT 1 COMMENT '状态（0:禁用 1:启用）',
    `joined_at` DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_tenant` (`user_id`, `tenant_id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-租户关联表';
