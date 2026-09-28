# Harnax 工具接入设计（中文）

本文是「工具」域的设计文档：接入模型、数据不变量、分层职责、端到端链路、关键决策及其约束，以及该域与 MCP / Skill 的口径关系。注解属性、内置工具清单、管理 API、新工具开发步骤这些操作层内容在《Harnax 工具能力与开发指南》一份，两份各自成文，互不依赖对方的章节编号。

## 1. 设计目标

| 目标 | 落地机制 |
| --- | --- |
| 工具定义只有一个事实来源：代码 | `@Tool` / `@ToolMeta` 是唯一声明处；`agent_tool` 由 `BuiltinToolAutoRegistrar` 写入，管理 API 与页面只读 |
| 运行时看到的工具集可预期、可审计 | 交付集合 = 绑定行 ∪ 必须工具，再按 `registeredToolNames()` 过滤；没有兜底工具集，也没有「页面配了但运行时偷偷加上」的路径 |
| 授权粒度到单个工具 | 一个 `@Tool` 方法一行记录，因此确认、环境参数、按 agent 授权都是方法级 |
| 配置数据与配置定义分离 | 「要什么参数」在 `agent_tool_env_param`（代码同步）；「值是什么」在 `agent_tool_binding.env_bindings`（管理员写入） |
| 危险能力可声明、且默认收口 | `readOnly` / `needConfirm` / `dangerousInput` 三个声明进同一套权限引擎，危险输入的 ASK 属于 bypass-immune |
| 工具执行留痕但不改变执行 | `ToolBox.execute` 记录入参、结果、耗时；记录失败或日志器缺省都不影响工具返回值 |

## 2. 分类模型

### 2.1 唯一的实现方式

工具只有代码内置一类：实现 `ToolBox` 子类的 `@Tool` 方法，随进程 classpath 发布。`agent_tool` 表没有类型列，`AgentToolService` 没有写方法，`AgentToolController` 只有 GET。接入一个外部系统意味着写一个 ToolBox，不是配一条记录。

### 2.2 两层结构：工具组与工具

- 工具组 = 一个 `ToolBox` bean，标识是 Spring bean 名（`bean_name`），职责是共享依赖与实例化单位。
- 工具 = 组内一个 `@Tool` 方法，标识是 `@Tool.name`（`agent_tool.name`），职责是授权、确认、环境参数的单位。

组与方法两级标识同时存在，但只有方法级名字承担身份：`bean_name` 与 `method_name` 只用于 `createToolBoxInstance` + 反射调用，把方法移到另一个 ToolBox 会让同一行的这两个属性被更新，行本身、`id` 以及所有 agent 绑定保持不变。

### 2.3 按是否可关分

`@ToolMeta.isRequired` → `agent_tool.is_required`。可选工具走「绑定行 → 交付」；必须工具走「交付时无条件追加」，因此没有配置界面能把它关掉，也进不了 `selectAvailableTools` 的候选集。

### 2.4 注册模型之外的一类工具组

`TeamLeadToolBox` / `TeamMemberToolBox` 由 `HarnessAgentLauncher` 在装配团队角色时直接构造，不是 Spring bean，因而不进 `ToolRegistry`、不进 `agent_tool`、不可被绑定。这类工具组的可见性由装配角色决定，名字通过 `TOOL_NAMES` 常量进权限引擎的框架 ALLOW 集合。

## 3. 数据模型

### 3.1 表与写入方

| 表 | 行语义 | 写入方 | 读取方 | 生命周期 |
| --- | --- | --- | --- | --- |
| `agent_tool` | 一个 `@Tool` 方法 | `BuiltinToolAutoRegistrar` 独占 | 管理页、agent 配置面板、`ToolConfigAdaptorImpl`、交付查询 | 只增与改，从不删；`status` / `active` 恒 1 |
| `agent_tool_env_param` | 工具的一个环境参数定义 | 同上 | 管理页、保存期必填校验 | 随定义同步增删，`tool_id` 指向当前行 |
| `agent_tool_binding` | 一个 agent 对一个工具的授权与取值 | `AgentServiceImpl.saveToolBindings` | 交付查询、装配 | 保存时整组重写；`(agent_id, tool_id)` 唯一 |
| `tool_call_log` | 一次工具调用 | `ToolCallLogAdaptorImpl` | 无（`ToolCallLogMapper` 只有 `insert`） | 只保留，不随 session / agent 清理 |

### 3.2 关键约束

- `agent_tool.name` 上有 `UNIQUE uk_agent_tool_name`：身份唯一性由数据库保证，重名行不可能同时存在。
- `agent_tool` 没有 `tenant_id` 列，也没有 `type` / `is_public` / `http_url` / `http_method` / `http_headers` / `input_schema` / `output_schema` / `env_params` / `timeout_seconds` 这些列：工具是平台资源，配置形态只有「代码声明的方法」一种，超时不在数据模型里。
- `agent_tool_binding` 没有「缺失时跳过」开关列：解析不到的工具一律 WARN 后继续，行为只有一条。
- `agent_tool_env_param` 的 `(tool_id, env_param_name)` 唯一，`secret = 1` 的 `default_value` 在响应前经 `SecretFieldEncryptor` 解密再掩码。
- `tool_call_log.agent_id` 与 `tool_call_log.tenant_id` 都可空，NULL 表示归属未知，不做猜测回填。

### 3.3 结构基线

列集合与索引以 admin 的 schema 基线 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` 为准：这一个脚本就是全部建表与列定义，没有需要往上叠加的后续版本。`agent_tool` 与 `tool_call_log` 各有一段建表语句写在这一个文件里，`agent_tool_binding` 与另外两张同构绑定表也一样。`agent_tool` 上的唯一键是 `uk_agent_tool_name (name)`，这张表不带租户列、不带超时列；`agent_tool_binding` 上的组合唯一键是 `uk_agent_tool_binding_agent_id_tool_id`；`tool_call_log` 带一个可空的 `tenant_id` 列与一个 `idx_tenant_ts (tenant_id, ts)` 索引。

## 4. 分层职责

| 层 | 模块与关键类型 | 负责 | 不负责 |
| --- | --- | --- | --- |
| 契约层 | `harnax-agent/harnax-tools-sdk`：`ToolBox`、`ToolMeta`、`ToolEnvParamDef`、`ToolEnvContext`、`SessionMetaContext` / `UserIdentifier`、`ToolRegistry`、`ToolCallLogAdaptor` / `ToolConfigAdaptor`、`ToolSpec` | 定义工具形态、注入契约与 SPI | 不查库、不决定是否绑定 |
| 实现层 | `harnax-tools-external/harnax-tools-buildin`：`TimeToolBox`、`EmailToolBox` | 声明并实现工具 | 不感知 agent、不读库 |
| 元数据层 | `harnax-admin`：`BuiltinToolAutoRegistrar`、`AgentToolController` / `AgentToolServiceImpl`、`AgentServiceImpl.saveToolBindings`、`InternalApiController.buildAgentSpecResponse` | 同步声明、只读暴露、绑定保存、spec 交付 | 不执行工具 |
| 运行层 | `harnax-agent/harnax-harness-core`：`HarnessAgentLauncher`、`HarnessAgentBuilder`、`DangerousInputCheckingTool`、`TeamToolBoxes`、`HarnessAgentWrapper`；`harnax-agent-service`：`AgentSpecResolver`、两个 adaptor 实现 | 实例化、授权收敛、权限规则、上下文注入、整轮超时、留痕 | 不定义工具、不改元数据 |
| 存储层 | `harnax-entity`：`AgentTool` / `AgentToolBinding` / `AgentToolEnvParam` / `ToolCallLogEntity` 与对应 Mapper | 读写与 SQL 口径 | 不含业务判断 |

依赖方向：实现层与运行层都只依赖契约层；admin 与 agent-service 各自把实现层放进 classpath 并扫描 `com.agnetix.harnax.tools`。同一份注解声明因此在两个进程里各自解析成注册表——admin 侧决定库里有什么，agent 侧决定运行时能不能实例化。

## 5. 端到端链路

1. **发布**：ToolBox 类打进 `harnax-admin` 与 `harnax-agent-service` 两个进程的 classpath。
2. **发现**：进程启动时 `ToolRegistry.init()`（`@PostConstruct`）按 `getBeansOfType(ToolBox::class.java)` 建 bean 表，并逐方法读 `@Tool` + `@ToolMeta` 生成 `ToolMetaDescriptor`；没有任何 `@Tool` 方法的 bean 不进元数据。
3. **同步**：`ApplicationReadyEvent` 触发 `syncBuiltinTools()`。先 `requireUniqueNames` 拒绝重名，再按名字 upsert `agent_tool`，然后按解析出的行 id 同步 `agent_tool_env_param`（含删除声明集合里没有的定义），最后把声明名集合写入 `declaredNames`。
4. **配置**：管理员在 agent 表单勾选工具、切确认开关、填环境变量值，`saveToolBindings` 校验可绑定与必填后整组重写 `agent_tool_binding`。
5. **交付**：会话取 spec 时 `buildAgentSpecResponse` 读绑定行与 `selectRequiredTools()`，去重、按 `declaredNames` 过滤，产出 `toolDetails`（含 `bindingNeedConfirm`）与带已解析 `env_bindings` 的 `toolList`；引用型环境变量按当前租户现取最新解密值，取不到退回快照。团队 lead 走 `specForTeam`，工具与必须工具集合都为空。
6. **解析**：`AgentSpecResolver` 把 `toolDetails` 折成 `ToolSpec(toolId, needConfirm)` 列表，把工具与 MCP 的 env 绑定合并成一个扁平 map 作为 `ToolEnvContext` 放进 `contextForTools`（空也注册）。
7. **装配**：`HarnessAgentLauncher.createAgentBase` 按 `beanName` 去重实例化 ToolBox、`init` 注入调用上下文、`addTool` 整组注册，随后按「本 agent 授予的工具名」做一次 `removeTool` 收敛；确认位取工具与绑定的并集生成 ASK 规则，`dangerousInput` 方法名生成装饰。
8. **执行**：模型发起 tool call → 权限引擎按模式与规则判定（危险输入的 `checkPermissions` 在 ASK 判定链上）→ 反射调用 ToolBox 方法 → `execute` 包装内产生 `ToolCallInfo` → `ToolCallLogAdaptorImpl` 落 `tool_call_log` → 返回值交回框架。
9. **收尾**：整轮调用受 `turnTimeoutSeconds` 约束（团队轮次用 team 预算）；单工具无独立超时。

链路上任一环节解析不到工具的行为统一：记 WARN、跳过该工具、agent 继续构建。

## 6. 关键设计决策

### 6.1 一行一个方法

`agent_tool` 的行粒度是 `@Tool` 方法，不是 ToolBox 类。`Toolkit.registerTool(toolBox)` 是整组注册，因此装配侧必须有收敛步：`getToolMeta(bean).methods` 里未被授予的名字全部 `removeTool`。这让「同组里只勾一个方法」成为真实授权语义，约束是收敛逻辑与整组注册绑定，漏一次就会多给工具。

### 6.2 名字即身份

注册身份是 `@Tool.name`，落库唯一键是 `uk_agent_tool_name`。`selectByName` 不带 `active` 条件：占着名字的行（含 `active = 0`）会被更新回 1，而不是新插一行撞键。`bean_name` / `method_name` 因此降级为属性，代码搬方法不会丢绑定。

推论：改名 = 新工具。声明里换 `@Tool.name` 会插入新行，原行按「只增不删」留着；两条记录指向同一个方法时，被交付的是匹配当前声明的那条。

### 6.3 重名在启动期拒绝

同一个名字被两个 `@Tool` 方法声明时没有正确解：谁赢取决于 bean 顺序。`requireUniqueNames` 在任何写库之前抛 `IllegalStateException`，消息逐个列出 `bean::method`，进程启动失败。数据库唯一键兜的是运行期数据，代码期冲突由这道闸兜。

### 6.4 只增不删，交付侧过滤

同步只做插入与更新，从不删行：agent 绑着的工具不能因为一个 Java 方法搬家或一个类下架而消失。声明已离开 classpath 的行由 `registeredToolNames()` 在交付时挡住——行留在表里给管理员看，但不随 spec 下发。`declaredNames` 为空表示同步未运行，此时不过滤，避免把「不知情」当成「零工具」而清空所有 agent 的工具。

### 6.5 生命周期入口唯一

工具行的写入方只有 `BuiltinToolAutoRegistrar`。由此推出三条口径：`status` / `active` 恒为 1（人工停用在数据模型里没有落点）、管理 API 与页面只读、`creator` 固定 `SYSTEM`。任何新写入路径都会破坏「代码是唯一事实来源」这条设计前提。

### 6.6 必须工具不落绑定表

必须工具靠交付时无条件追加实现「关不掉」。副作用是它没有绑定行，也就没有取值处，所以同步遇到 `isRequired` 与 `required = true` 环境参数同时出现时打 WARN 提示互斥——这是提醒而非拒绝，运行时表现为 `require()` 抛「未配置」。

### 6.7 绑定级确认只能加严

运行时判据是 `agent_tool.need_confirm == 1 || ToolSpec.needConfirm`。代码声明为需确认的工具，绑定不能把它关掉；绑定可以给它原本不需要的工具加上确认。同一个方向也体现在保存注释里明确的 OR 语义上。

### 6.8 ToolBox 会话级实例

`createToolBoxInstance` 每次 `newInstance()`，装配侧紧接着 `init(adaptor, SessionMetaContext(...), userIdentifier)`。原因：`ToolBox` 需要持有会话态（谁在调、哪次会话）才能落调用日志，单例跨会话共享会把 `agentId` / `sessionId` 串台。设计契约随之是：ToolBox 必须保留可无参构造的构造函数，且不要在字段里放跨会话共享状态。实例化失败会退回单例并记 ERROR——此时日志归属可能错到别的会话。

### 6.9 环境参数：引用与快照并存，作用域共享

绑定元素可以引用 `env_variable`（`envVarId` + `envVarName` 快照）或自带 `customValue`，交付时引用优先取当前租户的最新解密值、失败退回快照。这让「改一处变量、所有引用生效」和「变量已删也不至于丢配置」同时成立；约束是引用可能与快照偏离，页面显示的快照不等于实际值。

工具看到的 `ToolEnvContext.bindings` 是该 agent 全部工具与 MCP 绑定的合并结果，按 key 回答，不区分来源，且先合工具后合 MCP。因此跨工具复用同名 key 是可行的（一个通用 `API_TOKEN`），同名冲突则是后写入者生效。

### 6.10 工具默认值不参与运行时兜底

`agent_tool_env_param.default_value` 只用于配置页展示与（在 MCP stdio 这类形态上的）填写便利，内置工具的运行时取值只来自绑定值，保存期必填校验也按 `defaultValueCounts = false` 处理。这样「页面写了默认值」不会被误当成「参数已满足」。

### 6.11 超时归装配侧

不存在工具级超时：`agent_tool` 无该列，`@ToolMeta` 无该属性。超时只在 `HarnessAgentWrapper` 的整轮调用上施加，值来自 `harness.turn-timeout-seconds`（团队轮次 `harness.team.turn-timeout-seconds`，并由 `turnBudget(teamRole)` 选择）。理由是同一个回合里工具是串/并交织的，逐工具计时需要一个执行层，而该层归框架；把列留在库里只会给出一个无人读取的数字。慢工具的收敛手段是确认与危险输入拦截，不是超时。

### 6.12 危险输入用装饰器

`dangerousInput` 不引入自定义中间件，而是把已注册的工具换成 `DangerousInputCheckingTool` 包装（`wrapWithDangerousInputCheck`），在 `checkPermissions` 里判入参，命中返回带 `safety:` 理由的 ASK——按 PermissionEngine 契约这类 ASK 在 BYPASS 下也生效。装饰器复制原工具的 name / description / schema 并委托 `callAsync`，对模型完全等价。

去重规则：同一工具若已有 needConfirm 的 ASK 规则，装配跳过包装，因为 ASK 在判定链上先于 `checkPermissions`，扫描不会改变结果。

### 6.13 缺失工具一律可见地跳过

交付与装配两处对「解析不到的工具」都只记 WARN 并继续，没有选择静默程度的开关：`agent_tool_binding` 没有跳过列，SDK 里也没有 `skipIfMissing` 字段。MCP 一侧同口径——`agent_mcp_binding` 同样没有该列，缺失配置按 WARN 跳过，不可达的 server 在 `HarnessAgentLauncher` 的逐个 try/catch 里被丢掉并关闭客户端，agent 带着剩余能力构建成功，末尾再聚合记一条「N of M bound MCP servers」。工具一侧不存在连接失败这一类故障，因此只有前一种表现。

### 6.14 日志只保留

`ToolCallLogMapper` 只有 `insert`，没有查询与删除语句，也没有清理任务：`tool_call_log` 不随 session 或 agent 消失。这个表因此是排障用的原始记录，不是统计来源；平台不提供工具调用页面。落什么内容由开发者传给 `execute` 的显式参数决定，`result` 原样入库，敏感值应在工具方法里避免回显。

## 7. 与 MCP / Skill 的一致性

同一个 agent 的能力装配由三张 `agent_*_binding` 表承担，共用同一套模式：

- **绑定表同构**：`agent_tool_binding` / `agent_mcp_binding` / `agent_skill_binding` 三张表都是 `(agent_id, <target>_id)` 唯一 + `create_time` / `update_time`，工具与 MCP 侧再各带 `env_bindings` JSON；绑定级 `need_confirm` 只有工具一侧有（`agent_mcp_binding` 无此列）。`agent_cli_binding` 是第四张同形状表，同样带 `env_bindings`、同样没有 `need_confirm`。
- **取值形态同构**：工具与 MCP 都用「引用 + 快照」，`customValue` 与 `envVarId` 互斥；Skill 绑定没有环境参数列，因为技能正文不是需要凭据的外部端点。
- **交付同点**：三者都在 `InternalApiController.buildAgentSpecResponse` 里组装 details 列表，并把解析后的 env 值放进同一条 spec；`AgentSpecResolver` 把工具与 MCP 的 env 合并进同一个 `ToolEnvContext`。
- **不可解析项同处理**：MCP 的「不在本租户」「已停用」「stdio 未放行」与工具的「不在声明集」都是留库、不上行、记 WARN。

与两者的核心差异在于事实来源与归属：MCP server 与 Skill 是租户维度的数据行，管理员可以创建、启停、删除；工具是平台维度的代码投影，管理员只能引用。因此 `agent_tool` 无 `tenant_id`、无写接口，而 `mcp_server` / `skill` 保留租户列与完整 CRUD 页面。危险路径与危险命令的判定常量（`ToolDangerousPathConstants`）与权限引擎在 MCP 与工具之间同源，两侧共用一套 ASK/DENY 语义。

## 8. 已知边界

- 工具没有租户维度，一行对所有租户可见。隔离只发生在绑定层：`agent` 属于某租户，`agent_tool_binding` 随 agent 走。
- 工具行永不消失，表会随代码声明的累积变长；工具页面按 `active = 1` 全量列出，其中可能包含已不下发的名字。
- 工具级超时、重试、并发上限均无配置位；`@Tool.concurrencySafe` 是代码属性，不落库、不可按 agent 调整。
- ToolBox 的会话级实例化依赖无参构造，回退单例的分支存在跨会话日志串味的可能。
- `ToolEnvContext` 是 agent 级扁平 map，工具能读到同 agent 其他绑定的同名 key；这是共享机制，不是按工具隔离。
- 必须工具与必填环境参数互斥，同步只 WARN 不拒绝启动。
- 团队 lead 不装配业务工具与必须工具，且 `specForTeam` 显式传空工具集；lead 的可见能力只有团队工具组。
- `ToolSpec.toolName` 在装配路径上未被读取，工具名一律以库行与 `@Tool.name` 为准。
- 调用日志只写不读，敏感入参若被工具显式传给 `execute` 会原样入库。
- `AgentToolController` 的 `/builtin` 不带 `status` 条件、`/available` 带 `is_required = 0`：两个列表口径不同，前者用于展示，后者用于选择。

## 9. 关键文件索引

| 主题 | 路径 |
| --- | --- |
| SDK 契约 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvContext.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolCallContext.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMetaDescriptor.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolSpec.kt` |
| 注册中心与 SPI | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolCallLogAdaptor.kt`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolConfigAdaptor.kt` |
| 内置工具与模块声明 | `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt`、`harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/EmailToolBox.kt`、`harnax-tools-external/harnax-tools-buildin/pom.xml`、`harnax-agent/harnax-tools-sdk/pom.xml`、`harnax-admin/pom.xml`、`harnax-agent/harnax-agent-service/pom.xml` |
| 元数据同步与只读 API | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/AgentToolService.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt` |
| 绑定保存与交付 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolConfig.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentToolResponse.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolEnvParamEntry.kt` |
| 运行时装配 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxes.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt` |
| spec 解析与 SPI 实现 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt`、`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolConfigAdaptorImpl.kt`、`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolCallLogAdaptorImpl.kt` |
| 实体与 SQL | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolBinding.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolEnvParam.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolCallLogEntity.kt`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentToolMapper.kt`、`harnax-entity/src/main/resources/mapper/AgentToolMapper.xml`、`harnax-entity/src/main/resources/mapper/ToolCallLogMapper.xml` |
| DDL | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`（`agent_tool` 与 `tool_call_log` 的建表、`agent_tool.is_required` 的 `NOT NULL DEFAULT 0`、`uk_agent_tool_name`、`agent_tool_binding` 的 `uk_agent_tool_binding_agent_id_tool_id`、`tool_call_log` 的 `tenant_id` 与 `idx_tenant_ts` 都写在这一个基线里） |
| 前端 | `harnax-webui/src/pages/tool/index.tsx`、`harnax-webui/src/pages/tool/components/ToolEnvEntriesEditor.tsx`、`harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx`、`harnax-webui/src/services/ant-design-pro/tool.ts` |
| 框架侧注解与引擎（外部依赖 agentscope 2.0.2） | `io.agentscope.core.tool.Tool`、`io.agentscope.core.tool.ToolParam`、`io.agentscope.core.tool.ToolSchemaGenerator`、`io.agentscope.core.tool.ToolMethodInvoker`、`io.agentscope.core.tool.ToolDangerousPathConstants`、`io.agentscope.core.permission.PermissionMode` |
