# Harnax 工具（Tools）集成设计说明

> **定位**：本文按代码现状描述工具（Tools）域：类型划分、两张主表与两张辅表的列与键、注册链路、装配与下发链路、行为判定的归属侧、对外 API 面、环境参数绑定。每条断言给出仓库相对路径与行号锚点。表结构与列集合以 schema 基线 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` 为准——该目录下只有这一份需要应用的迁移，表结构的最终态全在里面。产品口径见 `prod_doc/tool-integration-design.zh-CN.md`（英文 `prod_doc/tool-integration-design.en-US.md`），使用与开发口径见 `prod_doc/tool-capability.zh-CN.md`（英文 `prod_doc/tool-capability.en-US.md`）。

要点：

- 工具只有内置一类，全部是代码实现的 `ToolBox` 方法（第一节）。
- 工具定义落在 `agent_tool`，一行一个 `@Tool` 方法；agent 与工具的关系落在 `agent_tool_binding`，业务列只有 `need_confirm` 与 `env_bindings`（第二节）。
- 行的唯一写入方是 admin 启动期的 `BuiltinToolAutoRegistrar`，它从本进程的 `ToolRegistry` 取声明，只插入与刷新（第三节）。
- 管理员配的授权与取值经内部 API 交付给 agent-service，由装配侧实例化并收敛到方法级（第四节）。
- 确认、危险输入、超时与留痕的判定都发生在装配侧与运行侧（第五节）；对外只有五条只读 GET（第六节）；环境参数的定义与取值分在两张表（第七节）。

## 一、工具的类型现状

工具只有内置一类：一个工具就是一个 `ToolBox` 子类上的一个 `@Tool` 方法，随进程 classpath 发布（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt:13`、`harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt:19`）。接入一个外部系统等于写一个 `ToolBox` 子类，因此 `agent_tool` 里没有 `type`、`is_public`、`http_url`、`http_method`、`http_headers`、`input_schema`、`output_schema` 这类列（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:86-105`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt:22-72`）。

### 1.1 两级标识：工具组与工具

| 层级 | 标识 | 来源 | 落库位置 | 承担什么 |
|------|------|------|----------|----------|
| 工具组 | Spring bean 名 | `@Component("time-tool-box")`（`harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt:18`） | `agent_tool.bean_name`（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:92`） | 共享依赖与实例化单位（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:61-71`） |
| 工具 | `@Tool.name` | 方法注解，缺省回落方法名（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:113`） | `agent_tool.name`（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:88`） | 身份：唯一键、确认、环境参数、按 agent 授权的单位（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:104`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:21-22`） |

一个 `@Tool` 方法对应一行记录（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:65`、`:79`）。`bean_name` 与 `method_name` 只服务于实例化与反射调用，不参与身份（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt:37-41`、`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:92-93`）。

组级还有一层方法外的名字：`ToolBox.name()`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt:28`）。它进 `ToolMetaDescriptor.toolName`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:130-134`），并作为调用日志前缀的一部分（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt:98`），落库的行名仍是方法级的 `@Tool.name`。

### 1.2 现有内置工具

| 工具组 bean | 方法 | `@Tool.name` | 注解锚点 |
|------|------|------|----------|
| `time-tool-box` | `getDate` | `getDate` | `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt:21-23` |
| `time-tool-box` | `getDatetime` | `getDatetime` | `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt:25-27` |
| `email-tool-box` | `sendEmail` | `sendEmail` | `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/EmailToolBox.kt:41-53` |

`getDate` 与 `getDatetime` 声明 `readOnly = true`（`harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt:21`、`:25`），`sendEmail` 声明 `needConfirm = true` 并带五个环境参数定义（`harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/EmailToolBox.kt:45-52`）。

`harnax-tools-buildin` 同时进 admin 与 agent-service 两个进程的 classpath（`harnax-admin/pom.xml:157`、`harnax-agent/harnax-agent-service/pom.xml:54`），两个进程都扫描 `com.agnetix.harnax.tools` 包（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/HarnaxAdminApplication.kt:11-15`、`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/AgentServiceApplication.kt:8-14`）。同一份注解声明因此在两侧各自解析：admin 侧的注册表决定库里写入什么（`harnax-admin/pom.xml:151`），agent 侧的注册表决定运行时能否实例化。harness-core 只依赖契约层（`harnax-agent/harnax-harness-core/pom.xml:45`）。

### 1.3 按是否可关分

`@ToolMeta.isRequired`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt:58-62`）落到 `agent_tool.is_required`（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:97`）。可选工具走「绑定行到交付」；必须工具在交付时无条件追加（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:641-646`），因此没有配置界面能关掉它，也进不了候选集（`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:60-62` 带 `is_required = 0` 条件）。

必须工具没有绑定行，也就没有取值处；同步遇到 `isRequired` 与 `required = true` 的环境参数同时出现时打 WARN 而不拒绝（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:139-148`），运行时表现为 `ToolEnvContext.require()` 抛「未配置」（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvContext.kt:38-44`）。

### 1.4 不经注册表的工具组

`TeamLeadToolBox` 与 `TeamMemberToolBox` 由装配代码在构造团队角色时直接 new（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxes.kt:17`、`:78`；`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:462-481`），不是 Spring bean，因此不进 `ToolRegistry`、不进 `agent_tool`、不可被绑定。它们的工具名通过 `TOOL_NAMES` 常量进权限引擎的框架 ALLOW 集合（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxes.kt:66`、`:122`；`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:616-627`）。

团队 lead 不装配业务工具：spec 交付侧显式传空绑定集合与空必须工具集合（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:584`、`:588`），装配侧再按 `isLead` 跳过整段工具装配（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:331-335`）。

## 二、表结构：列与键

列集合与索引以基线为准：`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`。

### 2.1 `agent_tool`：一行一个 `@Tool` 方法

建表在 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:86-105`，共 16 列（`:87-102`）：

| 列 | 位置 | 语义与取值来源 |
|------|------|------|
| `id` | `:87` | 自增主键（`:103`）；同步只更新已存在行，`id` 因此稳定，agent 绑定随之保留（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:167-171`） |
| `name` | `:88` | `@Tool.name`，全平台唯一（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:104`） |
| `display_name` | `:89` | `@ToolMeta.displayName`，空则回落 `@Tool.name`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:191`） |
| `display_name_zh` | `:90` | `@ToolMeta.displayNameZh`，空则为 NULL（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:192`） |
| `description` | `:91` | `@Tool.description`，即发给 LLM 的描述（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:116`） |
| `bean_name` | `:92` | 工具组 bean 名（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:194`） |
| `method_name` | `:93` | Java 方法名（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:195`） |
| `required_env_param_keys` | `:94` | 声明中 `required = true` 的环境参数 key 的 JSON 数组（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:135-137`） |
| `read_only` | `:95` | `@Tool.readOnly`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:117`） |
| `need_confirm` | `:96` | 只来自 `@ToolMeta.needConfirm`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:107-108`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt:40`） |
| `is_required` | `:97` | `@ToolMeta.isRequired`，`NOT NULL DEFAULT 0`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:198`） |
| `status` | `:98` | 恒为 1：插入与更新语句都写死字面量（`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:86`、`:103`） |
| `creator` | `:99` | 插入写 `'SYSTEM'`（`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:86`），更新时保留原值（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:170`） |
| `active` | `:100` | 恒为 1（`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:86`、`:104`） |
| `create_time` | `:101` | 首次插入时写入，之后保留（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:169`） |
| `update_time` | `:102` | 每次刷新写 `NOW()`（`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:105`） |

这张表没有租户维度，也没有超时位与「解析不到时是否跳过」这类开关位（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:86-105`、`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:5-6`）；超时的归属见 5.3。

### 2.2 `agent_tool_binding`：一个 agent 对一个工具的授权与取值

建表在 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:109-119`，共 7 列（`:110-116`）：`id`、`agent_id`、`tool_id`、`need_confirm`、`env_bindings`、`create_time`、`update_time`。主键 `:117`，组合唯一键 `uk_agent_tool_binding_agent_id_tool_id (agent_id, tool_id)`（`:118`）。

两个业务列的含义：

- `need_confirm`（`:113`）：本 agent 调用本工具是否需要人工确认。实体字段见 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolBinding.kt:28-29`，写入方 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:427`。
- `env_bindings`（`:114`）：本 agent 为本工具绑定的环境参数快照，JSON 数组。实体字段 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolBinding.kt:31-32`，序列化 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:606-631`。

该表的业务列只有 `need_confirm` 与 `env_bindings`，没有类型位，也没有「解析不到时是否跳过」这类开关位；resultMap 与插入语句的列集合与此一致（`harnax-entity/src/main/resources/mapper/AgentToolBindingMapper.xml:5-13`、`:19-25`）。

### 2.3 `agent_tool_env_param`：工具的环境参数定义

建表在 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:123-136`：`id`、`tool_id`、`env_param_name`、`description`、`required`、`secret`、`default_value`、`create_time`、`update_time`（`:124-132`），唯一键 `uk_tool_env_param_name (tool_id, env_param_name)`（`:134`），索引 `idx_tool_id`（`:135`）。

`tool_id` 指向 `agent_tool.id`（`:125`），因此环境参数定义是方法级的。定义内容由 `@ToolMeta.envParamDefs` 声明（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvParamDef.kt:22-33`）。

### 2.4 `tool_invocation_log` / `tool_invocation_stats`：一次工具调用

`harnax-agent-service` 的执行中间件 `ToolInvocationMiddleware` 在一次工具调用收尾时写一行明细进 `tool_invocation_log`：`kind` 标出来源（`builtin` 下发的工具 / `mcp` MCP 服务的工具 / `cli` 经 shell 执行的已下发 CLI 包 / `shell` 裸 shell 命令 / `framework` 运行时自带），`tool_name` 是模型看到的那个名字（`kind=cli` 时是命中的命令名），`mcp_id` / `cli_id` 只在对应来源上填。`outcome` 只取四个终态 `SUCCESS` / `ERROR` / `DENIED` / `INTERRUPTED`；`start_time` 与 `ts` 是 `datetime(3)`，秒级精度会把同一秒里的两次调用塌成一瞬。

写入经 `ToolInvocationAdaptor`（tools-sdk 的 `fun interface`）到 `harnax-agent-service` 的 `ToolInvocationAdaptorImpl`，那是一个带界队列的后台批量写线程：队列满即丢弃并计数，不阻塞回合。

`harnax-admin` 每小时把过完的那天折进日聚合 `tool_invocation_stats`（唯一键 `(stat_date, tenant_id, kind, subject_id, tool_name)`，六个耗时桶 + 四终态计数 + `sum_duration_ms`），明细按保留天数清理，聚合永久保留。

读侧是三个 GET：`/api/admin/tool-metrics/summary`、`/time-series`、`/invocations`，租户一律取自令牌而不是查询参数；按工具维度读聚合表，按智能体 / 会话维度读明细表，后者受保留窗口限制。`mcp_call_log` 不在这条链上，它是 MCP 按用户授权的账本，不是指标源。

### 2.5 五张表的写入方与生命周期

| 表 | 行语义 | 唯一写入方 | 生命周期 |
|------|------|------|------|
| `agent_tool` | 一个 `@Tool` 方法 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:134-179` | 插入与刷新，从不删除：Mapper 接口没有 delete 方法（`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentToolMapper.kt:14-39`），XML 没有 delete 语句（`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml`） |
| `agent_tool_env_param` | 工具的一个环境参数定义 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:228-301` | 随定义同步：命中则改（`:259-266`）、新增则插（`:267-280`）、当前声明集合里没有的则删（`:284-292`） |
| `agent_tool_binding` | 一个 agent 对一个工具的授权与取值 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:403-436` | 保存 agent 时整组重写（`:404`、`:434`）；删除 agent 时随 agent 清理（`:249`） |
| `tool_invocation_log` | 一次工具调用 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolInvocationAdaptorImpl.kt:167-171`（`batchInsert`），事件由 `harnax-agent/harnax-harness-core` 的 `ToolInvocationMiddleware` 发出 | 超出保留窗口的行由 admin 的每小时任务删除（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt:94`，`deleteRolledOut`）：带租户归属的以「那一天已折进聚合」为前提，`tenant_id IS NULL` 的行没有聚合可等、过窗即删；两种都不随 session 或 agent 清理 |
| `tool_invocation_stats` | 一天 × 租户 × 来源 × 归属主体 × 工具 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt:89`（`upsertDay`） | 永久保留；重算某天时该天的行整行重写，无删除路径 |

工具行永不消失，`agent_tool` 因此会随代码声明的累积变长；页面按 `active = 1` 全量列出（`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:65-67`），其中可能包含交付侧挡下的名字（见 3.4）。

## 三、注册链路：注解到 `ToolRegistry` 到启动期落库

### 3.1 进程内发现：`ToolRegistry`

`ToolRegistry` 是一个 `@Component`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:15-16`），在 `@PostConstruct` 里按类型取 bean 建表（同文件 `:26-49`，取 bean 在 `:28`），并同时生成元数据：

- 逐方法读 `@Tool` 与 `@ToolMeta`（`:93-95`，`Tool` 来自 agentscope：`io.agentscope.core.tool.Tool`）。
- 行名取 `@Tool.name`，为空时回落方法名（`:113`）；描述与只读位取 `@Tool.description` 与 `@Tool.readOnly`（`:116-117`）。
- 确认位只认 `@ToolMeta.needConfirm`，注解缺省即 `false`（`:107-108`）。
- 环境参数定义取 `@ToolMeta.envParamDefs`（`:97-105`），必须工具位取 `@ToolMeta.isRequired`（`:120`）。
- 没有任何 `@Tool` 方法的 `ToolBox` bean 进 bean 表但不进元数据表（`:125-128`），因此不会落库成行。

对外读取口：`getToolBox(beanName)`（`:51`）、`createToolBoxInstance(beanName)`（`:61-71`）、`getToolMeta(beanName)`（`:80`）、`getAllToolMeta()`（`:83`）。

### 3.2 启动期刷新落库：`BuiltinToolAutoRegistrar`

`BuiltinToolAutoRegistrar` 是 admin 进程里的 `@Component`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:36-41`），监听 `ApplicationReadyEvent`（`:57-58`）。它注入本进程的 `ToolRegistry` 与两张表的 Mapper（`:38-40`），因此整条链路都在 admin 进程内完成，不经过跨服务调用。

执行顺序：

1. 读 `getAllToolMeta()`；集合为空则记一行日志并直接返回，不触碰表（`:59-63`）。
2. 把每个 `@Tool` 方法摊平成 `(beanName, method)` 列表（`:65`），先做重名校验（`:66`）。
3. 逐个方法解析成行：`saveTool()`（`:79`、`:134-179`）。
4. 按刚解析出的行 id 同步该组的环境参数定义（`:83-85`），使保存失败的组不会把参数指向过期 `tool_id`（`:81-82`）。
5. 全部处理完后写入 `declaredNames`（`:101`），口径是「声明过的名字」而非「写成功的名」——某组写失败仍然算声明（`:99-100`）。

### 3.3 按名字收敛：插入、刷新、不动

对账键是 `@Tool.name`，查行为用 `selectByName`（`:151`；该语句刻意不带 `active` 条件，`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:38-43`）：

- 表里没有：插入（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:152-159`），语句见 `harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:78-88`。
- 两边都有：逐列比对代码拥有的列（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:206-218`），无差异则不写库（`:163-165`），有差异则保留 `id`、`create_time`、`creator` 后整行更新并记下差异列名（`:167-177`）。
- 表里有、代码里没有对应声明：不读不写，行保持原样。`agent_tool` 上没有删除路径可言（`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentToolMapper.kt:7-10`）。

比对列不含 `name`：行是靠名字找到的，声明换名等于另一个工具，会得到自己的新行，原行按「只增不删」留着（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:181-184`、`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:90-91`）。

同一个名字被两个 `@Tool` 方法声明时，谁赢取决于 bean 顺序，因此这没有正确解：校验在任何写库之前抛出 `IllegalStateException`，消息逐个列出 `bean::method`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:116-128`）。数据库唯一键 `uk_agent_tool_name`（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:104`）兜的是运行期数据，代码期的冲突由这道闸兜。

### 3.4 声明集合与交付过滤

`declaredNames` 由 `@Volatile` 保存，读取口是 `registeredToolNames()`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:52-55`）。交付侧读它来挡住「声明已离开 classpath」的行：行留在表里给管理员看，但不随 spec 下发（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:650-658`）。集合为空表示同步未运行，此时该过滤整体关闭，避免把「不知情」当成「零工具」（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:48-51`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:657`）。被挡下的 id 记一行 WARN（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:660-666`）。

## 四、装配与下发链路

链路分四段：管理员保存绑定（admin）→ 会话取 spec（admin 交付）→ spec 解析成运行时对象（agent-service）→ 实例化与授权收敛（装配）。

### 4.1 admin 侧：绑定保存

Agent 的创建与更新请求携带工具列表 `toolList`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:37`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentUpdateRequest.kt:31`），元素类型 `ToolConfig` 只有三个字段：工具 id、`needConfirm`、`envBindings`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolConfig.kt:6-15`）。

`saveToolBindings` 采用整组重写：先按 `agent_id` 删空，再批量插入新行（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:404`、`:434`；语句见 `harnax-entity/src/main/resources/mapper/AgentToolBindingMapper.xml:27-29`、`:19-25`）。逐条绑定落库前有三道校验：

- 工具可绑定：`resolveBindableTools` 用 `selectByIds` 批量取行，取不到就抛 `BizException`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:483-491`、调用点 `:409`）；该查询已排除 `active = 0`（`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:31-36`）。
- 环境绑定可解析：同一 key 指向多个来源、引用缺失或跨租户、引用已停用的变量，三种情况都在保存期拒绝（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:413`、实现 `:649-679`）。
- 必填参数有值：按 `agent_tool_env_param` 的 `required` 判定，且工具自身的 `default_value` 不计入（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:414-421`，`defaultValueCounts = false` 在 `:420`；判定函数 `:701-725`；参数定义读取 `:731-739`）。

`agent_tool_binding.need_confirm` 的值直接来自请求里的布尔位（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:427`）。它的语义方向是「只能加严」：运行时把工具位与绑定位做或运算（见 5.1），所以代码声明为需确认的工具，绑定关不掉。

绑定的读取：agent 详情按 `agent_id` 读绑定并回查明细（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:304`，`selectByAgentId` 见 `harnax-entity/src/main/resources/mapper/AgentToolBindingMapper.xml:15-17`）；删除 agent 时先清理绑定（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:249`）。

### 4.2 admin 侧：spec 交付

交付入口是内部 API `GET /api/admin/internal/agent-spec/{sessionId}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:54`、`:284`）。组装函数 `buildAgentSpecResponse` 把绑定集合与必须工具 id 作为参数接收，默认值就是两次表读：`toolBindingMapper.selectByAgentId` 与 `agentToolMapper.selectRequiredTools()`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:606-623`，两处默认值在 `:618`、`:622`）。

交付集合的构造顺序：

1. `toolList` JSON：每个绑定行输出 `id`、`need_confirm`、已解析的 `env_bindings` 三项（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:625-636`）。
2. 待交付 id 集合 = 绑定行 id 加上尚未绑定的必须工具 id，去重（`:641-646`）。必须工具因此不需要绑定行也关不掉；已存在的绑定行仍然被尊重（`:638-640`）。
3. 按 id 批量取行后用 `registeredToolNames()` 过滤，只留下当前代码声明过的名字（`:647-659`）；被挡下的 id 记 WARN（`:660-666`）。
4. `toolDetails` 逐行转 `ToolDetailDto`（`:668-688`），其中 `bindingNeedConfirm` 取自该工具的绑定行（`:685`），DTO 字段见 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/ToolDetailDto.kt:10-47`。

响应里工具相关的两个字段：`toolList`（字符串 JSON）与 `toolDetails`（结构化明细），见 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/AgentSpecInfoResponse.kt:47-48`、`:81-82`。env 值在交付时解析，绑定行的引用型变量按当前租户现取最新解密值、取不到退回快照（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:893-940`）。

### 4.3 agent-service 侧：spec 解析

`AgentSpecResolver.resolve(sessionId)` 调 admin 的统一端点并把整份 spec 放进上下文持有者，供随后的适配器读取（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt:56-61`）。

`buildAgentSpec` 里工具段只做两件事（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt:252-260`）：把每个 `toolDetails` 元素折成 `ToolSpec(toolId, needConfirm = bindingNeedConfirm)`。`ToolSpec` 的全部字段是 `toolId`、`toolName`、`needConfirm`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolSpec.kt:3-7`），装配路径只读 `toolId` 与 `needConfirm`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:346`、`:377`），`toolName` 一律以库行与 `@Tool.name` 为准。

运行时的工具规格因此没有任何「缺失时跳过」的开关位：解析不到的工具只记 WARN 并继续（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:393`、`:396`）。

环境参数在这一步合并：先解析 `toolList` 与 `mcpList` 两条 JSON 里的 `env_bindings`（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt:265-266`、实现 `:282-296`），合成一个扁平 map 后包成 `ToolEnvContext` 放进 `contextForTools`；集合为空也照样注册，好让 `require()` 报「未配置」而不是注入失败（`:269-271`）。`AgentSpec.contextForTools` 与 `toolSpecs` 的声明见 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt:33-34`。

SPI 的实现两跳：契约 `ToolConfigAdaptor.getToolConfig(toolId)`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolConfigAdaptor.kt:5-7`），实现 `ToolConfigAdaptorImpl` 先读上下文里 admin 已解析的 `toolDetails`（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolConfigAdaptorImpl.kt:32-39`），读不到再直接查库兜底（`:41-48`）。DTO 转实体逐列拷贝 `id`、`name`、`displayName`、`displayNameZh`、`description`、`beanName`、`methodName`、`readOnly`、`needConfirm`、`requiredEnvParamKeys`、`status`（`:54-67`）。

### 4.4 装配：实例化与授权收敛

`HarnessAutoConfiguration` 把 `ToolConfigAdaptor` 与 `ToolRegistry` 作为可选项注入 launcher（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt:321-322`、`:340-341`、`:354-355`），launcher 侧对应两个可空参数（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:124-125`）。

工具装配段的整体形状（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:373-455`）：

- 前置条件是非 lead、`toolSpecs` 非空、`toolConfigAdaptor` 可用（`:380`）；缺适配器时整段跳过并记 WARN（`:453-454`）。
- 按 `beanName` 去重（`:383`、`:402`、`:406`）：同一个 `ToolBox` 只实例化并注册一次，多个方法行共享同一个组。
- 实例化用 `createToolBoxInstance(beanName)`（`:403`；`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:62-72`，走无参构造，失败则记 ERROR 并回退单例），紧接着 `addTool(toolBox)`（`:405`），中间没有任何装配动作。`ToolBox` 只有 `abstract fun name(): String`（同模块 `ToolBox.kt`），实例不持有会话态，装配也不需要往实例里塞任何东西；一次调用落在哪个会话、哪个 agent、哪个租户，由装配挂上的 `ToolInvocationMiddleware` 决定（见 5.4）。
- `addTool` 是整组注册：`toolkit.registerTool(it)`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt:85-87`），一次把一个类的所有 `@Tool` 方法都交出去。
- 因此必须有收敛步：把本 agent 未被授予的方法名逐个 `removeTool`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:443-451`；`removeTool` 见 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt:92-94`）。授予集合按 bean 累积（`:425`），漏一次就会多给工具，这也是「同组只勾一个方法」能成立的原因。
- `status = 0` 的行在解析阶段就跳过（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:396-399`），其名字不进授予集合，所以同组兄弟注册也带不回它。
- `contextForTools` 注册进框架的 `ToolExecutionContext`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:457-465`；`addToolContext` 见 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt:96-98`），声明了 `ToolEnvContext` 参数的工具方法由此拿到它（用法见 `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/EmailToolBox.kt:63`）。

框架自带的 meta tool 与这条链路无关：它只由 `AgentSpec.enableMetaTool` 决定，且对 lead 关闭（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:376-378`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt:33`）。

## 五、确认、危险输入与超时：判定在哪一侧

这三类行为都不写在工具表里做「配置」，而是由装配侧在读到工具行之后决定；admin 侧只负责把声明与取值送到位。

### 5.1 确认位：工具声明与 agent 绑定做或运算

装配时的判据是 `toolConfig.needConfirm == 1` 或 `toolSpec.needConfirm`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:377`）。两个来源分别是：

- 工具位：`agent_tool.need_confirm`（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:96`），值来自 `@ToolMeta.needConfirm`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:107-108`）。
- 绑定位：`agent_tool_binding.need_confirm`（同文件 `:113`），经 `bindingNeedConfirm`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:685`）与 `ToolSpec.needConfirm`（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt:257`）到达装配。

命中即把行名加入待确认集合（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:378-381`），加入的名字是 `@Tool.name`，好让权限引擎按工具名匹配（同文件 `:379-380`）。随后每个名字生成一条 ASK 规则（`:629-633`），框架自带工具则一律 ALLOW，避免默认模式下把内部工具一起卡在确认上（`:616-628`）。待确认集合与危险输入集合一起进 `dangerousTools`（`:678`）。

或运算决定了方向：绑定只能给原本不需确认的工具加上确认，关不掉代码声明为需确认的工具（保存侧同口径的说明见 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:425-427`）。

### 5.2 危险输入：代码属性，靠装饰器生效

`@ToolMeta.dangerousInput`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt:41-57`）是纯代码属性：`agent_tool` 没有对应列（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:86-105`），同步生成的行也不写它（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:189-203`）。

装配侧的识别方式是反射扫描：遍历 ToolBox 类的方法，把带该注解的方法的 `@Tool.name`（缺省回落方法名）收集起来（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:862-871`），按类去重以免重复反射（`:342-343`、`:386-390`）。生效方式是把已注册的工具换成包装类（`:652-662`；`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt:201-206`）：包装体沿用原工具的名字、描述与入参 schema（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt:39-48`）并把执行原样委托回去（`:97`）。命中的判定返回带 `safety` 理由的 ASK（`:74-78`、`:84-88`），按权限引擎契约这类 ASK 在 BYPASS 下也生效（`:19-21`）。

去重规则：同一工具若已有 5.1 的 ASK 规则，装配跳过包装并记 DEBUG（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:650-657`）。

### 5.3 执行超时：归装配侧的整轮预算

工具级超时在数据模型与契约层都没有落点：`agent_tool` 无超时列（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:86-105`、`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:5-6`），`@ToolMeta` 的属性全集是 `displayName`、`displayNameZh`、`envParamDefs`、`needConfirm`、`dangerousInput`、`isRequired`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt:31-62`），`ToolSpec` 也只有三个字段（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolSpec.kt:3-7`）。

超时施加在一次完整回合的调用上，值随装配传入：`turnTimeoutSeconds = turnBudget(teamRole)`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:697`），`turnBudget` 对散兵取 `harnessConfig.turnTimeoutSeconds`、对团队轮次取 `team.turnTimeoutSeconds`，并要求团队预算严格高于成员预算否则记 WARN（同文件 `:710-722`）。落地方式是 `HarnessAgentWrapper` 对批式调用与流式各下一道超时（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt:384`、`:764`），非正值表示不加超时（同文件 `:85-88`）。

配置项与默认值：`harness.turn-timeout-seconds` 默认 300（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt:80`、`:87-91`；`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt:26`），`harness.team.turn-timeout-seconds` 默认 1800（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt:106`、`:113-118`；`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt:57`）。

理由是同一个回合里工具与其他动作交织，逐工具计时需要一个执行层，而该层在框架侧；库里存一个无人读取的数字只会给出错误承诺。慢工具的收口手段因此是确认与危险输入拦截（5.1、5.2），不是超时。

### 5.4 调用留痕：记什么、由谁写、留多久

留痕只有一个来源：`ToolInvocationMiddleware`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationMiddleware.kt:37`）在 `onActing` 里先记下模型这一批请求的每个调用（名字、入参、起始毫秒），再在同一条结果流上等终态事件——终态到达即组一行 `ToolInvocationEvent` 交出去，流提前结束（异常或取消）时还开着的起点一律记成 `INTERRUPTED`。终态只取 `SUCCESS` / `ERROR` / `DENIED` / `INTERRUPTED` 四个，非终态不产生事件。一次调用属于哪个会话、哪个智能体、哪个租户、哪个最终用户，由构造参数带进来，而中间件是每次装配新建的实例（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:647-662`）。

来源 `kind` 与 CLI 归因由同包的 `ToolInvocationClassifier` 判定，顺序是契约而不是实现细节：注册表答案（`mcp`）优先于下发工具名单，shell 检查排在工具名单之前（下发的 CLI 是经 shell 工具执行的），三者都不是才落 `framework`。`mcpIdsByTool` 取的是全部工具注册完之后的快照，早取会把 MCP 工具记成 `framework`。

事件经 `ToolInvocationAdaptor`（tools-sdk 的 `fun interface`，`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolInvocationAdaptor.kt:17-19`）到 `harnax-agent-service` 的 `ToolInvocationAdaptorImpl`：一个带界队列加一条后台批量写线程，默认容量 512、每 200 毫秒一趟、每批 64 行（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolInvocationAdaptorImpl.kt:36-40`）。队列满或写线程已停即丢弃新事件并计数（`:82-86`），不阻塞回合；`emit` 不等数据库也不抛异常。入参与结果正文在写入侧按 `harness.metrics.invocation.capture-max-chars`（默认 2000）截断（`:40`），关掉 `harness.metrics.invocation.capture-payload` 则两列留 NULL（`:39`）。这四个数值都在构造期夹进合法区间并记 WARN，越界不会让服务起不来（`:57-64`）。

`tool_invocation_log` 一行一次调用（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:759-784`）：`kind` 标来源，`tool_name` 是模型看到的那个名字（`kind=cli` 时是命中的命令名），`mcp_id` / `cli_id` 只在对应来源上填，`start_time` / `end_time` / `ts` 是 `datetime(3)`——秒级精度会把同一秒里的两次调用塌成一瞬。生命周期见 2.5：admin 每小时把过完的那天折进 `tool_invocation_stats`，超出保留窗口的行随后释放——带租户归属的以「那一天已折进聚合」为前提，`tenant_id IS NULL` 的行过窗即删。敏感值应当在工具方法内部避免回显，因为入参与结果正文都会进库。

## 六、对外 API 面

### 6.1 工具管理面：五条只读 GET

控制器挂在 `/api/admin/tools`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:17`），类注释直接声明只读、工具由代码注册（同文件 `:13-15`）。

| 方法与路径 | 请求参数 | 映射锚点 | 服务实现 | SQL 口径 |
|------|------|------|------|------|
| `GET /page` | `pageNum`、`pageSize`、`keyword`、`status`（`:29-32`） | `:26-39` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt:26-31` | `harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:45-57`：`active = 1`，可选 `keyword` 命中 `name`、`display_name`、`description`，可选 `status`，按 `status DESC, update_time DESC` |
| `GET /{id}` | 路径 `id`（`:43-44`） | `:41-52` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt:33` | `harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:26-28`：按 id 且 `active = 1`；取不到时返回带 `error.tool.notfound` 文案的错误（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:47`） |
| `GET /available` | 无 | `:54-65` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt:58` | `harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:60-62`：`status = 1` 且 `active = 1` 且 `is_required = 0`，按 `name` 排序 |
| `GET /builtin` | 无 | `:67-78` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt:60` | `harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:65-67`：只带 `active = 1`，按 `name` 排序 |
| `GET /{id}/required-env-params` | 路径 `id`（`:82-83`） | `:80-90` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt:62-65` | 读 `agent_tool.required_env_param_keys` 并解析为字符串数组（同文件 `:67-78`） |

`/available` 与 `/builtin` 的口径不同是有意的：前者给 agent 配置做候选集，因此排除必须工具；后者给工具页做全量展示，因此不加 `status` 条件。

### 6.2 响应形状

统一响应体 `AgentToolResponse` 带 `id`、`name`、`displayName`、`displayNameZh`、`description`、`beanName`、`methodName`、`envParams`、`readOnly`、`needConfirm`、`isRequired`、`requiredEnvParamKeys`、`status`、`creator`、`createTime`、`updateTime`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentToolResponse.kt:7-54`），组装在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt:35-56`。

`envParams` 逐项来自 `agent_tool_env_param`，其中 `secret = 1` 的 `default_value` 先解密再打掩码后返回（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt:91-112`、掩码规则 `:80-85`）。

### 6.3 没有写路径

控制器全文只有上述五个 `@GetMapping`，没有任何 `@PostMapping`、`@PutMapping`、`@PatchMapping`、`@DeleteMapping`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:26`、`:41`、`:54`、`:67`、`:80`）。服务接口的六个方法全是读方法，注释明确它不暴露创建、更新、启停与删除（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/AgentToolService.kt:7-24`）。`agent_tool` 相关的 DTO 只有展示与配置三类：`AgentToolResponse`、`ToolConfig` 与 `ToolEnvParamEntry`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/`），没有工具的新增、更新与连通性测试请求体。

`agent_tool` 因此只有一条写入路径：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:134-179`；Mapper 层也备不出删除语句（`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentToolMapper.kt:14-39`）。

### 6.4 agent 配置面与内部交付面

工具的授权与取值通过 agent 端点写：创建与更新请求里的 `toolList`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:37`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentUpdateRequest.kt:31`），落库见 4.1。

跨服务的工具下发只有一个入口：`GET /api/admin/internal/agent-spec/{sessionId}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:54`、`:284`），载荷见 4.2。团队的 lead 走 `specForTeam`，工具绑定集合与必须工具 id 都显式传空（同文件 `:564-590`，两处空集在 `:584`、`:588`；调用点 `:477`）。

### 6.5 前端消费点

`/builtin` 供工具页展示（`harnax-webui/src/services/ant-design-pro/tool.ts:14-16`、`harnax-webui/src/pages/tool/index.tsx:15`、`:42`）；`/available` 供 agent 表单取候选集（`harnax-webui/src/services/ant-design-pro/tool.ts:6-8`、`harnax-webui/src/pages/agent/components/CreateForm.tsx:111`、`harnax-webui/src/pages/agent/components/UpdateForm.tsx:220`），勾选与取值在配置面板里完成（`harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx:105-113` 选工具与 `needConfirm` 开关、`:120-128` 环境参数行）。

### 6.6 调用指标的读接口

三个只读 GET 挂在 `/api/admin/tool-metrics`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt:27`、`:29`）：`/summary`（`:35`）按主体给窗口内合计，`groupBy` 取 `tool`（默认）/ `agent` / `session`；`/time-series`（`:54`）按 `day` / `week` / `month` 分档给趋势，空档补零；`/invocations`（`:75`）给单次调用明细。三个端点都没有租户形参，租户一律取自令牌；`days` 都是 1..365。`tool` 维度读日聚合 `tool_invocation_stats`，`agent` 与 `session` 两个维度读明细 `tool_invocation_log`，因此后两者受保留窗口限制——窗口之外的调用已在折算之后释放，页面只能问到窗口内的。页面在 `harnax-webui` 的「监控与治理」分组下（`harnax-webui/src/pages/call-metrics`）。

## 七、环境参数绑定

「要什么参数」与「值是什么」分在两张表：定义在 `agent_tool_env_param`，由代码同步独占写入；取值在 `agent_tool_binding.env_bindings`，由管理员写入。两侧的行语义与写入方见 2.3、2.5。

### 7.1 定义侧：注解到 `agent_tool_env_param`

声明位置是 `@ToolMeta.envParamDefs`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt:38`），元素类型 `ToolEnvParamDef` 有五个属性：`key`（无默认值）、`description`（默认空）、`required`（默认 `true`）、`secret`（默认 `false`）、`defaultValue`（默认空），见 `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvParamDef.kt:22-33`。现有实例：`sendEmail` 声明五个参数，其中 `SMTP_PORT` 带默认值 `587`、`SMTP_PASSWORD` 标了 `secret = true`（`harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/EmailToolBox.kt:45-51`）。

`ToolRegistry` 把注解逐属性转成 `ToolEnvParamDescriptor`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:97-105`、类型定义 `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMetaDescriptor.kt:6-17`）。`BuiltinToolAutoRegistrar.syncToolEnvParams` 按方法行 id 对账：已有则逐属性比对后更新（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:248-266`）、新增则插入（`:267-280`）、当前声明集合里没有的定义则删除（`:284-292`）。参数定义的删除归到「更新它所属的工具」这一档，不归到「删除工具」（`:220-227`）。

同时把 `required = true` 的 key 拼成 JSON 数组写进 `agent_tool.required_env_param_keys`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:135-137`、列定义 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:94`）。这一列只被管理页与保存期的必填校验读（`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt:52-57`），运行时是否满足由 `ToolEnvContext.require()` 判定（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvContext.kt:38-44`）。

### 7.2 取值侧：引用与字面值并存

列定义在 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:114`，请求侧结构是 `EnvBinding`：`envKey` 加三种取值形态——`envValue`、`envVarId` 与 `envVarName`（引用 `env_variable` 表）、`customValue`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolConfig.kt:18-33`）。

序列化规则见 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:606-631`：

- 引用形态只存 `envVarId` 与解析出的 `envVarName`，不存值（`:612-620`）。原因是客户端带来的值只是展示值（敏感变量是 `******`），把它快照进去会让工具在变量被删后拿到一串星号，而服务端解析又会把明文写进这一列。
- `customValue` 直接存（`:621-622`）；没有引用而只带 `envValue` 的，按字面值同样存成 `customValue`（`:623-625`）。

### 7.3 保存期校验

`assertEnvBindingsBindable` 拒绝三种写坏的绑定（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:649-679`）：同一个 key 指向多个来源（`:653-659`，实际判定在 `:654`，来源口径见 `:688-693`）、引用缺失或落在别的租户（`:661-670`）、引用已停用的变量（`:673-678`）。

`assertRequiredEnvParamsFilled` 按定义表的 `required` 位逐个检查有没有可解析的值（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:701-716`）。单值的判定在 `hasRuntimeValue`：引用型直接算已填（`:720`），带掩码的文本是回显产物而不是值（`:721-723`），默认值是否计入由调用方决定（`:724`）；内置工具这一路传的是不计入（`:420`），理由写在调用处注释（`:416-417`）。

### 7.4 交付期解析

`resolveEnvBindingsJson` 逐条把绑定元素解成 `{envKey, envValue}`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:893-940`）：`envKey` 缺失的元素直接丢弃（`:904`）；引用先按 agent 的租户取当前最新解密值（`:910-912`），变量已不可解析时退回快照并记 WARN（`:913-924`）；无引用时用 `customValue`、再用 `envValue` 快照（`:926-927`）；最终没有值的 key 不下发（`:930-934`）。解析结果进 `toolList` JSON 的 `env_bindings` 字段（`:632`），MCP 与 CLI 绑定复用同一函数（`:962`、`:1036`）。

引用与快照并存意味着「改一处变量、所有引用生效」，代价是页面显示的快照不等于运行时的实际值。

### 7.5 运行期消费：agent 级扁平 map

`AgentSpecResolver` 把工具与 MCP 两条列表里的 `env_bindings` 依次合进同一个可变 map（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt:233`、`:265-266`），再包成 `ToolEnvContext` 放进 `contextForTools`；map 为空也照样注册（`:269-271`）。列表内部用 `associate` 折叠，同名 key 后写入者生效（`:291`）。

`ToolEnvContext` 的语义就是「按名字回答，不区分来源」（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvContext.kt:22-27`）：`get()` 可空（`:33`），`require()` 在缺失或空白时抛 `IllegalArgumentException`（`:38-44`）。装配侧把 `contextForTools` 注册进框架的执行上下文（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:417-425`），工具方法把它声明为参数即可拿到（`harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/EmailToolBox.kt:63`），取值处见同文件 `:74-78`。

因此作用域是 agent 级：同一个 agent 的多个工具、以及它的 MCP 绑定，共享一份 map；跨工具复用同名 key 可行，同名冲突则由合并顺序决定谁生效。

### 7.6 展示与掩码

管理响应把定义逐项带出，`secret = 1` 的 `default_value` 先解密再掩码（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt:91-112`、掩码函数 `:80-85`）。引用型绑定库里没有值（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:612-620`），页面因此显示变量名而不是值；这串掩码文本若被表单原样回传，保存校验也不把它当作已填（`:721-723`）。

## 八、不变量一览

| 不变量 | 现状表述 | 锚点 |
|------|------|------|
| 工具无租户维度 | `agent_tool` 不带 `tenant_id`，一行对所有租户可见；隔离只落在绑定层，绑定随 agent 行走 | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:86-105`、`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:5-6`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:403-436` |
| 工具身份就是 `@Tool.name` | 一个方法一行，行靠名字解析与唯一；`bean_name` 与 `method_name` 只是属性 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:113`、`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:104`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:151`、`:181-184` |
| 注册后不删除 | 同步只插入与刷新；声明离开 classpath 的行留在表里，由交付侧过滤挡住 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentToolMapper.kt:7-10`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt:52-55`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:650-658` |
| 执行超时归装配侧 | 数据模型与契约层没有工具级超时位；超时按整回合施加，值来自配置 | `harnax-entity/src/main/resources/mapper/AgentToolMapper.xml:5-6`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt:31-62`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:697`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt:384` |
