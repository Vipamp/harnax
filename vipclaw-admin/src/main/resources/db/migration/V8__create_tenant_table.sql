-- 租户表
DROP TABLE IF EXISTS `tenant`;
CREATE TABLE `tenant`
(
    `id`          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '租户ID',
    `name`        VARCHAR(100) NOT NULL COMMENT '租户名称',
    `status`      TINYINT(1)   DEFAULT 1 COMMENT '状态（0:禁用 1:启用）',
    `creator`     VARCHAR(100) NOT NULL COMMENT '创建人',
    `active`      TINYINT(1)   DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';
