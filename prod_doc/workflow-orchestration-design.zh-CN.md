# Agent 编排工作台设计（Orchestration Workbench）

版本口径：harnax 大版本功能，首版范围 A 档。
本文是落地设计规格，含数据模型、DSL、执行语义、接入点、接口清单、前端工作台、测试判据与部署运维。文中代码锚点为仓库相对路径加行号，均可在当前 `kotlin-dev` 检出上定位。

## 1. 定位：编排是 Agent 的一种驱动方式

harnax 已有的 Agent 由模型自主决定下一步（agentscope 的工具循环、团队的自由委派）。编排提供第二种驱动方式：**用一张可视化流程图为 Agent 定义行为骨架**，运行时按图执行，输出仍然是一段对话回复。

三句话口径：

- 编排的对象是 Agent，不是「应用」。画布产出的是一个可复用的流程定义，它必须绑定到 Agent 才会被执行；没有独立的流程应用、没有独立的对外 API 出口、没有独立 Web App。
- 触发方式一条都不新增。用户仍从会话页/渠道/定时任务跟 Agent 说话，图只是这个 Agent 本轮怎么跑的内在机制。
- 图语义收敛为 Agent 语义的组件。面板上没有「HTTP 节点」「代码节点」这类通用自动化组件，只有「推理步骤」「判断」「能力调用」；HTTP 是「能力调用」的一种来源，与内置工具、MCP 工具并列。

这条定位把它和 Dify 类编排器分开：别人是「编排一个应用」，这里是「把 Agent 的行为写成流程」。产品叙事上，编排与团队（team）平级——团队是「多人协作、模型决定谁做」，编排是「流程确定、人决定怎么走」，两者共同补全「自主推理」之外的两种可控形态。

首版（A 档）边界：

| 维度 | A 档做 | A 档不做 |
| --- | --- | --- |
| 图形状 | 外层 DAG + 分支 | 容器子图（迭代、循环） |
| 并行 | 同节点多出边的分支并行、多入边合流 | 数组元素级并行 |
| 变量 | 节点输出、环境变量、输入变量、会话历史窗口 | 会话变量写入（跨轮可变的流程状态） |
| 错误 | 默认值兜底、节点重试 | 失败分支（fail-branch）双 handle |
| 交互 | 一次性跑到结束、执行中可等待工具确认 | 跨重启的暂停续跑（快照与恢复） |
| 观测 | 运行记录 + 节点执行记录 + 试跑逐节点高亮 | 会话流里的逐节点事件 |

## 2. 现状底座与两个缺口

编排不需要新服务，因为一次图执行要用的东西都在 agent-service 进程里。已核实的可复用件：

| 能力 | 锚点 | 编排怎么用 |
| --- | --- | --- |
| 对话入口（流式与批式） | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:179`、`:143` | 图执行是同位置的另一条分支，入参出参不变 |
| Agent 装配分流点 | 同上 `:832`（`buildAgent` 先判 team 再判单 Agent） | 编排走执行期分流，不进这条装配链 |
| 规格下发 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/AgentSpecInfoResponse.kt:25` | 加两个字段把流程带给运行时 |
| 前缀路由 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:329`（`web-`/`mp-`/`chn-`/`task-`） | 编排不改前缀集合，绑定关系由 agent 行解析 |
| 模型调用 | `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/model/ModelHelper.kt:30` | 推理步骤与意图分流的执行体 |
| 模型配置解析（可脱离会话规格） | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ChatModelConfigAdaptorImpl.kt:32` | 按 `modelId` 取配置，DB 兜底分支在同文件 `:45`；试跑与节点级覆盖模型靠它 |
| 会话历史读写 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/MysqlSessionMessageStore.kt:89`、`:168` | 图执行用同一个 store 读历史、写回本轮 |
| 工具注册与实例化 | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:61`、`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt:30` | 能力调用节点的内置工具分支 |
| MCP 客户端构建 | `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpHelper.kt:133` | 能力调用节点的 MCP 分支 |
| 逐成员事件合流范式 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:224`（`withMemberEvents`） | 试跑通道的多路事件合成照这个形状写 |
| 中断 | 同上 `:314`、`:323`（`stopExecution` 以 `activeStreams`/`activeCalls` 为 live 判据） | 图执行必须登记到同一条 live 判据上 |
| 流事件契约 | `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:33`、`:210` | 图执行的会话输出映射到这 8 个既有事件，不新增类型 |
| Token 记账 | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:738`（`token_stats`） | 每个模型节点一行，列已够，首页总览自动吃进去 |

两个真实缺口：

1. **没有「按工具名直调」的入口。** `ToolRegistry` 只能造实例（`createToolBoxInstance(beanName)`），实际调用由 agentscope 反射 `@Tool` 方法完成，运行侧没有对外暴露这条反射路径。搜索 `invokeTool`/`callTool`/`executeTool`/`ToolExecutor` 在 main 代码零命中。能力调用节点需要新建一个执行器（即「节点清单」一节里的 `CapabilityInvoker`）。
2. **技能只有装载没有执行。** `SkillAdaptor.getSkill(skillId)` 返回定义后由 harness 投影进沙箱，没有「跑一次技能」的抽象。所以 A 档不设技能节点，避免造一个只能半用的组件。

另有一处约束会影响实现方式：**agent-service 的主数据源就是 admin 库**（`harnax-agent/harnax-agent-service/src/main/resources/application.yml:10` 指向 `harnax_admin`），会话消息在另一个库（同文件 `:32` 指向 `agentscope`）。因此运行记录由 agent-service 直接经 `harnax-entity` 的 Mapper 落 `harnax_admin`，与 `TokenStatAdaptorImpl` 现有的写法同构，不需要走 admin 内部 API 转发。

## 3. 总体架构

```
harnax-webui  编排工作台（画布、配置面板、试跑、运行历史）
     │  REST（定义/发布/绑定/历史）      SSE（试跑）
     ├──────────────► harnax-admin ◄───────────────┐
     │                 │  内部 API：把已发布流程       │
     │                 │  随 agent 规格下发            │
     ▼                 ▼                             │
harnax-session-router ──► harnax-agent-service       │
   （会话路由，不改）        │                        │
                            ├─ HarnessAgent 路径（自主/团队，现状）
                            └─ WorkflowExecution 路径（新增，按图执行）
                                     │
                        harnax-workflow（新 Maven 库模块，编译期校验＋图执行引擎）
                                     │
                     ModelHelper ────┼──── ToolInvoker ──── McpHelper
                     （模型流式）     │    （内置工具反射调用）
                                MysqlSessionMessageStore（历史）
                                WorkflowRunMapper（运行记录 → harnax_admin）
```

模块与服务归属：

- 新增 Maven 模块 `harnax-agent/harnax-workflow`，与 `harnax-harness-core` 同级，依赖 `harnax-entity`、`harnax-agent-utils`、`harnax-tools-sdk`、`harnax-protocol`。它只含图模型、编译校验、执行引擎与节点接口，不含 Spring Web 层。
- 执行仍在 **agent-service 进程内**。理由：推理步骤要用 `ModelHelper` 与 provider 凭据、能力调用要用 `ToolRegistry`/`McpHelper`、历史要读会话库、输出要发 `ChatEvent`——这四件全在 agent-service。做成独立服务等于把这四件事各开一条 HTTP 接口并重做一遍凭据下发，而 `harnax-deploy/docker-compose.yml` 已经挂着 10 个服务（`mysql`、`redis`、`minio` 三个基础设施加 `admin`、`router`、`scheduler`、`agent-service`、`channel-service`、`mcp-server`、`frontend` 七个应用侧），再加一个就多一套端口、密钥、健康检查与排障面，收益为零。
- admin 只做定义面：流程 CRUD、发布与版本、绑定校验、运行历史查询。它不执行图（`harnax-admin/pom.xml` 不依赖 `harnax-harness-core`，这条边界现状已经存在，保持）。
- router、channel、scheduler、iOS **零改动**。它们在链路上只见到 `ChatEvent`，图执行的输出仍走同一条会话流（见「与既有链路的接入」）。

分流方式取「执行期分流」而不是「装配期分流」：`DefaultAgentRunner.streamProcess`/`process` 在拿到规格后判断 `orchestration`，为 `workflow` 时交给 `WorkflowExecution`，不再构造 `HarnessAgentWrapper`。原因：图执行没有工具循环、没有沙箱、没有 MCP 长连接要释放，硬塞进 `agentCache`（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:78` 那套 30 分钟 TTL、`pendingRelease` 延迟释放）会凭空带上一堆生命周期负担。代价是原本由 agentCache 提供的两条保护要在图侧等价重建：同一会话同时只允许一个在跑的图执行，以及运行句柄必须登记进 `stopExecution` 认的 live 判据。

## 4. 数据模型

五张表落在 admin 库（`harnax_admin`），按现有规约折进单份基线 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`，并同步 `harnax-entity/src/test/resources/schema-test.sql` 的逐字副本（漂移由 `SchemaBaselineDriftIT` 守，该 IT 只在 `-Pintegration-test` 跑，所以两份要人肉对齐）。

### workflow：流程定义与草稿

| 列 | 类型 | 语义 |
| --- | --- | --- |
| `id` | bigint AI | 主键 |
| `tenant_id` | bigint NOT NULL DEFAULT 1 | 归属租户 |
| `name` | varchar(100) NOT NULL | 流程名，租户内唯一 |
| `description` | text | 用途说明 |
| `graph_draft` | mediumtext | 草稿图 JSON（画布直接编辑的对象） |
| `env_vars` | text | 环境变量 JSON 数组，值可为密文列引用 |
| `dsl_version` | varchar(16) NOT NULL DEFAULT '1' | 定义结构版本，迁移判据 |
| `published_version_id` | bigint | 当前生效版本，NULL 表示从未发布 |
| `owner` | varchar(100) | 负责人 |
| `is_public` | tinyint DEFAULT 0 | 租户内共享（0 私有、1 共享），沿用模型域已定稿口径 |
| `status` | tinyint DEFAULT 1 | 启用/停用 |
| `creator` | varchar(100) | 创建人 |
| `active` | tinyint DEFAULT 1 | 软删 |
| `active_name` | varchar(100) GENERATED | `if(active=1,name,NULL)`，与 `agent` 表同形 |
| `create_time`/`update_time` | datetime | 同现例 |

索引：`PRIMARY (id)`、`UNIQUE (tenant_id, active_name)`、`KEY (tenant_id, status)`。
`graph_draft` 用 `mediumtext` 而不是 MySQL `json` 类型，与 `skill.skillmd`、`mp_chat_message.segments_json` 一致：全仓没有用过原生 `json` 列，JSON 一律在应用层序列化。

### workflow_version：发布快照

`id`、`tenant_id`、`workflow_id`、`version_no` int、`graph` mediumtext（发布时刻的图，不可变）、`env_vars` text（发布时刻的环境变量）、`release_note` varchar(500)、`dsl_version`、`creator`、`create_time`。
唯一键 `UNIQUE (workflow_id, version_no)`。
只写不改：发布插入新行，回滚是把 `workflow.published_version_id` 指回旧行，不删版本。

### agent_workflow_binding：Agent 与流程的绑定

`id`、`tenant_id`、`agent_id`、`workflow_id`、`version_id`（NULL 表示跟随该流程的 `published_version_id`，非 NULL 表示钉在某个版本，用于灰度与回滚）、`create_time`、`update_time`。
唯一键 `UNIQUE (agent_id)`：一个 Agent 至多一张骨架图；一张流程可被多个 Agent 绑定复用。

### workflow_run：一次执行

`id`、`tenant_id`、`workflow_id`、`version_id`、`agent_id`、`session_id` varchar(255)、`triggered_from` varchar(16)（`web`/`mp`/`chn`/`task`/`dry_run`）、`status` varchar(16)（`running`/`succeeded`/`failed`/`stopped`/`timeout`）、`inputs` mediumtext、`outputs` mediumtext、`error` text、`total_steps` int、`total_tokens` bigint、`elapsed_ms` bigint、`creator` varchar(100)（执行用户）、`create_time`、`finished_at`。
索引：`KEY (tenant_id, workflow_id, create_time)`、`KEY (session_id)`、`KEY (status, create_time)`（供孤儿运行对账扫）。

### workflow_node_execution：节点执行明细

`id` bigint AI、`tenant_id`、`run_id` bigint、`node_id` varchar(64)、`node_type` varchar(32)、`title` varchar(100)、`seq` int（本次执行的第几步）、`status` varchar(16)（`succeeded`/`failed`/`skipped`/`running`）、`inputs` mediumtext、`outputs` mediumtext、`error` text、`elapsed_ms` bigint、`total_tokens` bigint、`create_time`。
索引：`KEY (run_id, seq)`、`KEY (tenant_id, run_id)`。

### 不变量

1. **每条 SQL 自带 `tenant_id` 谓词。** admin 没有 MyBatis 租户拦截器，租户安全只能靠 SQL 形状；`SkillMapper.selectByIds` 缺租户条件已经踩过两次，这五张表的 Mapper 每一条（含按主键取、按 `run_id` 取明细）都要带 `tenant_id`。
2. **绑定即校验。** 写 `agent_workflow_binding` 时拒绝三种情况：目标 Agent 是团队（team）成员或被团队委派（编排与委派语义互斥，首版不做嵌套）；`version_id` 不属于 `workflow_id`；流程未发布却建绑定。
3. **图内引用不跨租户。** 能力调用节点引用的 `toolId`/`mcpId`、推理步骤引用的 `modelId` 必须与流程同租户，发布时逐个核。
4. **运行记录不阻断执行。** 落库失败只记 warn，图继续跑完，与技能装载「不致命」的既有口径一致。
5. **草稿与发布态物理分离。** 画布只写 `graph_draft`，执行只读 `workflow_version.graph`；试跑是唯一读草稿的路径。

## 5. 图定义 DSL

`graph_draft` 与 `workflow_version.graph` 存同一形状的应用层 JSON，用缩进树表达：

```
graph
  dslVersion: 1
  nodes: [ { id, type, version, title, position, data } ]
  edges: [ { id, source, sourceHandle, target } ]
  viewport: { x, y, zoom }
  envVars: [ { key, valueType, value, secret } ]
  entryNodeId: string
```

- `id` 由画布生成且永不复用（`n_7f3a` 形状）；`position`/`viewport` 纯展示，执行引擎完全不读；`version` 是节点结构版本，与节点注册表配对。
- `sourceHandle` 在 A 档只有两种取值：普通节点为空串，判断/意图分流的出边用分支 id（`branch-0`、`branch-1`…）。**A 档不出现在同一条出边上叠失败分支 handle**，那是 fail-branch 的语义，留到 B 档，因为 handle 形状改动会连带牵动校验器、前端连线与合流判定。
- `envVars` 的 `secret=true` 项存列名引用而非明文，沿用 admin 现有的 `AesUtil`/`SecretFieldEncryptor` 与 `_enc text` 列做法。
- 定义文件支持导出/导入 YAML（`kind: workflow`、`app`、`graph`），导入即走一次发布级校验。**不做 Dify DSL 兼容**：节点语义、字段名和变量引用语法都不同，做转换只会得到一个四不像；`dslVersion` 字段的存在是为将来自己升版本留的迁移位。

### 变量与类型

文本模板里的引用语法为 `${nodeId.field}`，字面量要输出时用 `\${` 转义。非文本入参（如能力调用的结构化参数、判断节点的比较左值）在 `data` 里存 selector 数组 `["n_7f3a","text"]`，执行时直接取值，不做文本解析。类型集合：`string`、`number`、`boolean`、`object`、`array[string]`、`array[object]`。文件类型 A 档不做。

四层作用域：

| 层 | 生命周期 | 写入方 |
| --- | --- | --- |
| 输入变量 | 一次执行 | 开始节点（会话消息、附件计数、定时任务参数） |
| 节点输出 | 一次执行 | 每个执行完的节点 |
| 环境变量 | 跨执行、流程级 | 定义面配置 |
| 会话历史 | 跨轮、会话级 | `MysqlSessionMessageStore`，由开始节点的窗口开关暴露 |

历史不是自动进图的：推理步骤默认只拿本轮输入与它自己 prompt 里引用的变量。要带上文，开始节点上有「携带会话历史」开关与窗口（最近 N 轮，默认 10），打开后引擎在 `sys.history` 里给出 `List<Msg>`，只有推理步骤与意图分流能引用它（其余节点拿到会被校验拒绝）。这样「上下文从哪来」在图上看得见，而不是运行时偷偷塞。

### 校验规则

编译期（保存时软校验给告警，发布时硬校验给拒绝）逐条按序跑，错误码进 admin 的信封返回：

| 码 | 判据 |
| --- | --- |
| `WF_NODE_ID_DUP` | 节点 id 重复 |
| `WF_EDGE_ENDPOINT_MISSING` | 边的 source/target 无对应节点 |
| `WF_EDGE_HANDLE_INVALID` | 判断/分流节点的 `sourceHandle` 不在其分支集合内 |
| `WF_ROOT_INVALID` | 入口节点不是 `start`，或存在多个 `start` |
| `WF_CYCLE` | 拓扑排序失败（外层必须 DAG） |
| `WF_UNREACHABLE` | 从入口不可达的孤立节点 |
| `WF_VAR_UNKNOWN` | 变量引用的节点 id 不在图内，或不在拓扑上游 |
| `WF_VAR_TYPE_MISMATCH` | 引用字段类型与入参声明不兼容 |
| `WF_REF_FOREIGN` | 引用的 tool/mcp/model 不属于本租户或不存在 |
| `WF_BRANCH_EMPTY` | 判断分支没有可达出口 |
| `WF_NODE_VERSION_UNSUPPORTED` | `(type, version)` 不在注册表内 |

## 6. 节点清单（A 档）

面板只露六类，命名一律用 Agent 语境的词，避免出现通用自动化编排器的既视感。

| 面板名 | `type` | 输入 | 输出 | 执行落点 |
| --- | --- | --- | --- | --- |
| 开始 | `start` | 会话消息、附件、定时任务入参；历史窗口开关 | `text`、`history`、`params.*` | 引擎内置，无外部调用 |
| 推理步骤 | `llm` | `prompt` 模板、`modelId`（缺省跟随绑定 Agent）、`temperature`、`jsonSchema` 可选 | `text`；带 schema 时另出 `json` | `ModelHelper.createChatModel`，流式透传 |
| 判断 | `if_else` | 条件组（and/or 两层）、每组的左值 selector、操作符、右值 | 无输出，只选出边分支 | 引擎内置比较器 |
| 意图分流 | `intent` | 待判文本 selector、分类项列表、兜底分支 | `intent`、`confidence` | 一次无工具的模型调用，强制结构化输出 |
| 能力调用 | `capability` | `source`（`builtin`/`mcp`/`http`）、目标引用、参数映射、超时、`needConfirm` 继承 | `result`、`ok`、`error` | 三种来源各自的执行器，见下 |
| 变量聚合 | `aggregate` | 一组互斥分支的同名输出 selector | `value`（取第一个非空） | 引擎内置 |
| 变量拼装 | `compose` | 一个模板 + 输出 schema 选择 | `text` 或 `json` | 引擎内置插值器 |
| 结束 | `reply` | 输出模板（可引用多个变量）、附件说明 | 会话最终回复文本 | 映射为 `TextEvent(isLast)` 与 `EndEvent` |

设计上的三个取舍：

- **HTTP 不是独立节点。** 它是 `capability.source = http` 的一个来源，配置项与内置工具同栏（方法、URL、头、体、超时、响应取值路径）。这一步是「不看着像 Dify」在节点层面最关键的动作：Dify 把 HTTP 摆成一块通用自动化积木，而这里它是 Agent 伸出外部的一次能力调用，与调用一个内部工具形态一致。
- **不做模板脚本。** `compose` 只有插值（`${}` 展开 + 数组 join），没有 Jinja/条件/循环。仓内没有模板引擎依赖，为一个插值需求引入 Jinja 不划算，且逻辑一旦能写就变成「代码节点」的入口，与 A 档边界冲突。真有加工需求用推理步骤或把逻辑沉进工具。
- **技能节点与自主片段留后。** 前者缺「跑一次技能」的抽象，后者一个节点就吃掉整个 harness 装配面（工具、沙箱、迭代、确认），两者都是 B 档的事，A 档不预留 handle。

能力调用节点的三个执行器要在 `harnax-workflow` 之外由 agent-service 提供实现（因为要用到 registry、MCP、凭据），以 `fun interface` 形式注入引擎，与 `harnax-harness-core` 现有的 `ChatModelConfigAdaptor`/`SkillAdaptor` 那套扩展点同构：

```
fun interface LlmInvoker      { suspend fun invoke(req: LlmRequest): Flow<LlmChunk> }
fun interface CapabilityInvoker { suspend fun invoke(req: CapabilityRequest): CapabilityResult }
fun interface HistoryProvider  { suspend fun load(sessionId: String, userId: String?, rounds: Int): List<Msg> }
fun interface RunSink          { fun emit(event: WorkflowEvent) }
```

## 7. 执行引擎

### 编译与调度

`GraphCompiler` 把 `graph` JSON 变成 `CompiledGraph`：节点表、邻接表、入度表、拓扑序（同时用于环检测与变量上游可达性判断）、`(type, version) → NodeHandlerFactory` 的注册表查找结果。注册表按 Dify 的经验用字符串键加版本号，新增节点只加一个 handler 类，执行核心不改。

执行语义：

- **就绪判定**：一个节点的入边全部到达（taken 或 skipped）才可运行；无入边的入口节点直接就绪。这是 AND-join 的唯一定义，不引入「等待全部上游」与「任一上游」两种 join 语义。
- **分支并行**：同一节点的多条出边同时 taken 时，这些子图并发执行（协程，`Dispatchers.IO` 有界线程池），并行度上限取配置项（默认 4）。
- **skipped 传播**：判断节点只选一条分支，未取的分支子图整块标 `skipped`；`aggregate` 的入边允许部分 skipped。这条与 Dify 一致，也是 `WF_BRANCH_EMPTY` 要在发布时拦住的原因。
- **合流后继续**：多条分支汇入同一节点时，该节点在所有入边到齐后跑一次，其可见变量为已 taken 上游的输出并集。

变量池 `VariablePool` 是执行级独占对象（每次 run 一个，非线程共享状态：写入只在节点完成时发生一次，读取由就绪节点串行取），节点输出按 `(nodeId, field)` 落入，写前先按 handler 声明的 `OutputVariableType` 校验形状。

### 事件流

引擎内部发 `WorkflowEvent`，与 `ChatEvent` 完全解耦：

```
GraphStarted / GraphFinished / GraphFailed
NodeStarted / NodeStreamDelta / NodeFinished / NodeSkipped / NodeFailed
RunUsage
```

会话内的执行由 `WorkflowExecution` 把这套事件降维成既有 `ChatEvent`（见「与既有链路的接入」），试跑通道把这套事件原样编码成 SSE 给画布做逐节点高亮。两者是两个订阅者，引擎不知道具体协议，这样二期把节点事件推进会话流时不动引擎。

### 限额、超时、错误与中断

| 项 | 默认 | 说明 |
| --- | --- | --- |
| 单次执行最大步数 | 60 | 超出即 `GraphFailed`，`status = timeout` |
| 单次执行最大时长 | 600s | 与会话链路的异步超时对齐后再取值，配置项给 admin 与 agent-service 各一份 |
| 节点超时 | 120s，能力调用可覆盖 | 到点标 `failed`，走节点的错误策略 |
| 同会话并发图执行 | 1 | 第二次进来直接拒绝，回复沿用 `RESOURCE_LOCKED` 那套文案形状 |
| 单实例并发图执行 | 16 | 超出的请求排队而不是拒绝 |
| 节点重试 | 默认关，可配 1–3 次、指数退避 | 只重试幂等来源（`http` 的 GET、`builtin` 且 `readOnly` 的工具） |
| 错误策略 | `none` / `default_value` | 失败即整图失败，或用声明的默认值继续 |

失败语义要说清两条：模型 4xx/5xx 与超时都算节点 `failed`，走节点策略；只有校验类问题（变量缺失、类型不符）才是 `GraphFailed` 且必带 `WF_` 码，因为它是定义错误而不是运行错误。批式路径（`/api/agent/chat` 非流式）与流式路径共用同一个引擎实例，批式只是把事件流聚合后返回，与 `AgentRunner` 现有的「非流内部收集流再聚合」做法一致。

中断：`WorkflowExecution` 开始时把运行句柄登记进 `DefaultAgentRunner` 认的 live 判据（同文件 `activeStreams`/`activeCalls` 那两张表，或一个并列的 `activeRuns` 并在 `stopExecution` 里多查一次），`/stop` 到达时取消协程并把 run 标 `stopped`、未完成节点标 `skipped`。`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:336` 的注释已经写明「缓存命中不等于有活在跑」，图执行同样不能拿运行过就算 live。

对账：agent-service 重启会留下 `status = running` 的孤儿 run。启动期一次性扫描把本实例（`instance_id` 列复用 `agent_task_execution` 的做法）遗留的 running 标 `timeout`，另外由一个每 5 分钟的清理任务兜住跨实例的漏扫。这条与调度域已有的 reconciler 同形，实现代价很小，但没有它运行历史页会长期挂着假在跑。

## 8. 与既有链路的接入

### 规格下发

`AgentSpecInfoResponse` 增两字段，缺省值保证对旧运行时二进制兼容：

```
orchestration: String = "react"          // react | workflow
workflowSpec: WorkflowSpecDto? = null     // graph、versionId、workflowId、dslVersion、envVars
```

admin 侧 `resolveFromSession`/`resolveFromChannel`/`resolveFromTask` 三条既有解析路径都在取到 agent 行之后补一次绑定查询（`agent_workflow_binding` join `workflow_version`），因此三种前缀（`web-`/`mp-`/`chn-`/`task-`）自动全覆盖，前缀路由表不动。

### 会话内输出映射

图执行不发任何新的 `ChatEvent` 类型，全部落在既有 8 个里：

| 图的产出 | 会话事件 |
| --- | --- |
| 推理步骤的流式增量 | `TextEvent(isLast = false)` |
| 能力调用的开始与结果 | `CallToolEvent`、`ToolResultEvent` |
| 结束节点的回复 | `TextEvent(isLast = true)` |
| 整图失败 | `ErrorChatEvent`（复用 `WF_` 码） |
| 收尾 | `EndEventChatEvent` |

这么做的原因很实际：`ChatEvent` 是 Jackson 多态类型，`@JsonSubTypes` 的判别名在**反序列化侧**必须已注册（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:23`），而主检出里另有一份独立的多态定义给 SDK 用（`harnax-client/harnax-client-common/src/main/kotlin/com/agnetix/harnax/client/dto/ChatEvent.kt:17`，由 `JdkSseStreamReader` 与 `SseEventParser` 消费）。也就是说流里有四方会解 `eventType`：session-router（`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt`）、channel-service、harnax-client SDK、iOS（`harnax-ios/Sources/HarnaxCore/Contract/ChatEvent.swift:176` 按 `eventType` 分八个分支解码）。新增一种 eventType 若只升 agent-service，旧版 channel-service 与旧版 SDK 遇到未知判别名会直接反序列化失败，飞书渠道当场断。日常开发是单服务重启（部署策略已定稿），协议扩展在这个拓扑下就是跨服务锁步升级。首版把节点级进度只放在编排页自己的试跑流上，会话流零风险。二期真要往会话里推节点进度，再加一个默认关的开关，开启前置条件写清「上述四方均已升级」。

### 工具确认走的是同一条回路

`ConfirmAgentRequest` 现在按会话找回执行体：`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:376` 的 `confirm` 先处理 `childRunId`（团队成员的确认），否则 `cachedAgent(sessionId, …)` 取缓存的 Agent 并把答案交给它挂起的工具调用。图执行没有缓存 Agent，所以 `confirm` 必须同样按 `orchestration` 分流：活运行句柄在图侧的运行登记表里按 `sessionId` 找，答案直接回灌给等待中的能力调用节点。这条不改协议、不改前端，但不接上就会表现为「点了确认没反应」，所以它和中断一样属于必须显式接线的一处，「测试与验收」一节的执行链路 IT 里有对应用例。

图侧对确认的继承规则：能力调用节点没有自己的确认开关，直接沿用工具定义里的 `needConfirm` 与 `dangerousInput`（`agent_tool` 与 `@ToolMeta` 已有的那两个口径），画布上只读展示「该动作需用户确认」。理由是确认是安全属性而不是编排属性，图上给一个能把它关掉的字段等于给了绕过入口。

### 历史与记账

- 读：`MysqlSessionMessageStore.load(userId, sessionId)`，按开始节点窗口截断。
- 写：一轮跑完写回两条 `Msg`（用户本轮、图最终回复），id 必填（该 store 对无 id 的消息直接跳过，见 `MysqlSessionMessageStore.kt:117`），沿用 upsert 语义，`deleteSession` 那条清理路径自动成立。
- 记账：每个 LLM/意图分流节点一行 `token_stats`，`agent_id` 用绑定 Agent、`chat_model_id` 用节点实际生效模型、`tenant_id` 用规格里的租户，`total_tokens` 汇总写进 `workflow_run`。**不给 `token_stats` 加列**，首页总览与既有聚合 SQL 不改就吃得到图执行的用量；要按流程维度看，就查 `workflow_run`，不要在用量表里塞两套归属。
- 会话页的上下文占用读数、`/compact`、记忆、沙箱这些能力在图模式下语义上不适用（没有 agent 实例的上下文窗口可言），入口按现有形状返回「该 Agent 由流程驱动」的说明而不是报错——这条要在实现时对着 `loadContextUsage` 与命令处理分支逐条落。

### 试跑通道

```
POST /api/agent/workflow/dry-run        body: { workflowId, inputs, historyHint? }
                                       resp: text/event-stream，WorkflowEvent 原样编码
```

- 走 session-router 转发，但不落 `session` 表、不写 `session_message`（`triggered_from = dry_run`）。
- 图从 admin 内部接口按 `workflowId` 取**草稿**（`GET /api/admin/internal/workflows/{id}/draft`，带租户校验），前端不提交图，避免把未发布定义直传执行体并绕开租户判断。
- 模型配置由 `ChatModelConfigAdaptorImpl.getConfig(modelId)` 的 DB 兜底分支解析（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ChatModelConfigAdaptorImpl.kt:32` 取方法、`:45` 是兜底分支），因此试跑不需要会话级规格上下文。
- 试跑同样写 `workflow_run`（`triggered_from = dry_run`）与节点明细，编排页的运行历史由此而来。

## 9. admin 侧接口与发布

前缀沿用 `/api/admin/**`，鉴权沿用现有登录态与租户上下文，业务错误一律走现有信封（HTTP 200 + `code`，非 2xx 只有 401）。

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/api/admin/workflows/page` | 分页列表（租户谓词 + 名称/状态/负责人筛选） |
| GET | `/api/admin/workflows/{id}` | 详情含草稿图 |
| POST | `/api/admin/workflows` | 新建（名称租户内唯一） |
| PUT | `/api/admin/workflows/{id}/graph` | 存草稿，软校验：返回告警列表不拒绝保存 |
| POST | `/api/admin/workflows/{id}/validate` | 发布级硬校验，逐条错误码 |
| POST | `/api/admin/workflows/{id}/publish` | 生成版本行并置为生效版本 |
| GET | `/api/admin/workflows/{id}/versions` | 版本列表 |
| POST | `/api/admin/workflows/{id}/rollback` | 把生效版本指回某历史版本 |
| GET | `/api/admin/workflows/{id}/export` | YAML 导出（指定版本或草稿） |
| POST | `/api/admin/workflows/import` | YAML 导入，导入即硬校验 |
| DELETE | `/api/admin/workflows/{id}` | 软删，有绑定时拒绝并回绑定清单 |
| PUT | `/api/admin/agents/{agentId}/workflow-binding` | 绑定/改版本/解绑（`versionId` 可为 null） |
| GET | `/api/admin/workflows/{id}/runs/page` | 运行历史（列表 + 汇总） |
| GET | `/api/admin/workflow-runs/{runId}` | 单次运行含节点明细 |

Controller/Service/Mapper/DTO 的分层与命名照现例（`SkillController` 那条链：实体 → Mapper 接口 → `harnax-entity/src/main/resources/mapper/*.xml` → Service 接口与 impl → Controller → `dto/` 请求响应类），动态查询写 XML 不写注解，多参数方法一律带 `@Param`。

## 10. 前端工作台

### 依赖

新增 `@xyflow/react`（React Flow v12，MIT）。选它而不是 `@antv/X6` 或未声明的 G6：节点就是 React 组件，配置面板、试跑高亮、内联编辑都能用现有 antd 组件写；X6 的命令式 API 在自定义节点上要额外搭一层；G6 v5 目前只是 `@ant-design/charts` 的传递依赖，没进 `package.json`，拿它当编辑器等于把核心交互挂在一个随时可能因依赖收敛而消失的副本上。

工程没有图编辑库，`@xyflow/react` 与 React 18、antd 5 共存无冲突（其样式自带作用域，仅需在画布容器上引 `style.css`）。

### 页面与文件

一级菜单 `agent` 下新增子页 `orchestration`，与 `management`/`team`/`session`/`task` 平级（`harnax-webui/config/routes.ts:32` 那个分组）：

```
config/routes.ts                                  增一条 name: 'orchestration'
src/locales/{en-US,zh-CN}/menu.ts                 menu.agent.orchestration
src/locales/{en-US,zh-CN}/pages.ts                pages.orchestration.* 一块
src/services/ant-design-pro/workflow.ts           函数式 request<API.Result<T>>
src/typings.d.ts                                  Workflow / WorkflowRun / WorkflowGraphNode 类型
src/pages/orchestration/index.tsx                 流程列表（PageContainer + ProTable）
src/pages/orchestration/canvas.tsx                画布主页（hideInMenu，/agent/orchestration/:id）
src/pages/orchestration/components/NodePanel.tsx  左侧可拖入的组件栏
src/pages/orchestration/components/NodeConfig.tsx 右侧配置抽屉
src/pages/orchestration/components/VarPicker.tsx  变量选择器
src/pages/orchestration/components/RunPanel.tsx   底部试跑与结果
src/pages/orchestration/components/RunHistory.tsx 运行历史与逐节点详情
src/pages/orchestration/graph/                    纯函数：图⇄xyflow 转换、校验、拓扑与高亮状态
```

`typings.d.ts` 与 `services/ant-design-pro/typings.d.ts` 有两份 `declare namespace API`，新类型只挂前者（技能域已收敛成一处，跟新的那份走）。

### 交互

- 组件栏六个入口，按「开始 → 推理 → 判断 → 能力 → 加工 → 结束」顺序，图标沿用 antd 图标集，不引入第三方图标字体。
- 画布连线即建边，判断/分流节点的每条出边拖出来时要求就地命名分支（这是 `sourceHandle` 的唯一编辑口）。
- 右侧配置面板按节点类型渲染表单，字段旁用 chips 展示可引用的上游变量，点击插入 `${}`；类型不兼容的变量灰掉并在 tooltip 说明原因。
- 底部面板三页：试跑（输入表单由开始节点的参数声明自动生成）、本次结果（逐节点状态、耗时、token、输入输出折叠）、历史（分页 + 单次详情）。运行时节点边框按 `NodeStarted`/`NodeFinished`/`NodeSkipped`/`NodeFailed` 变状态色，增量文本直接进节点卡片。
- 顶栏动作只有四个：保存草稿、校验、发布、版本。版本抽屉里给回滚与导出。

界面上去掉 Dify 既视感的几条具体决定：不叫「Workflow/Chatflow 双模式」，只有「流程」；不做「功能设置/特性开关」侧栏（那是应用型编排器的壳）；输出侧只有「回复」一个概念；画布上没有「并行」「迭代」这类还没实现的入口，避免露出空壳。

前端可用闸只有 `max build` 与单文件 `biome lint`（`harnax-webui/package.json:10`、`:9`；`tsc --noEmit` 有数百条既有噪声、`jest` 在当前 Node 上直接红）。因此所有需要断言的逻辑——图⇄xyflow 转换、拓扑与环检测、变量可达性、试跑事件归约——写成 `src/pages/orchestration/graph/` 下的纯函数并在浏览器实测里逐个覆盖，不假设有单测闸。

## 11. 测试与验收

| 层 | 判据 | 位置与坑 |
| --- | --- | --- |
| 引擎单测 | 表驱动覆盖：环检测、`skipped` 传播、AND-join 就绪、变量解析与类型不符、错误策略、步数与超时上限、重试只对幂等来源生效 | `harnax-workflow/src/test`，纯 JVM，无 Spring |
| 校验器单测 | 每条 `WF_` 码一个用例，且断言错误码而不是断消息 | 同上 |
| 节点 handler 单测 | LLM/能力/意图分流的 handler 用 fake `LlmInvoker`/`CapabilityInvoker`，不发真请求 | 协程用 `runTest`，不 `sleep` |
| 定义面 IT | CRUD、发布生成版本行、回滚只动指针、导入非法图被拒、绑定三种拒绝、软删有绑定被拒 | `-Pintegration-test`，Testcontainers MySQL 8；`schema-test.sql` 必须是基线逐字副本 |
| 租户隔离 IT | 跨租户按 id 取流程、取运行、建绑定、试跑全部被拒；每条 Mapper 方法都要出现在用例里，缺一个就是漏 | 形状照 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/DashboardTenantIsolationIT.kt`；邻居租户 id 用空闲段 |
| 执行链路 IT | 绑定已发布流程的 Agent 发一条会话消息，断言事件序列（`TextEvent*` → `EndEventChatEvent`）、`workflow_run` 与节点明细行数与状态、`token_stats` 增量 | 需要真模型；沿用现有 IT 的建 Agent 夹具（`modelId`/`description`/`systemPrompt` 缺一即红），密钥缺失时按现有约定跳过并说明 |
| 确认回路 | 图里命中 `needConfirm` 工具：会话内发出 `ToolConfirmChatEvent` 并挂住，答「是」继续跑完、答「否」按节点失败走错误策略；中途实例重启则该 run 由对账标 `timeout` | 复用 `ConfirmAgentRequest` 现有形状，含 `childRunId` 为空的分支 |
| 中断与并发 | 同一会话连发两条：第二条被拒；第一条执行中打 `/stop`：run 落 `stopped` 且未完成节点标 `skipped` | 必须真等防抖窗口与节点完成边界，不能靠固定 sleep 猜时序 |
| 重启对账 | kill 掉执行中的实例，重启后遗留 running 被标 `timeout` | 与部署文档的排障步骤同一条 |
| 前端 | `max build` 通过 + `biome lint` 无新增错项 + 浏览器实测走通「建流程→连图→试跑→发布→绑定 Agent→会话里跑通」 | 真栈走 harnax-deploy，浏览器侧用回环 HTTP 镜像（前端 https 自签打不开） |

验收口径：**做完 = 代码落盘 + 上述闸门跑绿 + 本文与实际行为一致**。实现期若撤掉本文任何承诺（例如试跑不落运行历史），必须回改本文，不许只在代码里留注释。

## 12. 部署与运维

无新服务、无新容器、无新端口。变更面只有三处：admin 与 agent-service 的代码与库表，webui 前端产物。

### 参数表

| 变量 | 落点 | 默认 | 说明 |
| --- | --- | --- | --- |
| `WORKFLOW_ENABLED` | agent-service | `false` | 总开关。关闭时即使规格带流程也按 `react` 跑，用于灰度与紧急回退 |
| `WORKFLOW_MAX_STEPS` | agent-service | `60` | 单次执行步数上限 |
| `WORKFLOW_MAX_EXECUTION_SECONDS` | agent-service | `600` | 单次执行时长上限，取值须与对话链路各跳超时逐跳核对 |
| `WORKFLOW_NODE_TIMEOUT_SECONDS` | agent-service | `120` | 节点默认超时 |
| `WORKFLOW_PARALLELISM` | agent-service | `4` | 分支并行度 |
| `WORKFLOW_MAX_CONCURRENT_RUNS` | agent-service | `16` | 单实例并发图执行 |
| `WORKFLOW_RUN_RETENTION_DAYS` | admin | `30` | 运行记录保留天数，0 表示不清 |

按既有规约，改任何环境变量都要四处一起动：`application.yml`、`harnax-deploy/docker-compose.yml`、`harnax-deploy/.env.example`、部署文档；且 compose 里要写成 `${VAR:-默认}` 形状而不是字面量，否则 `.env` 改不动。

### 升级步骤

1. 库表：新表折进 `V1__init_schema.sql` 并同步 `schema-test.sql`。**已应用的迁移连注释都不能改**，所以这一步的前提是这个库可以清重建；不可清的生产库另出前向 `V{N}__*.sql` 增量。
2. 起 admin（定义面 + 内部下发接口），再起 agent-service（引擎）。前端最后。
3. 回滚：把 `WORKFLOW_ENABLED=false` 重启 agent-service 即可让全部 Agent 回到自主路径；定义与运行记录留在库里不影响回滚，不需要回库表。
4. 首次开启时，绑定关系的校验只在写绑定与发布时做，因此灰度期间存量绑定不保证仍成立——上线后跑一次绑定体检查询（列出生效版本缺失或已软删流程的绑定）并逐条处置。

### 日常运维

- 容量：`workflow_node_execution` 每节点一行、`inputs`/`outputs` 是 mediumtext，是增长主源。保留策略按 run 粒度删（先删明细再删 run，同事务外的两步删除可重跑）；`graph_draft` 与版本快照不删，版本行只随流程软删。
- 排障四条：
  - 会话里 Agent「不按流程走」：先看 `WORKFLOW_ENABLED`，再看该 Agent 的 `agent_workflow_binding` 与生效版本是否为 NULL，最后看规格下发响应里 `workflowSpec` 在不在——这三处任一为空都是配置态问题，不是引擎问题。
  - 运行历史里长期 `running`：实例重启过且对账任务没跑起来，查 agent-service 启动日志里的 sweep 行与清理任务是否注册。
  - 试跑报 `WF_REF_FOREIGN`：草稿引用了别的租户或已停用的工具/模型，用发布级校验列全量而不是逐条改。
  - 渠道/定时任务侧「什么都没回」：图执行失败时 `ErrorChatEvent` 的码要在 channel 的映射表里有对应文案，缺码会被降成通用错误，看 `harnax-channel` 的错误转换分支补一条。
- 观测最小集：`workflow_run` 按 `status` 与 `elapsed_ms` 的分布、失败最多的 `node_type`、`token_stats` 与 `workflow_run.total_tokens` 的差值（对不上说明有节点没记账）。

## 13. 分期

A 档之后的语义增量，以及现在就要为它们留好的东西：

| 档 | 加什么 | 现在就要留好的 |
| --- | --- | --- |
| B | 迭代容器、循环容器、子流程、代码节点、fail-branch、会话内节点事件 | 边的 `sourceHandle` 已是字符串且校验按分支集合走（加 `fail-branch` 只扩集合）；节点 `data` 已带 `version`；`WorkflowEvent` 与 `ChatEvent` 已解耦 |
| C | 人工确认（暂停续跑）、会话变量、渠道内联表单 | 运行表已有 `status` 可扩展 `paused`；快照落 MinIO（现成服务）并把 object key 存列，不要把中间态塞进 DB 字段 |
| 后续 | 记忆节点、自主片段节点、按流程维度的用量报表 | 历史已走 `sys.history` 显式引用，节点新增输出不破坏现有 selector |

明确不做（避免被当成路线图缺口反复问）：画布多人协同编辑与评论、流程市场、把流程作为对外 API 产品发布、Dify DSL 兼容导入。

## 14. 决策清单

第 1 条已在选型轮拍板，其余每条都带默认值、逐条独立，可以只否其中一条。

1. **命名与信息架构**：已拍板——「编排」，落在一级菜单 `agent` 下作为子页（`/agent/orchestration`），表名与技术名保留 `workflow`。备选是「流程」或独立域 `Playbook`，未采。改名成本只涉及 `routes.ts`、两份 `menu.ts` 与页面目录名，动不到协议与库表。
2. **图模式不进 agentCache**：默认执行期分流、图侧自建并发闸；备选是给 `CachedAgent` 加一个流程运行时包装。选前者的理由是别让没有生命周期负担的执行路径背上 MCP 释放与沙箱保活。
3. **会话流零协议改动**：默认不新增 `ChatEvent` 类型，逐节点高亮只在试跑通道；备选是一步到位把节点事件推进会话，代价是四方反序列化锁步升级。
4. **不给 `token_stats` 加流程维度列**：默认按现有列记账，流程维度查 `workflow_run`；备选是加 `workflow_run_id`，会让既有聚合与首页总览的口径重核一遍。
5. **试跑读草稿的方式**：默认由 agent-service 向 admin 取，前端不提交图；备选是前端直传图 JSON，实现快一步但绕过租户与校验。
6. **绑定的版本语义**：默认 `version_id` 可为 NULL 表示跟随生效版本；备选是强制钉版本，发布后所有绑定 Agent 都要手改，运营成本高。
7. **变量语法 `${nodeId.field}`**：默认这个写法（仓内该语法在 prompt 与技能文本里没有既有用途，实测零冲突）；备选是沿用 Dify 的 `{{#...#}}`，但那会增加「看着像 Dify」的面。
8. **孤儿运行对账**：默认启动期扫描 + 每 5 分钟兜底；备选是不做，运行历史页长期挂着假在跑。
