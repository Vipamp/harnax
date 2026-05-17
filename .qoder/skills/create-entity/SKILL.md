---
name: create-entity
description: 在需要基于实体类开发 CURD 操作时，开发对应的前后端代码，完成对实体的 CURD 操作
---

## 一、项目概览

- **后端**: SpringBoot3 + MyBatis + MySQL
- **前端**: Ant Design Pro + Umi4
- **接口规范**: RESTful API，统一返回 Result 包装类型
- **分页**: MyBatis Plus Page 对象

> harnax-admin 是需要开发的后端代码模块，harnax-webui 是需要开发的前端代码模块，其他模块不要动

## 二、开发流程

### 2.1 分析实体的 schema 信息

- 分析实体的 schema 信息中，确定实体的字段和字段类型，同时必须包含如下字段和类型，如果未提供自动补充进去

```text
- `id`：主键，自增，long 类型
- `status`：是否启用（0:禁用，1:启用），tinyint 类型
- `active`：是否可用（0:被删除，1:可用），tinyint 类型
- `create_time`：创建时间，datetime 类型
- `update_time`：更新时间，datetime 类型
```

- 根据最终的 schema 信息，生成 mysql 表的建表语句，格式如下

```sql
DROP TABLE IF EXISTS `<实体名称>`;
CREATE TABLE `实体名称`
(
    `id`          BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    -- 其他业务字段
    -- 必须字段
    `status`      TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active`      TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实体名称';
```

-- 示例

```sql
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`
(
    `id`          BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码',
    `nickname`    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `email`       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `gender`      TINYINT(2) DEFAULT 2 COMMENT '性别 (0:女 1:男 2:未知)',
    `avatar`      VARCHAR(255) DEFAULT NULL COMMENT '头像 URL',
    `status`      TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active`      TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
```

### 2.2 定义接口规范

* 分页查询接口
    * url 格式：`/<实体名称>/page`
    * 请求方式：GET
    * 请求参数：
        * keyword：string 模糊查询字段
        * status：状态筛选字段
        * pageNum：int 页码
        * pageSize：int 每页大小
    * 响应结果：
        * code：int 状态码
        * message：string 提示信息
        * data：object 数据对象
            * total：int 总数
            * pageSize：int 每页大小
            * pages：int 总页数
            * pageNum：int 当前页码
            * records：array 数据列表

* 获取单个实体的接口
    * url 格式：`/<实体名称>/{id}`
    * 请求方式：GET
    * 请求参数：
        * id：long 主键
    * 响应结果：
        * code：int 状态码
        * message：string 提示信息
        * data：实体的Response对象
        * timestamp：long 时间戳

* 创建实体
    * url 格式：`/<实体名称>`
    * 请求方式：POST
    * 请求参数：
        * 实体的Request对象
    * 响应结果：
        * code：int 状态码
        * message：string 提示信息
        * data：boolean 创建成功或者失败
        * timestamp：long 时间戳

* 更新实体
    * url 格式：`/<实体名称>/{id}`
    * 请求方式：PUT
    * 请求参数：
        * id：long 主键
        * 实体的Update对象
    * 响应结果：
        * code：int 状态码
        * message：string 提示信息
        * data：boolean 更新成功或者失败
        * timestamp：long 时间戳

* 更新实体启用状态
    * url 格式：`/toggle/<实体名称>/{id}`
    * 请求方式：PUT
    * 请求参数：
        * id：long 主键
        * status：int 启用状态
    * 响应结果：
        * code：int 状态码
        * message：string 提示信息
        * data：boolean 更新成功或者失败
        * timestamp：long 时间戳

* 删除实体
    * url 格式：`/{id}`
    * 请求方式：DELETE
    * 请求参数：
        * id：long 主键
    * 响应结果：
        * code：int 状态码
        * message：string 提示信息
        * data：boolean 删除成功或者失败
        * timestamp：long 时间戳

### 2.3 编写后端接口服务的代码

* 具体参考：[后端代码开发文档](./references/backend.md)

### 2.4 编写前端页面的代码

* 具体参考：[前端代码开发文档](./references/frontend.md)
