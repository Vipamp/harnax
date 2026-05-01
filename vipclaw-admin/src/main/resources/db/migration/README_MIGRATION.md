# 数据库迁移执行指南

## 概述

本文档指导如何执行多租户功能的数据库迁移脚本。

## 迁移脚本列表

按执行顺序排列：

1. **V8__create_tenant_table.sql** - 创建租户表
2. **V9__create_user_tenant_table.sql** - 创建用户-租户关联表
3. **V10__init_default_tenant.sql** - 初始化默认租户数据
4. **V11__add_tenant_id_to_business_tables.sql** - 为业务表添加tenant_id字段

## 执行方式

### 方式1：使用Flyway自动迁移（推荐）

如果项目已配置Flyway，启动应用时会自动执行：

```bash
cd vipclaw-admin
mvn spring-boot:run
```

Flyway会按照版本号顺序自动执行V8-V11迁移脚本。

### 方式2：手动执行SQL

如果未使用Flyway，可以手动执行：

```bash
# 连接到MySQL数据库
mysql -u root -p vipclaw

# 执行迁移脚本
source src/main/resources/db/migration/V8__create_tenant_table.sql
source src/main/resources/db/migration/V9__create_user_tenant_table.sql
source src/main/resources/db/migration/V10__init_default_tenant.sql
source src/main/resources/db/migration/V11__add_tenant_id_to_business_tables.sql
```

## 验证数据完整性

### 1. 验证表结构

```sql
-- 检查tenant表
DESCRIBE tenant;

-- 检查user_tenant表
DESCRIBE user_tenant;

-- 检查业务表的tenant_id字段
DESCRIBE sys_user;
DESCRIBE agent;
DESCRIBE mcp_server;
```

### 2. 验证默认租户数据

```sql
-- 检查默认租户
SELECT * FROM tenant WHERE id = 1;

-- 检查管理员租户关联
SELECT ut.*, u.username 
FROM user_tenant ut
JOIN sys_user u ON ut.user_id = u.id
WHERE ut.tenant_id = 1 AND ut.role = 'admin';
```

### 3. 验证索引

```sql
-- 检查tenant_id索引
SHOW INDEX FROM agent WHERE Key_name = 'idx_tenant_id';
SHOW INDEX FROM mcp_server WHERE Key_name = 'idx_tenant_id';
SHOW INDEX FROM skill WHERE Key_name = 'idx_tenant_id';
SHOW INDEX FROM model WHERE Key_name = 'idx_tenant_id';
SHOW INDEX FROM channel WHERE Key_name = 'idx_tenant_id';
SHOW INDEX FROM session WHERE Key_name = 'idx_tenant_id';
SHOW INDEX FROM job WHERE Key_name = 'idx_tenant_id';
```

## 预期结果

### 表创建
- ✅ tenant表创建成功
- ✅ user_tenant表创建成功
- ✅ 8个业务表添加tenant_id字段

### 数据初始化
- ✅ 默认租户（id=1, name='默认组织'）创建成功
- ✅ 系统管理员关联到默认租户（role='admin'）

### 索引创建
- ✅ 所有tenant_id字段都有idx_tenant_id索引

## 常见问题

### Q1: 迁移脚本执行失败

**A:** 检查：
1. 数据库连接是否正常
2. 是否有足够的权限执行DDL操作
3. 表是否已存在（Flyway会自动处理）

### Q2: 默认租户已存在

**A:** V10脚本使用`ON DUPLICATE KEY UPDATE`，可以安全重复执行。

### Q3: 业务表已有tenant_id字段

**A:** 需要手动检查字段是否已存在，如果存在则跳过该表的ALTER语句。

## 回滚方案

如需回滚，可以执行以下操作：

```sql
-- 删除业务表的tenant_id字段
ALTER TABLE agent DROP COLUMN tenant_id;
ALTER TABLE mcp_server DROP COLUMN tenant_id;
ALTER TABLE skill DROP COLUMN tenant_id;
ALTER TABLE model DROP COLUMN tenant_id;
ALTER TABLE channel DROP COLUMN tenant_id;
ALTER TABLE session DROP COLUMN tenant_id;
ALTER TABLE job DROP COLUMN tenant_id;
ALTER TABLE sys_user DROP COLUMN tenant_id;

-- 删除关联表
DROP TABLE IF EXISTS user_tenant;
DROP TABLE IF EXISTS tenant;
```

**注意：** 回滚会丢失所有租户相关数据，请谨慎操作！

## 后续步骤

迁移完成后：
1. 启动应用验证功能
2. 测试租户创建和管理
3. 验证数据隔离功能
4. 检查日志确认MyBatis拦截器工作正常
