# Harnax 数据库设计规范

本文档基于 `harnax-admin` 的 Flyway 迁移脚本（`db/migration/V1 ~ V14`）与 [数据库迁移说明](../harnax-admin/src/main/resources/db/migration/README.md) 整理，适用于 Harnax 平台所有 MySQL 数据库设计。

**文档版本**: v1.0
**适用范围**: `harnax-admin` 主库及相关服务库
**相关文档**: [后端代码规范](./backend-code-conventions.md)

## 一、基础约定

| 项目 | 要求 |
|------|------|
| 数据库 | MySQL 8.0 |
| 存储引擎 | 统一 `InnoDB`（支持事务、行级锁） |
| 字符集 | `utf8mb4`（支持 emoji 与特殊字符） |
| 排序规则 | `utf8mb4_0900_ai_ci`（MySQL 8.0 默认，不区分大小写） |
| 外键 | **不使用物理外键**，关联关系由应用层校验，用普通索引 + 字段注释表达 |
| 删除策略 | 一律逻辑删除（`active = 0`），禁止物理删除 |
| 表注释 | 每张表必须有英文 `COMMENT` |
| 字段注释 | 每个字段必须有英文 `COMMENT`，枚举值含义写在注释里，格式 `(0: X, 1: Y)` |

## 二、命名规范

### 2.1 表命名

- 全小写 + 下划线（snake_case），使用单数业务名词：`agent`、`mcp_server`、`skill_repository`
- 系统表使用 `sys_` 前缀：`sys_user`、`sys_token_blacklist`
- 移动端表使用 `mp_` 前缀：`mp_session`、`mp_chat_message`
- 关联表使用 `A_B_binding` / `user_tenant` 风格：`agent_tool_binding`、`agent_mcp_binding`
- 日志/统计表使用 `_log` / `_stats` 后缀：`process_log`、`tool_call_log`、`token_stats`

### 2.2 字段命名

- 全小写 + 下划线（snake_case），业务含义明确
- 布尔/标志字段使用 `is_` 前缀或语义化名称：`is_public`、`is_admin`、`enable_think`
- 时间字段使用 `_time` 后缀：`create_time`、`update_time`、`last_login_time`
- 外键引用字段使用 `{关联表}_id`：`agent_id`、`provider_id`、`tenant_id`
- JSON 配置字段按内容命名：`mcp_list`、`config_json`、`env_bindings`

### 2.3 索引命名

| 类型 | 前缀 | 示例 |
|------|------|------|
| 唯一索引 | `uk_` | `uk_callback_key`、`uk_tenant_name`、`uk_user_permanent` |
| 普通索引 | `idx_` | `idx_tenant_id`、`idx_agent_id`、`idx_create_time` |
| 组合索引 | `idx_字段1_字段2`（按选择性/查询顺序排列） | `idx_type_enabled_status_active`、`idx_token_lookup` |

## 三、通用字段规范

### 3.1 业务表必备字段

所有可管理的业务表必须包含以下通用字段：

| 字段 | 类型 | 约束 | 默认值 | 说明 |
|------|------|------|--------|------|
| `id` | BIGINT | PRIMARY KEY, AUTO_INCREMENT | - | 主键 |
| `tenant_id` | BIGINT | NOT NULL | `1` | 所属租户 ID（多租户隔离，见 3.3） |
| `status` | TINYINT(1) | NOT NULL | `1` | 状态（0: Disabled, 1: Enabled） |
| `is_public` | TINYINT(1) | NOT NULL | `1` 或 `0` | 数据可见性（0: Private, 1: Public），需要数据权限的表必加 |
| `creator` | VARCHAR(100) | - | NULL | 创建人用户名（数据权限过滤依据） |
| `active` | TINYINT(1) | NOT NULL | `1` | 逻辑删除标记（0: Deleted, 1: Active） |
| `create_time` | DATETIME | NOT NULL | `CURRENT_TIMESTAMP` | 创建时间 |
| `update_time` | DATETIME | NOT NULL | `CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` | 更新时间 |

```sql
`id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'MCP Server ID',
`tenant_id`   bigint       NOT NULL DEFAULT '1'    COMMENT 'Tenant ID',
`status`      tinyint(1)            DEFAULT '1'    COMMENT 'Status (0: Disabled, 1: Enabled)',
`is_public`   tinyint(1)            DEFAULT '1'    COMMENT 'Public visibility (0: Private, 1: Public)',
`creator`     varchar(100)          DEFAULT NULL   COMMENT 'Creator',
`active`      tinyint(1)            DEFAULT '1'    COMMENT 'Active status (0: Deleted, 1: Active)',
`create_time` datetime              DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
`update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
```

### 3.2 日志/流水表的精简字段

只增不改的日志、统计表（`process_log`、`tool_call_log`、`token_stats`、`agent_task_log`）可省略 `status`、`active`、`update_time`，但必须保留：

- `id` 自增主键
- 关联检索字段（`agent_id`、`session_id` 等）并建索引
- 时间字段（`ts` 或 `create_time`）

### 3.3 租户字段与拦截器

- 业务表 `tenant_id` 必须 `NOT NULL DEFAULT 1`，由 `MybatisTenantInterceptor` 在 INSERT 时自动填充、在 SELECT/UPDATE/DELETE 时自动过滤
- 系统级表（`tenant`、`user_tenant`、`sys_user`、`sys_token_blacklist` 等）不需要租户过滤，需加入拦截器 `EXCLUDED_TABLES` 排除列表
- 新表如需豁免租户过滤，必须同步修改排除列表并注释原因

### 3.4 唯一约束与逻辑删除的配合

带逻辑删除的唯一字段，唯一键应把 `active` 纳入，避免已删除记录阻塞新建：

```sql
UNIQUE KEY `uk_tenant_name` (`tenant_id`, `name`, `active`),
UNIQUE KEY `uk_tenant_key_active` (`tenant_id`, `env_key`, `active`)
```

## 四、数据类型规范

| 场景 | 类型 | 说明 |
|------|------|------|
| 主键 / 外键引用 | `BIGINT` | 自增主键统一 `BIGINT AUTO_INCREMENT` |
| 状态 / 标志位 | `TINYINT(1)` | 取值 0/1，注释写明含义 |
| 短字符串（名称、类型、角色） | `VARCHAR(20~200)` | 按实际业务上限设置 |
| 长字符串（URL、命令、Key） | `VARCHAR(500)` | 如 `url`、`command`、`api_key` |
| 大文本（描述、提示词、JSON） | `TEXT` | `description`、`system_prompt`、各类 JSON 列表 |
| 超大文本（消息内容） | `MEDIUMTEXT` | 如 `mp_chat_message.content` |
| 金额 / 价格 | `DECIMAL(10, 4)` | 禁止使用浮点类型 |
| 时间 | `DATETIME` | 统一使用 `DATETIME`，不使用 `TIMESTAMP` |
| 计数 | `BIGINT` | token 计数等用 `BIGINT DEFAULT '0'` |

**其他要求**：

- NOT NULL 字段必须有默认值或明确的写入路径；可空字段注释中说明用途
- 枚举值用 `VARCHAR`（如 `type`、`source_type`、`permission_mode`）或 `TINYINT`（如 `status`），取值含义全部写进注释
- 布尔语义字段禁止使用 `CHAR(1)`/字符串，统一 `TINYINT(1)`

## 五、JSON 字段存储

列表型/配置型数据使用 JSON 字符串存储于 `TEXT` 字段，字段注释必须给出 JSON 结构示例：

```sql
`headers`    text DEFAULT NULL COMMENT 'HTTP headers JSON: [{"key":"Authorization","value":"Bearer xxx","secret":true}]',
`envs`       text DEFAULT NULL COMMENT 'Env vars JSON: [{"key":"API_KEY","value":"sk-xxx","secret":true}]',
`mcp_list`   text COMMENT 'MCP list (JSON format)',
`subtasks`   text COMMENT 'Subtasks list (JSON format)',
```

**使用原则**：

- 不需要按元素检索、整体读写的配置 → JSON 字段
- 需要按元素关联、检索、级联维护的关系 → 拆独立关联表（见第六节绑定表模式）
- 含敏感值的 JSON 条目使用 `secret: true` 标记，落库前由 `SecretFieldEncryptor` 加密

## 六、关联表（绑定表）设计

多对多关系拆独立绑定表（参考 `agent_tool_binding`、`agent_mcp_binding`、`agent_skill_binding`）：

```sql
CREATE TABLE IF NOT EXISTS agent_tool_binding (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id     BIGINT NOT NULL,
    tool_id      BIGINT NOT NULL,
    need_confirm TINYINT    DEFAULT 0,
    env_bindings TEXT       DEFAULT NULL COMMENT 'JSON array of env binding snapshots',
    create_time  DATETIME   DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_tool_binding_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**要求**：

- 主表引用字段必须 `NOT NULL` 并建索引（索引名含表名前缀，避免跨表重名）
- 绑定属性（开关、快照、个性化配置）放在绑定表上，不污染主表
- 两端主键之外如需唯一约束，建组合唯一键

## 七、索引设计规范

**必须建索引**：

- 所有 `tenant_id` 字段
- 所有外键引用字段（`agent_id`、`provider_id`、`task_id` 等）
- 列表页常用筛选字段（`status`、`enabled`、`creator`、时间字段）
- 唯一业务字段（`uk_` 唯一键）

**示例**：

```sql
KEY `idx_tenant_id` (`tenant_id`),
KEY `idx_agent_id` (`agent_id`),
KEY `idx_create_time` (`create_time`),
KEY `idx_type_enabled_status_active` (`type`, `enabled`, `status`, `active`),
UNIQUE KEY `uk_callback_key` (`callback_key`)
```

**注意事项**：

- 组合索引字段顺序遵循最左前缀原则，等值条件在前、范围/排序字段在后
- 单表索引数量建议不超过 5 个，避免写入放大
- 模糊查询 `LIKE '%keyword%'` 不走索引，大表搜索需另行方案

## 八、建表语句模板

```sql
CREATE TABLE IF NOT EXISTS `example` (
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `tenant_id`   bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`        varchar(100) NOT NULL COMMENT 'Name',
    `description` text COMMENT 'Description',
    `type`        varchar(20)  NOT NULL COMMENT 'Type (typeA/typeB)',
    `status`      tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`   tinyint(1) DEFAULT '1' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`     varchar(100)          DEFAULT NULL COMMENT 'Creator',
    `active`      tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time` datetime              DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_name` (`tenant_id`, `name`, `active`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Example table';
```

## 九、Flyway 迁移规范

### 9.1 目录与命名

- 所有脚本位于 `harnax-admin/src/main/resources/db/migration/`
- 命名格式：`V{version}__{description}.sql`，全小写短横线描述
  - 结构变更：`V15__add_example_table.sql`
  - 数据初始化：`V11__seed_harnax_cli_plugin.sql`（`seed` 前缀）
- 版本号单调递增，**禁止跳号复用、禁止修改已执行脚本、禁止删除历史脚本**

### 9.2 脚本编写规则

- 建表一律 `CREATE TABLE IF NOT EXISTS`
- 结构变更使用 `ALTER TABLE ADD COLUMN ... AFTER ...`，**禁止 DROP + CREATE 重建表**
- 一个脚本只做一类变更，文件头部注释说明变更目的
- 初始化数据使用 `INSERT IGNORE`，保证幂等可重放
- 逻辑删除优先于删除数据；确需清理数据须单独脚本并评审

```sql
-- V3: Add i18n support columns
ALTER TABLE `agent_tool`
    ADD COLUMN `display_name_zh` varchar(200) DEFAULT NULL COMMENT 'Display name (Chinese, for i18n zh-CN locale)'
    AFTER `display_name`;
```

### 9.3 配置与验证

`application.yml` 关键配置：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
    validate-on-migrate: true
    clean-disabled: true
```

- 部署后通过 `flyway_schema_history` 表确认版本
- 社区版不支持自动回滚：回滚需新写反向迁移脚本
- 生产执行迁移前必须备份；先在数据副本上验证

## 十、测试库规范（schema-test.sql）

Mapper 集成测试（Testcontainers）使用 `harnax-entity/src/test/resources/schema-test.sql` 初始化：

- 包含被测表的完整结构（与生产表结构保持一致）
- 每表预置 3~5 条数据，覆盖三类场景：
  - 正常数据（`active = 1, status = 1`）
  - 已删除数据（`active = 0`，验证逻辑删除过滤）
  - 已禁用数据（`status = 0`，验证状态筛选）
- 生产表结构变更（新增迁移脚本）时，必须同步更新 `schema-test.sql`

## 十一、敏感数据存储

| 数据 | 存储方式 |
|------|----------|
| 用户密码 | BCrypt 哈希（`varchar(100)`），禁止明文 |
| API Key | SHA-256 哈希（`key_hash varchar(64)`，唯一）+ 展示前缀（`key_prefix`）；原始 Key 仅创建时返回一次 |
| 需回显的密钥（如 Provider api_key） | AES 加密存储（`varchar(500)`） |
| JSON 中的敏感条目 | `secret: true` 标记 + `SecretFieldEncryptor` 加密落库 |
| Token 黑名单 | 存储 SHA-256 哈希（`token_hash`）+ 过期时间，配合组合索引 `idx_token_lookup (token_hash, expire_time)` |

## 十二、设计检查清单

新增/修改表前逐项确认：

**结构**：

- [ ] 表名、字段名符合 snake_case 命名，表有英文注释
- [ ] 每个字段都有英文 COMMENT，枚举值含义齐全
- [ ] 业务表包含通用字段（`id`、`tenant_id`、`status`、`active`、`create_time`、`update_time`）
- [ ] 需要数据权限的表包含 `is_public` + `creator`
- [ ] 唯一键考虑了逻辑删除（纳入 `active`）

**索引**：

- [ ] `tenant_id`、外键引用字段、常用筛选字段均有索引
- [ ] 索引命名 `uk_` / `idx_` 规范

**迁移**：

- [ ] 新增 Flyway 脚本，版本号递增，命名清晰
- [ ] `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE`，无破坏性操作
- [ ] 种子数据 `INSERT IGNORE` 幂等
- [ ] 同步更新 `schema-test.sql` 并补充 Mapper 集成测试

---

**文档版本**: v1.0
**整理依据**: `db/migration/V1__init_schema.sql` ~ `V14`、`db/migration/README.md`、`openspec/vipclaw-admin-rules.md`（v2.1）
**维护者**: Harnax 团队
