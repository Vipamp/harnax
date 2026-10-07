# Harnax 数据库设计规范

本文档基于 `harnax-admin` 的 schema 基线 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` 与 [数据库迁移说明](../harnax-admin/src/main/resources/db/migration/README.md) 整理，适用于 Harnax 平台所有 MySQL 数据库设计。

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
- 日志/统计表使用 `_log` / `_stats` 后缀：`process_log`、`tool_invocation_log`、`token_stats`、`tool_invocation_stats`

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

只增不改的日志、统计表（`process_log`、`tool_invocation_log`、`token_stats`、`agent_task_log`）可省略 `status`、`active`、`update_time`，但必须保留：

- `id` 自增主键
- 关联检索字段（`agent_id`、`session_id` 等）并建索引
- 时间字段（`ts` 或 `create_time`）

### 3.3 租户字段

- 业务表 `tenant_id` 必须 `NOT NULL DEFAULT 1`：INSERT 由服务层显式赋值（取自 `currentTenantId()`），SELECT/UPDATE/DELETE 的条件写在各条 SQL 里，没有自动填充也没有自动过滤
- 系统级表（`tenant`、`user_tenant`、`sys_user`、`sys_token_blacklist`）与平台级共享表（`agent_tool`）不带租户条件，是否需要写在该表的设计说明里
- 新表默认按业务表处理；确需平台级共享（全平台一份、各租户共用）时，在建表迁移与实体注释里写明该表按平台级资产对待

### 3.4 唯一约束与逻辑删除的配合

**不要**把 `active` 放进唯一键。`UNIQUE KEY (tenant_id, name, active)` 只允许每个键留一条已删行：第一次逻辑删除把 `(tenant, name, 0)` 占住，之后「删了再建、再删」的第二次删除直接撞这个键报 duplicate——`env_variable` 在旧键形态下就是这一形状（删第二个同名变量必然失败）。仓内 `agent`、`mcp_server`、`skill`、`env_variable` 现在都写成生成列（四张表的建表语句都在 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`）：

```sql
`active_name` VARCHAR(100) GENERATED ALWAYS AS (IF(active = 1, name, NULL)) VIRTUAL,
UNIQUE KEY `uk_tenant_active_name` (`tenant_id`, `active_name`)
```

- MySQL 唯一索引忽略 NULL，`active = 0` 的行因此退出该键的覆盖范围，历史删除行可以无限累积
- 存活行的约束强度不变：同租户内 `active = 1` 的行仍然唯一
- 把已删行改回 `active = 1` 会重新撞键，这是预期行为：不允许两行同名复活
- 唯一键左前缀已含 `tenant_id`，原有的 `idx_tenant_id` 一并删除
- 加键前若存量已有重复的存活行，先改名（如 `原名#dup-<id>`）再加键，不删任何行
- 只放宽「已删行」这一侧时不需要回填：旧键已保证存活行唯一，新键只改变删除之后的行为

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

### 9.1 目录与基线

- 每个服务模块只有一份 schema 基线，位于该模块的 `src/main/resources/db/migration/`：

| 模块 | 基线脚本 | 库 | 历史表 |
|------|----------|-----|--------|
| `harnax-admin` | `V1__init_schema.sql` | `harnax_admin` | `flyway_schema_history` |
| `harnax-scheduler` | `V1__init_schema.sql` | `harnax_scheduler` | `flyway_schema_history_scheduler` |
| `harnax-session-router` | `V1__create_session_router_tables.sql` | `harnax_router` | `flyway_schema_history`（仅 cluster profile；`local` 模式走 `db/sqlite-init.sql`） |

- 基线给出该模块 schema 的**最终形态**：每张表的建表语句带最终的列、索引、唯一键与注释，其后是系统启动所需的初始化数据。判「这张表现在长什么样」只看这一个文件，该模块的 `db/migration/` 目录下没有需要往上叠加的后续版本。

### 9.2 变更落法

变更直接写进所属模块的那一份 init 基线：把列、索引、唯一键、初数据改到该文件里对应的 `CREATE TABLE` 与 `INSERT` 上，然后重建库。`harnax-deploy` 环境一律按这条走，schema 历史不向下传。改基线的同时：

- 重新生成 `harnax-entity/src/test/resources/schema-test.sql`——它的 DDL 段取自 admin 基线，不是手工对照；`SchemaBaselineDriftIT` 拿 Flyway 真正建出的库与该文件比对，漂了就红。
- 重跑 `mvn -o -pl harnax-entity -am test` 与 `mvn -o -pl harnax-admin -am -Pintegration-test verify`。

没有「另写一份脚本叠上去」这条路：目录下只有基线一个文件，而已经按旧形态建好的库不认改过的基线——启动即校验和不符，`repair-on-migrate` 也不是解法。所以**改表结构与重建库是同一个动作**，不能拆开。

基线本身的编写规则：

- 建表一律 `CREATE TABLE IF NOT EXISTS`，唯一的既有例外是 `harnax-scheduler` 基线里那 11 张 `QRTZ_*`，它们沿用 Quartz 官方脚本的裸 `CREATE TABLE`
- 列写成最终形态：类型、`DEFAULT`、`COMMENT`、列顺序都直接落在建表语句里，基线内不留 `ALTER` 增量语句，也**禁止 DROP + CREATE 重造整张表**
- 一张表一段建表语句；初始化数据用 `INSERT IGNORE`，保证幂等可重放
- 逻辑删除优先于删除数据；确需清理数据须单独评审
- 基线里不写数据修复语句（回填、改名、归属重判）：新库没有待修的行，写了也没有对象

### 9.3 配置与验证

`harnax-admin/src/main/resources/application.yml`：

```yaml
spring:
  flyway:
    enabled: ${FLYWAY_ENABLED:true}
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
    validate-on-migrate: true
    repair-on-migrate: true
    clean-disabled: ${FLYWAY_CLEAN_DISABLED:true}
```

- `baseline-on-migrate` + `baseline-version: 0` 是必需的：库由 `harnax-deploy/sql/init-databases.sql` 预建，Flyway 见到的是一张空库，基线版本设 0 才会应用该模块的基线脚本，而不是把库判定为「已迁移」。
- `repair-on-migrate` 只有 `harnax-admin` 开着；`harnax-scheduler` 与 `harnax-session-router` 没有配，历史里出现类路径上已不存在的脚本时直接失败。
- **已按旧形态建过的库不能直接换基线**：它的台账行指向类路径上已不存在的脚本，基线的校验和也不再相符。按策略是删库重建，不是就地修复。
- 部署后通过 `flyway_schema_history` 确认基线已应用；社区版不支持自动回滚，回退形态就是把基线改回去再重建库。
- 生产执行迁移前必须备份；先在数据副本上验证。

## 十、测试库规范（schema-test.sql）

Mapper 集成测试（Testcontainers）使用 `harnax-entity/src/test/resources/schema-test.sql` 初始化：

- DDL 段取自 admin 的 schema 基线（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`），不是另一份手工维护的表结构
- 每表预置 3~5 条数据，覆盖三类场景：
  - 正常数据（`active = 1, status = 1`）
  - 已删除数据（`active = 0`，验证逻辑删除过滤）
  - 已禁用数据（`status = 0`，验证状态筛选）
- 基线变更后重新生成该文件的 DDL 段（生产基线里的初始化 INSERT 不进测试库，测试数据由本文件自己播种）；`SchemaBaselineDriftIT` 会拿 Flyway 建出的真实表与列集合与它做双向差集，漂了就红

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

- [ ] 变更写进所属模块的 init 基线（`V1__init_schema.sql`）并重建库，不在已按旧形态建好的库上就地改基线
- [ ] 建表用 `CREATE TABLE IF NOT EXISTS`，基线内无 `DROP`、无 `ALTER` 增量语句、无数据修复语句
- [ ] 种子数据 `INSERT IGNORE` 幂等；基线内不放数据修复语句
- [ ] 重新生成 `schema-test.sql` 的 DDL 段并补充 Mapper 集成测试

---

**文档版本**: v1.0
**整理依据**: `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`、`harnax-admin/src/main/resources/db/migration/README.md`、`openspec/vipclaw-admin-rules.md`（v2.1）
**维护者**: Harnax 团队
