-- 创建数据库
CREATE DATABASE IF NOT EXISTS `vipclaw` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `vipclaw`;

-- 用户表
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(100) NOT NULL COMMENT '密码',
    `nickname` VARCHAR(50) DEFAULT NULL COMMENT '昵称',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `gender` TINYINT(2) DEFAULT 2 COMMENT '性别 (0:女 1:男 2:未知)',
    `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像 URL',
    `status` TINYINT(2) DEFAULT 1 COMMENT '状态 (0:禁用 1:使用)',
    `active` TINYINT(2) DEFAULT 1 COMMENT '状态 (0:已删除 1:未删除)',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- MCP 服务表
DROP TABLE IF EXISTS `mcp_server`;
CREATE TABLE `mcp_server`
(
    `id`          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'MCP ID',
    `name`        VARCHAR(100) NOT NULL COMMENT 'MCP 名称',
    `description` TEXT         DEFAULT NULL COMMENT 'MCP 描述',
    `type`        VARCHAR(20)  NOT NULL COMMENT 'MCP 类型（stdio/sse/streamablehttp）',
    `command`     VARCHAR(500) DEFAULT NULL COMMENT '执行命令（仅 stdio 类型生效）',
    `url`         VARCHAR(500) DEFAULT NULL COMMENT '服务地址（sse/streamablehttp 类型生效）',
    `status`      TINYINT(1)   DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active`      TINYINT(1)   DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 服务表';
