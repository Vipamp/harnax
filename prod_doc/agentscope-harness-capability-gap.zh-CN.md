# AgentScope-Java Harness 能力对比与集成候选清单

## 0. 口径与取证基线

对比的两端：

- **上游**：AgentScope-Java 的 `agentscope-harness` 模块（Harness 框架层）与 `agentscope-core`（ReAct 运行时层），另加 `agentscope-extensions` 扩展族。
- **本方**：harnax 的 agent 内核，即 `harnax-agent/harnax-harness-core`（承担 agent-core 角色）+ `harnax-agent/harnax-agent-service` 的装配层。harnax 的 harness-core 本身就是包在 `agentscope-harness` 外面的一层，因此本文不是"两个框架选一个"，而是"上游给了什么、这一层实际接了什么"。

版本事实（决定哪些结论成立）：

| 项 | 取值 | 依据 |
|---|---|---|
| harnax 在用版本 | `agentscope-harness` / `agentscope-core` **2.0.2** | `harnax/pom.xml:39`（`<agent-scope.version>2.0.2</agent-scope.version>`） |
| 在用版本的事实来源 | 2.0.2 官方 sources jar 解出的 230 个 harness 源文件，与上游 tag `v2.0.2` 的 harness 源文件数逐字相等（230 = 230） | `~/.m2/repository/io/agentscope/agentscope-harness/2.0.2/…-sources.jar`，本地检出 `/Users/heqingsong/code/opensource/m2-sources/agentscope-harness-2.0.2/` |
| 上游最新检出 | 分支 `release/2.0.4`，`<revision>2.0.4-SNAPSHOT</revision>`，HEAD `3c1c29c0`，比 tag `v2.0.2` **多 197 个提交** | `/Users/heqingsong/code/opensource/agentscope-java/`，`git describe` = `v2.0.2-197-g3c1c29c0` |
| 2.0.4 的 harness 规模 | 源文件 230 → **269**（+39）；新增四个子包 `team`、`transcript`、`artifact`、`coordination`；`HarnessAgent.Builder` 公开方法 75 → **86，无一个移除** | `git ls-tree` 子包差集、builder 方法名差集 |
| 本机能取到的构件 | `agentscope-harness`：1.1.0-RC2 / 2.0.0-RC3 / 2.0.2；`agentscope-core` 另有 2.0.3 | `ls ~/.m2/repository/io/agentscope/…` |

由此定下两条纪律：**凡"现在跑成什么样"的断言以 2.0.2 源码为准**（harnax 编译的就是它）；**凡"升级带来什么、要不要升级"以 `release/2.0.4` 检出为准**。本文 A 类七条已在 2.0.4 逐条复核，默认值与 2.0.2 完全一致，所以收口结论跨版本成立（见 §6）。

上一版记录的"GitHub main 比 2.0.2 少 16 个类"随仓库切到 `release/2.0.4` 已失效：这 16 个类（`ChannelRuntimeContextRequest`/`ChannelRuntimeContextResolver`/`SandboxFileTransfer`/`RuntimeContextSkillRepository`/`RemoteAskPolicy`、`subagent.protocol.*` 6 个、`subagent.task.*` 4 个，以及 `SkillResources.java` 内的包级类 `EmptySkillResources`）在 2.0.2 与 2.0.4 都存在，`docs/v2/zh/` 现在也与 HEAD 同源。逐个定位后只有 `RuntimeContextSkillRepository` 换了模块（harness → core，见 §6.3 U7），其余 15 个位置不变。

harness 2.0.2 类分布（231 个类，对应 230 个源文件）：`sandbox` 45、`skill` 32、`gateway` 31、`filesystem` 31、`subagent` 27、`middleware` 18、`tool` 15、`memory` 10、`workspace` 7、`bus` 6、`tools` 5、根包 4。

锚点约定：harnax 侧 `HarnessAgentLauncher.kt`/`HarnessAgentBuilder.kt` 等省略了前缀 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/`（`agent/` 下的适配器与中间件同前缀去掉 `harness/`）；`DefaultAgentRunner.kt` 等省略了 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/`。上游侧省略 `io.agentscope.` 前缀，类名后可定位。

取证方式：全部为源码静态阅读 + 配置比对，未启动任何 harnax 服务。文中所有标 **未验** 的条目都需要实跑或产物级核对后才能当作事实使用。

---

## 1. 结论摘要

上游 harness 的 231 个类里，harnax 的 import 面只覆盖 24 个（`DistributedStore`、`HarnessAgent`、`IsolationScope`、`filesystem.spec.*`、`filesystem.remote.store.BaseStore`/`StoreItem`、`sandbox.**`、`sandbox.snapshot.*`、`WorkspaceSkillRepository`），另有 `InMemoryStore` 以全限定名内联出现在 `HarnessAgentLauncher.kt:578`，不计入这 24 个。差异不是均匀分布的，按性质分三类：

**A 类 · 上游默认开着，harnax 既没配置也没治理**（7 项）。这类最容易被漏掉，因为"没写代码"看起来像"没这回事"，实际是 `HarnessAgent.Builder` 的字段初值已经在跑。其中 A4 是本报告里唯一的**安全面**结论，A3/A6/A7 是**空转**结论（往模型的工具清单里塞了用不上的工具）。

**B 类 · 上游有、harnax 完全没有**（8 项）。真正需要拍板"要不要集成"的部分。B1（模型弹性）与 B2/A1 的收口（上下文压缩）是投入产出比最高的两项。

**C 类 · 上游有、harnax 已自建，不建议替换**（7 项）。逐条给出"可以借的机制"，避免后续有人拿上游实现来推翻既有裁定。

2.0.4 这条线改变了两件事的形状，但没改变上面三类的划分：其一，A 类七条在 2.0.4 的默认值**逐条复核后与 2.0.2 完全一致**，收口建议照旧成立；其二，2.0.4 新增了一批与 harnax 正面重叠的能力——原生 `team` 团队协调层、`transcript` 会话逐字记录、`deliver_artifact` 投递 SPI、跨节点周期闸、会话 turn 租约、技能使用统计后端——同时带来两个升级后才生效的静默默认（`web_fetch`/`web_search` 默认注册、transcript 默认落盘）。这些集中在 §6，其中 `web_fetch` 默认开是本报告新增的第二个**安全面**结论。

另外，本轮 self-improving 专项复核（B3.1）**修正了 B3 的一个前提**：技能治理面不是"配了就有"——四个内置 `SkillVisibilityFilter` 只 gate agent 自产的技能、对 harnax 的 admin 资产逐条放行，`.usage.json`/`.audit`/`.curator_state.json` 又不跟租户命名空间走，用量遥测只有 view 没有 use。Q7 从"P2 优先做"下调为"先过两道闸再做"，`enableSkillCurator` 单独调用是静默 no-op 这条也属于必须显式写出来的装配事实。

一句话总纲：**先做 A 类收口（把隐式默认变显式，成本几乎为零，风险最高的一项也在这一批），再做 B1 模型弹性 + A1 压缩显式化，B2/B3 这类改变产品形态的等你拍板；升级到 2.0.4 是独立的一项决策（Q9），且必须先过 §6.3 的兼容闸。**

---

## 2. harnax 侧装配实况

### 2.1 `HarnessAgent.Builder` 被调 / 未被调

harnax 实际调用的 builder 方法（统计口径：`harnax-agent/harnax-harness-core/src/main/kotlin/**` 内全部 `.kt`）：

| 已调 | 出现处 |
|---|---|
| `name` / `description` / `sysPrompt` / `model` / `maxIters` / `toolkit` | `harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt`、`HarnessAgentLauncher.kt:218` 附近 |
| `workspace` / `stateStore` / `agentId` / `environment` / `toolExecutionContext` / `enableMetaTool` | 同上 |
| `filesystem(SandboxFilesystemSpec)` / `filesystem(RemoteFilesystemSpec)` / `distributedStore` | `HarnessAgentLauncher.kt:548-593` |
| `permissionContext` / `skillRepository` / `middleware` | `HarnessAgentLauncher.kt:613+`、`HarnessAgentBuilder.kt:116,170` |
| `enablePlanMode` | `HarnessAgentBuilder.kt:121`（由 `chatSpec.enablePlan` 驱动） |
| `disableWorkspaceContext` / `disableMemoryHooks` / `disableSessionPersistence` | `HarnessAgentLauncher.kt:596-604`（由三个 `harness.*` 开关驱动） |
| `disableFilesystemTools` / `disableShellTool` / `disableSubagents` / `disableDefaultWorkspaceSkills` | `HarnessAgentLauncher.kt:605-611`（仅 lead）、`HarnessAgentBuilder.kt:168` |

未被调用的 builder 方法（**这就是 A 类与 B 类的来源**）：`compaction`、`disableCompaction`、`toolResultEviction`、`disableToolResultEviction`、`memory`、`toolsConfig`、`disableToolsConfig`、`messageBus`、`asyncToolRegistry`、`asyncToolTimeout`、`taskRepository`、`subagent`/`subagents`/`subagentFactory`/`externalSubagentTool`/`disableDynamicSubagents`、`skillRepositories`、`projectGlobalSkillsDir`、`skillFilter`/`skillsEnabled`/`enableSkills`/`disableSkills`、`enableSkillManageTool`/`enableSkillPromotionGate`/`enableSkillCurator`、`planFileDirectory`/`allowShellInPlanMode`、`abstractFilesystem`、`maxRetries`/`fallbackModel`/`modelResolver`/`generateOptions`/`modelExecutionConfig`/`toolExecutionConfig`/`stopOnReject`、`enableTaskList`、`enablePendingToolRecovery`、`enableAgentTracingLog`、`disableAtPathExpansion`、`disableMemoryTools`、`additionalContextFile`、`maxContextTokens`、`environmentMemory`、`useLegacyXmlWorkspaceContext`、`defaultSessionId`、`checkRunning`、`hook`/`hooks`。

### 2.2 部署侧实际取值

`harnax-agent/harnax-agent-service/src/main/resources/application.yml:43-45` 给了 `enable-workspace-context: ${HARNESS_ENABLE_WORKSPACE_CONTEXT:true}`、`enable-memory-hooks: ${…:false}`、`enable-session-persistence: ${…:true}`；`harnax-harness-core/…/harness/config/HarnessConfig.kt:23-25` 的 Kotlin 缺省是 `false/false/true`。部署侧 `harnax-deploy/docker-compose.yml:524` 把 workspace context 显式压成 `false`，同文件 `478`/`483` 打开 `MINIO_ENABLED` 与 `SANDBOX_ENABLED`。

**当前部署分支（讲功能必须落到这条）**：workspace context **关**、memory hooks **关**、session persistence **开**、Docker 沙箱 **开** + MinIO 快照/存储 **开**、`sandbox.isolation-scope` = `SESSION`（`application.yml:73`）、`mcp-stdio` **关**、lead 无沙箱。

### 2.3 harnax 自建层（对照时算"已有"）

| 域 | harnax 实现 | 位置 |
|---|---|---|
| 技能装载 | admin DB → `SkillAdaptor` → 自有 `InMemorySkillRepository`（实现上游 `AgentSkillRepository`） | `HarnessAgentBuilder.kt:211` |
| 技能文件进沙箱 | `SandboxSkillProjector`，落 `<workspaceRoot>/skills/<name>/` | `harness/skill/SandboxSkillProjector.kt` |
| 多 agent | `TeamOrchestrator` + `team_members`/`team_delegate`/`team_artifacts`（lead）、`team_artifact_publish`/`team_artifact_fetch`/`team_artifacts`（成员） | `harness/team/TeamToolBoxes.kt:66,122` |
| 团队产物 | `TeamArtifactGateway`（MinIO，object key 由服务端定） | `harness/team/TeamArtifactGateway.kt:13-46` |
| 沙箱保活/回收 | `KeepAliveSandboxManager`（idle 30 min、扫 5 min）、`CliArtifactReaper`、`SandboxIdleReaper` | `harness/sandbox/`、`harnax-agent-service/…/sandbox/` |
| CLI 包镜像 | `CliImageBuilder` + `CliPackageStore`（按 payload 组合出镜像） | `harness/sandbox/` |
| 会话状态 | `MysqlAgentStateStore`（实现上游 `AgentStateStore`） | `agent/session/` |
| KV 存储 | `MinioBaseStore`（实现上游 `BaseStore`） | `harness/minio/` |
| 观测 | `ProcessLogMiddleware` + `TokenStatsMiddleware`（实现上游 `MiddlewareBase`） | `agent/provider/middleware/` |
| 危险动作 | `DangerousInputCheckingTool` + `permissionContext` 的 ASK 规则；成员 HITL 确认带超时与心跳 | `harness/permission/`、`HarnessConfig.kt:51-58` |
| 产物文件 | `OutputFileDetector`（扫 `/workspace/output`，扩展名白名单 + 单文件上限 + 每轮上限）+ `OutputFileStore` | `harness/output/` |

---

## 3. A 类 · 上游默认已激活、harnax 未配置也未治理

本节锚点指 2.0.2 sources jar（harnax 在用的版本）。七条已在 `release/2.0.4` 逐条复核，结果与跨版本差异见 §6.1 —— 默认值一条都没变，只有 A6/A7 在升级后严重度下降。

`HarnessAgent.Builder` 的字段初值写在 `agentscope-harness-2.0.2` 的 `HarnessAgent.java:1141-1145`：

```
CompactionConfig compactionConfig = CompactionConfig.builder().build();   // 非 null
MemoryConfig memoryConfig = MemoryConfig.defaults();
ToolResultEvictionConfig toolResultEvictionConfig = ToolResultEvictionConfig.defaults();
boolean disableCompaction = false;
boolean disableToolResultEviction = false;
```

也就是说：**压缩与大结果卸载是默认开的**，harnax 一次都没配过它们。

### A1 对话压缩已默认在跑，但阈值取决于模型名是否命中内置表

- 上游：`CompactionMiddleware` 装在 `HarnessAgent.java:2306-2313`；默认 `triggerMessages=50`、`triggerTokens=0`（动态档）、`reserved=20_000`、`keepMessages=20`、`keepTokens=-1`（动态档，`keepTokensMin=2_000`/`keepTokensMax=8_000`/`ratio=0.25`）、`flushBeforeCompact=true`、`offloadBeforeCompact=true`（`CompactionConfig.java:273-286`）。动态档取 `model.getContextWindowSize() - reserved`（`CompactionMiddleware.java:157-177`）；模型报不出窗口时退化到 `FALLBACK_TRIGGER_TOKENS=160_000`（`CompactionConfig.java:67`）。窗口值由 `agentscope-core` 的 `ModelContextWindows` 按模型名前缀推断（`core/model/ModelContextWindows.java:22-45`，qwen 系有表）。
- harnax：模型走上游 `DashScopeChatModel.builder()` / `OpenAIChatModel.builder()`（`harnax-agent-utils/…/adaptor/model/ModelHelper.kt:52,75`），没有显式设上下文窗口，也从不调 `compaction(...)`。
- 结论：压缩确实在跑，但**阈值完全依赖模型名命中内置表**。走 openai 兼容网关自配的模型名（第三方/私有化）大概率不在表里 → 阈值 160k，可能比真实窗口晚得多才压。
- **压缩自带的写侧不受 `disableMemoryHooks` 约束**：`CompactionMiddleware` 自己 new `MemoryFlushManager` 与 `ConversationCompactor`（`CompactionMiddleware.java:104-106`），而 hooks 那个 if 只管 `MemoryFlushMiddleware`/`MemoryMaintenanceMiddleware`（`HarnessAgent.java:2271`），压缩装在同一个块之外（`:2306`；2.0.4 对应 `:2563` 与 `:2601`）。所以当前部署虽然关了 hooks，一次压缩照样跑 `flushBeforeCompact`（一次 LLM 调用，把事实**追加进 `memory/YYYY-MM-DD.md` 日报**，`MemoryFlushManager.java:330-331`；`MEMORY.md` 只作为只读上下文喂给模型、不写）与 `offloadBeforeCompact`（写 `sessions/<id>.jsonl` 与 `.log.jsonl`），合计两次额外 LLM 调用与两批文件写。**A6 的"jsonl 永不产生"按这条改。**
- `disableSessionPersistence()` **自 2.0 起是 no-op**（2.0.2 `HarnessAgent.java:2005-2009`、2.0.4 `:2269-2273`，注释原文 "No-op since 2.0; session persistence is owned by ReActAgent itself"）。§2.2 把 `enable-session-persistence` 当作部署分支事实陈述，但它不产生任何行为差异。
- 算法细节（2.0.2 与 2.0.4 一致，决定可调参的空间）：`compactIfNeeded` 先跑两趟非 LLM 预处理 —— `truncateArgs`（默认 `null`，关）与 `pruneToolResults`（默认 `PruneConfig.defaults()`，**开**：保护最近 `protectTokens=40_000` 的工具输出，超出部分单条 >`maxOutputChars=2_000` 字符时换成头+尾预览，累计可裁量 ≥`minimumTokens=20_000` 才动手，排除 `read_file`/`memory_search`/`memory_get`/`session_search`）。但这两趟**只用来判触发和喂摘要**：裁完若 `shouldCompact` 为假就直接返回 empty（`ConversationCompactor.java:105-111`），middleware 拿着**未裁剪的原始 `input`** 继续推理（`CompactionMiddleware.java:111-114`）—— 摘要不触发时这一趟白算。反向的坑是摘要失败不兜底：`ConversationCompactor.java:369-373` 把异常吞成字符串 `"(Summarization failed: …)"` 并照常覆盖前缀，middleware 那层的"压缩失败就按未压缩继续"救不了它。
- **另有一条完全独立的 compact 入口**（不属 A 类的隐式默认，是 B 类"上游有、harnax 没用"）：admin starter 的 `POST {agentscope.admin.base-path:/v1/admin}/sessions/{sessionId}:compact`，body `CompactRequest(keepLastMessages, replaceSummary)`，`keepLast` 缺省 **2**（锚点取 2.0.4 检出：`admin/controller/SessionAdminController.java:146`、`admin/properties/AdminProperties.java:50`、`admin/service/SessionOperations.java:155-215`；该扩展件在 tag `v2.0.2` 已存在）。它不走 `ConversationCompactor`，而是 `summarize → AgentState.setSummary(合并或替换) → 就地截断 context → persist`，改前 push 快照、`undo` 可回。**要害是 `AgentState.summary` 在 core/harness 主链路没有任何读取方**（全仓 `\.getSummary()` 反查，排除 test：只有 aistio adapter、admin 自己的 transcript 导出、`SubAgentTool.java:290` 的复制），`ReActAgent` 组装模型输入时不看它；而 harness 自动压缩是把摘要作为一条 USER 消息（`name=__compaction_summary__`）塞回 context。两种入口的"压完还能不能接着干活"完全不是一个强度。
- 附带：`harnax-agent-service/…/runner/impl/DefaultAgentRunner.kt:246` 还留着 `// TODO: implement memory compaction/summarization` —— 这条 TODO 的前提（"没有压缩"）已经不成立。
- 建议档位：**P0（显式化，不是新功能）**。给 `ChatSpec`/模型域加"上下文窗口"字段，装配时 `.compaction(CompactionConfig…)` 显式钉住 `triggerTokens`/`keepTokens`/`prune`，并把 `flushBeforeCompact`/`offloadBeforeCompact` 按 A6 的结论一起定下来（两条默认都开，且不受 `disableMemoryHooks` 管 —— 显式化时要一并决定是否为 flush 多付一次 LLM 调用）；同时把那条 TODO 改写为"核对阈值"。

### A2 大工具结果卸载已默认在跑，落点不在 harnax 的产物可见范围内

- 上游：`ToolResultEvictionMiddleware` 装在 `HarnessAgent.java:2315-2318`；默认 `maxResultChars=80_000`、`previewChars=2_000`、落盘目录 `large_tool_results/`，默认排除 `read_file`/`write_file`/`edit_file`/`grep_files`/`glob_files`/`list_files`/`memory_*`/`session_search`，**shell `execute` 不排除**（`ToolResultEvictionConfig.java:46-66`）。
- harnax：成员 agent 有沙箱文件系统，shell 输出超 80k 字符会被整段写进 `large_tool_results/` 并把原文裁成 2000 字符预览。harnax 的产物扫描只看 `/workspace/output`（`OutputFileDetector`），扩展名白名单 + 每轮条数上限。
- 后果：模型看到"结果太长已存文件"，但那条路径既不是 harnax 的产物出口、也不在清会话的删除清单里（**未验**：`clearSession` 是否连带删 `large_tool_results/`）。多副本 + MinIO 模式下它落在 KV 命名空间里，会跟着会话长期堆积。
- 建议档位：**P0（先定行为再谈优化）**。要么把阈值调大并显式排除 shell，要么把 `large_tool_results/` 纳入产物/清理链路。

### A3 MessageBus + Inbox + `wait_async_results` 工具默认全开，而 harnax 没有生产者

- 上游：只要 `filesystem` 非空就自动建 `WorkspaceMessageBus`（`.agentscope/bus`）与 `WorkspaceAsyncToolRegistry`（`HarnessAgent.java:2237-2246`），装 `InboxMiddleware`（`:2319-2321`），并注册 `WaitAsyncResultsTool`（`:2361-2371`）。`AsyncToolMiddleware` 需要 `asyncToolTimeout` 才装（`:2357-2360`），harnax 没设 → 异步卸载本身不生效。
- harnax：不 import 任何 `bus` 类，没有任何生产者往 inbox 投递。`filesystem` 在 harnax 的两条分支里都非空 —— 成员走 `DockerFilesystemSpec`/`RemoteFilesystemSpec`，lead 走 §A4 说的默认本地文件系统。
- 后果：每个 agent 的工具清单里多一个 `wait_async_results`，模型可以调它并空等（有超时）；每轮推理前多一次 inbox 队列读取；`wait_async_results` 与 `agent_spawn(timeout_seconds=0)` 的返回体一起进 `.agentscope/bus` 命名空间。
- 建议档位：**P1**。要么显式关掉（给 bus 传 `InMemoryStore` 级 no-op，或在没有生产者时不注册工具 —— 上游没有专门开关，需要以 `disableSubagents` + 自建 toolkit 过滤达成），要么真用起来（见 B4 的后台任务反向通知）。

### A4 lead agent 拿到的是宿主本地文件系统，`@path` 展开还默认开着 —— 本文唯一的安全项

- 上游：`filesystem(...)` 一次都没调时，`resolveFilesystem` 兜底走 `new LocalFilesystemSpec().toFilesystem(workspace, nsFactory)`（`HarnessAgentBuilderSupport.java:139-153`）。`LocalFilesystemSpec` 的路径策略默认 `LocalFsMode.ROOTED`（`:61`），`project` 根未显式设置时取 **`System.getProperty("user.dir")`**（`:72,290`）。`AtPathExpansionMiddleware` 只要不 `disableAtPathExpansion()` 就装（`HarnessAgent.java:2267-2268`），它识别 `@/abs`、`@./rel`、`@~/file` 三种形态，把文件内容（上限 1000 行）以 `<attached_file>` 追加进用户消息（`AtPathExpansionMiddleware.java:60-88`，`MAX_ATTACHED_LINES=1000`）。
- harnax：lead 分支不调 `filesystem(...)`（`HarnessAgentLauncher.kt:549`、`:583` 两条分支都带 `!isLead` 前提），因此 lead 的 filesystem 就是上面那个宿主默认；harnax 只关了 `disableFilesystemTools()`/`disableShellTool()`/`disableSubagents()`（`:605-611`），**没有关 `disableAtPathExpansion()`**。`AtPathExpansion` 不需要工具授权，直接读用户消息里的 `@` 串。
- 后果：ROOTED 白名单是「agent-service 进程的 `user.dir` 及其子树」∪「本会话 workspace 目录」。会话输入里出现形如 `@./xxx` 或 `@/绝对路径` 且落在 `user.dir` 子树内时，文件内容会被附进上下文 —— 在多租户服务里这是跨租户读取面。`@~/` 会展开到宿主 home（`expandHome`，`AtPathExpansionMiddleware.java:188-195`），home 通常不在 `user.dir` 子树内，因而被 PathPolicy 拒（**未验**，需实测确认）。
- 建议档位：**P0，先实测后定级**。验证方式：对 lead 会话发 `@./pom.xml`、`@/etc/hosts`、`@~/.ssh/id_ed25519` 三条，看是否出现 `<attached_file>` 块。修法成本极低（`disableAtPathExpansion()` 一行），或者给 lead 也显式给一个受控的 `RemoteFilesystemSpec`/`InMemoryStore` 文件系统而不是吃宿主默认。

### A5 框架自带子 agent 工具在成员上是默认装的，harnax 的团队层是第二条委派路

- 上游：`!leafSubagent && !disableSubagents && model != null` 且有 filesystem 时，装 `DynamicSubagentsMiddleware` 并把它 `getTools()` 全注册（`HarnessAgent.java:2324-2339`，工具为 `agent_spawn`/`agent_send`/`agent_list` + `task_output`/`task_cancel`/`task_list`，见 `DynamicSubagentsMiddleware.java:100-101,139`、`SubagentsMiddleware.java:661`）。同时把 TaskRepository 接到 messageBus 上（`:2330-2333`）。
- harnax：只在 lead 上 `disableSubagents()`（`:610`），成员没关。`HarnessAgentLauncher.kt:616-621` 的 `frameworkAllowTools` 里逐字列了 `"agent_spawn","agent_send","agent_list","task_output","task_list"` —— 这反证这些工具**在生产里确实存在且必须 ALLOW**，否则 `PermissionEngine` 会在 DEFAULT 模式下对它们发 ASK 把轮次卡住。
- 后果：harnax 有两条委派路径。受治理那条是 `team_delegate`（租户归属、`maxDelegations=20`、成员轮次超时 900s、HITL 确认 600s + 心跳、产物走 `TeamArtifactGateway`）；不受治理那条是 `agent_spawn`（子 agent 由 `subagents/` 目录声明驱动，harnax 没有声明目录，因此只剩内置 `general-purpose` 与运行时动态派生 —— **未验**：无声明时 `agent_spawn` 的实际可用面）。
- 建议档位：**P0 拍板项，不是代码缺口**。要么成员也显式 `disableSubagents()` + `disableDynamicSubagents()`，把委派收敛到 `team_*` 一条路（与"团队只配 skill、无 agent 行"的既有口径一致）；要么把 `agent_spawn` 纳入租户/预算治理后再放。**当前状态（半开）是三条里最差的一条。**

### A6 记忆工具注册了，喂它们的 hooks 被关 —— 半开

- 上游：`if (!disableMemoryTools)` 注册 `MemorySearchTool`/`MemoryGetTool`/`MemorySaveTool`/`SessionSearchTool`（`HarnessAgent.java:2374-2379`）；写侧在 `MemoryFlushMiddleware` + `MemoryMaintenanceMiddleware`（`:2271-2303`），受 `disableMemoryHooks` 控制。会话日志 `*.log.jsonl` 只有 `MemoryFlushManager.offloadToSessionTree` 会写（`memory/MemoryFlushManager.java:214-238`）。
- harnax：`enable-memory-hooks` 默认 `false`（§2.2，部署未覆盖）→ hooks 关；`disableMemoryTools` **从未调用** → 四个工具照常注册。
- 后果：模型可以调 `memory_save` 把内容写进 `MEMORY.md`/`memory/*.md`，但没有任何东西把它读回系统提示（`WorkspaceContextMiddleware` 也关着）；`session_search`/`session_history` 读的 jsonl **不是永不产生**：A1 那条 `offloadBeforeCompact` 每次压缩都会全量 offload 一遍，flush 还会写 `memory/YYYY-MM-DD.md` 日报，两者都不看 `disableMemoryHooks` 的脸色。但它们**只在压缩触发那一刻写**（默认 50 条起），短会话照样一次不写；真正缺的始终是**读回**——`WorkspaceContextMiddleware` 关着，consolidation 又被 hooks 关掉，日报不会被并进 `MEMORY.md`，也没人把它喂进系统提示。等于在工具清单里放了四个"看起来能用、用了没反馈"的工具，还占 prompt 体积。
- 建议档位：**P0（收口）**。本轮 `disableMemoryTools()`，B2 真接记忆时再一起打开。

### A7 `workspace/tools.json` 加载与 allow/deny 过滤默认开

- 上游：`!disableToolsConfig` 时 `ToolsConfigLoader.load(wsManager)`，命中则 `McpServerRegistrar.register(...)` + `ToolFilter.apply(...)`（`HarnessAgent.java:2429-2438`、`:2607-2609`）。
- harnax：工具与 MCP 的真相源是 admin 的 DB 行（带租户谓词），从没写过 `tools.json`；因此该分支当前是 no-op。但一旦某个 workspace 目录里出现同名文件（沙箱内被 CLI 生成、或 B5 引入 git 工作区投影），它会**静默注册 MCP 并按 allow 表砍内置工具** —— 上游文档自己把"allow 忘留 `read_file`/`memory_search`/`agent_spawn`"列为常见坑（`docs/v2/zh/docs/others/going-to-production.md:465`）。
- 建议档位：**P1**。`disableToolsConfig()` 一行，把 allow/deny 语义留给现有的"admin 禁用工具行"链路。

---

## 4. B 类 · 上游有、harnax 完全没有

### B1 模型弹性与调用参数（**推荐接，成本最低**）

上游 builder 上完全没被 harnax 触碰的一组：`maxRetries(int)`、`fallbackModel(Model|String)`、`modelResolver(Function<String,Model>)`、`generateOptions(GenerateOptions)`、`modelExecutionConfig(ExecutionConfig)`、`toolExecutionConfig(ExecutionConfig)`、`stopOnReject(boolean)`、`checkRunning(boolean)`。core 侧 `ModelConfig.DEFAULT_MAX_RETRIES = 3`（`core/agent/config/ModelConfig.java:32`）。

- 价值：harnax 现在唯一的兜底是 `turnTimeoutSeconds`（单轮 300s、团队轮 1800s）。主模型 429/5xx/超时 → 整轮失败，用户侧表现为一次空回复。`fallbackModel` + `maxRetries` 直接消掉这一类。
- 需要前置确认的：模型域是否允许"一个 agent 配两把模型"，或者 fallback 从哪来（同租户内的备用模型行？全局默认行？）。这是产品口径，不是代码问题。
- 成本：装配层加两个可选参数 + `AgentSpec`/`ChatSpec` 各一字段；无 DB 变更（若 fallback 走全局配置）。
- 档位：**P1，建议做**。

### B2 长期记忆 pipeline

上游三层：`MemoryFlushManager`（每轮结束抽事实写 `memory/YYYY-MM-DD.md`，`DEFAULT_FLUSH_PROMPT` 可追加项目规则）+ `MemoryConsolidator`（把日报合并进 `MEMORY.md`，`consolidationMaxTokens=4_000`）+ `MemoryMaintenanceMiddleware`（节流 `consolidationMinGap=30min`、`dailyFileRetentionDays=90`、`sessionRetentionDays=180`），全部走 `AbstractFilesystem`，因此同一套逻辑在本地/Remote/沙箱三种模式下都成立。`MemoryConfig` 可给记忆操作单独指定一个小模型（`MemoryConfig.java:51-60,234-255`）。上游文档：`docs/v2/zh/docs/harness/memory.md`（265 行）。

- 与 harnax 的关系：harnax 完全没有跨会话记忆层，`MEMORY.md` 这个词在 harnax 代码里只出现在 `MinioBaseStore.kt` 的注释里。A6 的四个工具正是它的入口。
- 要拍板的点：记忆的**归属维度**（租户 × 用户 × agent？）、存储落点（MinIO 命名空间还是 MySQL）、`WorkspaceContextMiddleware` 是否随之打开（它负责把 `MEMORY.md` 注入系统提示，而当前部署是关的）。这三个没定就接，会变成 A6 的第二版。
- 档位：**P2，需要产品决定**（见 §8 Q3）。

### B3 技能自学习闭环 + 技能治理面

上游给的是完整一条链，harnax 一个都没开：

| 能力 | 上游入口 |
|---|---|
| agent 自己写/改/patch/归档技能 | `enableSkillManageTool(...)` → `SkillManageTool` + `ProposeSkillTool`（`HarnessAgent.java:2458-2511`） |
| 草稿区与正式区分离 | `SkillManageConfig.mainDir()/draftsDir()`，新技能默认落草稿 |
| 入库前静态安全扫描 | `SkillSecurityScanner`（正则类别 × trust level × verdict 安装策略） |
| 晋升闸门（人工/多副本/全自动拒绝） | `SkillPromotionGate`：`RejectAllGate`（默认）/`LocalApprovalGate`/`NotifyAndWaitGate` + `NotificationSink` |
| 用量遥测与审计 | `SkillUsageStore`（`skills/.usage.json`）+ `SkillAuditLog`（`skills/.audit/*.jsonl`）+ `SkillUsageMiddleware` |
| 后台整理（ACTIVE→STALE→ARCHIVED，30/90 天 cutoff、7 天周期） | `enableSkillCurator(SkillCuratorConfig)` + `SkillCuratorMiddleware`；**必须与 `enableSkillManageTool` 同开才生效**，且文档所称"LLM 合并"在 2.0.4 仍是 stub（B3.1 第 1、5 条） |
| 按用户灰度/环境收敛可见性 | `SkillVisibilityFilter`：`AllowListFilter`/`CanaryFilter`（hash(userId×skillName) 百分比）/`EnvironmentFilter`/`CompositeFilter`，接 `environment(...)` |

- 现状对照：harnax 的技能是 admin 后台的资产（DB 行 + 租户 + 审核 + 版本摘要），装载走 `SkillAdaptor` → 自有 `InMemorySkillRepository` → `SandboxSkillProjector` 投影进容器。**harnax 完全没有"按用户灰度一个技能"和"技能有没有被用过"这两种能力** —— 但上游这两个载体**对 harnax 的资产是空转的**：四个内置 `SkillVisibilityFilter` 只 gate "agent 自己产的技能"，无用量记录的外部技能逐条放行（B3.1 第 3 条）；`SkillUsageStore` 的两个装配入口又嵌在 `skillManageToolEnabled` 分支内，builder 上没有独立开关（第 1 条）。
- 分档：治理面（可见性过滤 + 用量遥测 + 审计）= **P2，但不是"配了就有"**：灰度要自己 `implements SkillVisibilityFilter`，遥测只有 view 没有 use，且 `.usage.json`/`.audit` 在 2.0.4 上是跨租户共享的一份（第 3、4、6 条）；自写技能（`enableSkillManageTool`）= **P3，需要 admin 侧先有"agent 产的技能怎么进审核流"的口径**，否则会绕过租户与审核。
- 版本注：在 2.0.4 上，用量存储已是 `SkillUsageBackend` 抽象（`FilesystemSkillUsageBackend` 走工作区文件、`BaseStoreSkillUsageBackend` 走 `BaseStore` 跨节点 CAS，见 §6.2）；harnax 有多副本，接 `BaseStoreSkillUsageBackend` 就不必自建这一层。2.0.2 只有单文件 `<workspace>/skills/.usage.json`，多副本靠同级 `.usage.json.lock` 的文件锁协调（`SkillUsageStore.java:45,51,63`）—— 在 harnax 的 MinIO 远端文件系统上这不构成跨节点安全。2.0.4 两个后端的构造点分别是 `HarnessAgent.java:2814`（`SkillUsageStore.baseStore(distributedStore.baseStore())`）与 `:2815`（`new SkillUsageStore(filesystem)`），**都在 `:2783` 那个 `if` 里面**。
- 上游文档：`docs/v2/zh/docs/harness/skill.md:209-273`。

### B3.1 self-improving 专项复核（2.0.4 逐条，本轮新增）

上游把这套体验称作 self-learning loop（`docs/v2/en/docs/harness/skill.md:210`，原文 "the agent drafts skills → review gate → background curator tidies up"）。B3 的表给的是能力清单，这一节给的是**清单背后的闸在哪、默认组合跑出什么**。十条全部指向 `release/2.0.4` 工作树，锚点省略前缀 `agentscope-harness/src/main/java/io/agentscope/harness/agent/`。

按 §0 的版本纪律补一句跨版本口径：**第 1、3、4、5、6 条已在 2.0.2 sources jar（harnax 在用版本）逐条复核对齐，不是升级引入的回归** —— curator 的嵌套在 2.0.2 是 `HarnessAgent.java:2458`（`skillManageToolEnabled`）包住 `:2524`（`skillCuratorEnabled`）；`USE_TOOL_NAMES = Set.of("use_skill")` 同在 `middleware/SkillUsageMiddleware.java:54`、同样指向不存在的工具；`skill/curator/AbstractAgentCreatedFilter.java:52` 的 pass-through 逐字相同；`RuntimeContext.empty()` 在 2.0.2 的 `SkillUsageStore`/`SkillAuditLog`/`SkillPromoter`/`SkillCurator` 四个类里命中（2.0.2 尚**无** `SkillUsageBackend` 抽象，直接单文件）；umbrella 的 stub 注释同样存在。第 2、7、8、9、10 条未逐条比 2.0.2，但都是"上游本就少做了一环"性质的缺失，不影响下面的收口结论。

1. **三个开关不是并列的。** `skillManageToolEnabled`、`skillCuratorEnabled` 初值 `false`，`promotionGate`、`visibilityFilter`、`skillManageConfig`、`skillCuratorConfig` 初值 `null`，`environment = "prod"`（`HarnessAgent.java:1265-1272`）。而 curator 的装配分支（`:2843`）**嵌在** `if (skillManageToolEnabled && filesystem != null)`（`:2783`）里面 —— 只调 `enableSkillCurator(...)` 是**静默 no-op**。同一块内还构造了可写 `skills` 仓（`:2795-2808`）、`SkillUsageStore`（`:2812-2815`）、`SkillAuditLog`（`:2816`）、`SkillManageTool` + `ProposeSkillTool`（`:2818-2826`）、`SkillUsageMiddleware`（`:2828`）、`SkillPromoter`（`:2832-2840`）。
2. **默认组合等于"只能写草稿、永远晋升不了"。** `promotionGate == null` 时兜底 `new RejectAllGate()`（`:2837`）；`SkillManageConfig` 的初值是 `autoPromote=false`、`securityScan=true`、`draftsDir=skills/_drafts`、`mainDir=skills`（`tool/SkillManageConfig.java:78-81`）。想真落晋升必须显式给 prompter：`LocalApprovalGate.defaultPrompter()` 恒 Defer，`NotifyAndWaitGate` 也恒 Defer，只往 `skills/_drafts/<name>/.review_request.json` 写一条请求，等外部调 `HarnessAgent.promoteSkill(...)`（`:312`）。对外还开着 `queryAudit(...)`（`:286`）与 `getSkillUsageStore()`（`:278`）。
3. **四个内置可见性过滤器对 harnax 的技能逐条放行。** `AbstractAgentCreatedFilter.filter()` 在 `rec == null || !"agent".equals(rec.createdBy())` 时 `out.add(skill); continue;`（`skill/curator/AbstractAgentCreatedFilter.java:50-56`），`CanaryFilter`（`:33`）与 `AllowListFilter`（`:27`）都继承它；`EnvironmentFilter` 无侧车记录时放行（`:56-59`）、`environments` 为空也放行（`:62`）；只有 `CompositeFilter` 是直接 `implements`（`:27`，纯委托）。而 `createdBy="agent"` 只在晋升那一刻由 `SkillPromoter` 写（`skill/curator/SkillPromoter.java:157`）。灰度算法本身是 `hash(userId|skillName)` 稳定百分桶、不依赖用量记录（`CanaryFilter.java:58-69`），要作用到 admin DB 那些技能上只能 harnax 自己 `implements SkillVisibilityFilter`（接口 public，`SkillVisibilityFilter.java:36`），**别继承那三个**。另一个坑：`visibilityFilter` 唯一的 setter 挂在 `enableSkillPromotionGate(gate, visibilityFilter)` 上（`:2142-2144`），只想过滤就得连带设一个晋升闸门。
4. **用量遥测只记"看过"，不记"用过"。** `VIEW_TOOL_NAMES = {load_skill_through_path, read_skill}` 有效（`middleware/SkillUsageMiddleware.java:47-48`），`USE_TOOL_NAMES = Set.of("use_skill")`（`:54`）而全仓不存在名为 `use_skill` 的工具（读路径只有 `skill/runtime/SkillLoadTool.java:57` 的 `load_skill_through_path`）→ `bumpUse` 永不触发、`lastUsedAt` 恒 null。老化锚点 `latestActivityAt()` 取 lastUsed/lastViewed/lastPatched 三者最大值（`SkillUsageRecord.java:149-155`），所以 curator 的 30/90 天实际判据是"没被加载过、也没被 agent 改过"。
5. **curator 只会老化，不会合并。** 默认 `enabled=true`、`intervalHours=24*7`、`staleAfterDays=30`、`archiveAfterDays=90`、`umbrellaPassMode=DRY_RUN_ONLY`（`skill/curator/SkillCuratorConfig.java:75-79`，全可配）。状态机 ACTIVE→STALE→ARCHIVED，只处理 `createdBy=="agent"` 且非 `pinned` 的（`SkillCurator.java:213`，判定 `:197-241`，STALE 可回填 reactivated）。umbrella 那一趟的 javadoc 自陈是 stub、需要 auxiliary model 的 LLM 合并 "deferred to a future version"（`:248-257`），报告只按名字前缀聚类，`consolidations`/`prunings` 为空。触发方式既不是定时任务也不在轮内：`middleware/SkillCuratorMiddleware` 在 `onAgent(...).doOnComplete` 查 `shouldRunNow` 后投给单线程 daemon `ScheduledExecutorService`（首次只播种不执行），另有手动 `runCuratorOnce()`（`HarnessAgent.java:298`）绕过 interval。存储是 `skills/.curator_state.json` 与 `skills/.curator_reports/<ts>/REPORT.md`。
6. **遥测、审计、curator 状态是全局一份，不跟租户走。** `FilesystemSkillUsageBackend.java:64,86`、`SkillAuditLog.java:113,118,127,153`、`SkillCurator.java:110,127,314` 一律传 `RuntimeContext.empty()`，键只有 skill 名；而 skill 本体的读写确实按 ctx 命名空间隔离（`skill/WorkspaceSkillRepository.java:58-61`，回归测试 `src/test/java/io/agentscope/harness/agent/middleware/HarnessSkillManageNamespaceTest.java` 断言 alice/bob 互不泄漏）。→ harnax 多副本多租户 + MinIO 远端 FS 下，`skills/.usage.json`、`skills/.audit/*.jsonl`、`skills/.curator_state.json` 是**跨租户共享文件**，curator 还按这份全局视图做老化。**这是 B3/Q7 接入前的第一道闸**，换成 `BaseStoreSkillUsageBackend` 也不解决命名空间问题。对照证据在同一版本里：`transcript/TranscriptStore` 的键是 `{tenant}/{agentId}/{sessionId}/events/…`（见 §6.2）—— 上游不是做不到租户维度，是技能侧车这条线没用上。
7. **写侧无权限判定、无限流，审计记不到真实调用者。** `skill_manage` 六个 action（create/edit/patch/write_file/remove_file/delete，`tool/SkillManageTool.java:64`，参数 schema `:144-225`）没有任何 caller 权限分支，只有静态校验：名 ≤64（`MAX_NAME_LENGTH`，`:67`）、描述 ≤1024、正文 ≤100k、单文件 ≤1MiB、`^[a-z0-9][a-z0-9._-]*$`、子目录限 `references`/`templates`/`scripts`/`assets`（`:67-73` 区段）；整个 skill 包 grep `RateLimit|quota|maxWrites` 零命中。审计条目形状齐备（`Entry(ts, actor, op, target, decision, scanVerdict, beforeHash, afterHash, ctx, extra)`，`SkillAuditLog.java:74-85`），但 `SkillManageTool` 写入时 `actor` 传 `"agent"`（`:371`）、`decision`/`scanVerdict` 传 `"SAFE"`（`:375`），失败只作为 error 文本回给模型 —— 审计里既没有真实调用者身份也没有被拒记录，**不能直接当 admin 的审核账本用**。`delete` 非破坏，移进 `skills/.archive/<name>-<ts>/`（`WorkspaceSkillRepository.java:63,74`）。
8. **生效粒度是每次 `call()`，不是每轮推理。** `<available_skills>` 由 `skill/runtime/SkillPromptBuilder.java:72,148` 渲染，`middleware/HarnessSkillMiddleware.onSystemPrompt`（`:262-314`）每次重读磁盘做 merge + 过滤 + staging；但 system prompt 只在 `agentscope-core/…/ReActAgent.java:771-774` 的 `seedSystemMsg` 生成一次，`scope.systemMsg` 在 call 入口置空（`:765`）后被 reasoning 循环复用（`:2421`）。`tool/SkillManageTool.java:138,386` 写的 "immediately visible on the next reasoning turn" **与代码不符**：`autoPromote=true` 时一次 call 内新写的技能本轮不可见。`disableDynamicSkills()`（`HarnessAgent.java:2105`）走 `frozen()` 快照，整个 agent 生命周期不再重读盘。
9. **两条静默旁路。** `securityScan=false` 直接绕过 `SkillSecurityScanner`（策略是正则类别 × trust level × verdict，`shouldAllow(TrustLevel, Verdict)` 在 `skill/curator/SkillSecurityScanner.java:293-302`，AGENT_CREATED 允许 SAFE/CAUTION、只拦 DANGEROUS）；`autoPromote=true` 时 `markAgentCreated(name, "auto", ["prod"])` 直接进生产可见集，无闸门也无人审。
10. **闭环里没有"有效性"这一环。** 晋升判据只有静态扫描 + 闸门意见，结构校验只有 frontmatter 解析 + name 一致性 + 描述必填与长度（`tool/SkillManageTool.java:311-332`）；此链路 grep 无 `eval`/`benchmark`/A-B/LLM judge 命中，没有任何东西衡量"这条技能到底有没有用"。所以 self-learning 的实质是**技能生命周期管理 + 人工晋升**，不是"agent 自己变好"。上游自己的案例文给了本该有的那一维：驱动 Agent 与技能演进的核心力量不是抽象的自我改进，而是基于具体场景的可量化效果对比（`docs/v2/en/blogs/usecases/logistics.md:1158`）—— 本实现恰好缺这一维。

顺带定名，免得后面把三件事混谈。本文所称 self-improving 在上游是三条独立机制：**① 上面这整条技能闭环（全 opt-in，默认全关）；② 记忆自维护（默认开）**，写侧与 `disableMemoryHooks` 的关系见 A1/A6，合并与保留期见 B2；**③ 工作区自编辑** —— `AGENTS.md`/`subagents/*.md`/`tools.json` 按回合重读（`docs/v2/en/docs/harness/workspace.md:21-25`），通用 `write_file`/`edit_file` 默认注册且**无守卫**，agent 能直接改自己未来的行为；`agent_generate`（按 LLM 产出写 `subagents/<name>.md`，`tool/AgentGenerateTool.java:63`）默认不注册，需 `middleware/SubagentsMiddleware.java:252` 那个开关，但它在 `tools/HarnessPlatformTools.java:61` 名单里，按 §6.1 A7 的新语义不会被 allow 白名单剥掉。**明确不存在**：模型权重更新（`agentscope-extensions-training/…/runner/TrainingRouter.java:33-74` 是影子流量采样 + 外部 Trinity-RFT 后端，harness 主链路不调用它）、prompt 自优化、plan mode 的自我评审循环（`planModeEnabled=false`，退出走 `plan_exit` 人审）、跨 session 复用压缩摘要（见 A1）。

### B4 子 agent 的四种高级形态

harnax 的团队层覆盖了"主管派成员、成员在独立沙箱、产物靠 MinIO 中转、HITL 上抛"，但下面这几条上游有、harnax 没有：

1. **内置 `general-purpose` 子 agent**：不写声明即可用，同模型同工具同技能，用来"隔离上下文跑一个子任务"。
2. **工作区声明文件驱动**：`subagents/*.md`（YAML frontmatter）由 `AgentSpecLoader` 装载；`ISOLATED`/`SHARED` 两种 workspace 模式（`WorkspaceMode`）。
3. **后台任务 + 反向通知**：`agent_spawn(timeout_seconds=0)` 返回 `task_id`，任务完成后框架在下一轮推理前以 `<system-reminder>` 把结果注入父上下文（`docs/v2/zh/docs/harness/subagent.md:113-144`），存储是 `agents/<parentAgentId>/tasks/<parentSessionId>.json`（`WorkspaceTaskRepository`）。harnax 的团队气泡现在需要自己把成员结果拉回主流程。
4. **把子 agent 暴露给用户直接对话**：`expose_to_user=true` → `SubagentExposedEvent` 进事件流，客户端拿到 `subagentId` 后可绕过父直接发消息（`subagent.md:175-280`）；跨副本靠 `SubagentRegistry` + `SubagentMaterializer` 重建实例；跨进程靠远程子 agent 协议（`AgentProtocolTaskClient`/`RemoteEventCodec`，含 `awaiting_confirm` 状态与 `RemoteAskPolicy`）。

- 判断：3 和 4 是**产品形态选择**，不是补漏。harnax 已有 iOS/web 客户端与自己的流式协议，接 4 要动协议；接 3 只需把 `taskRepository`/`messageBus` 换成 harnax 的 MySQL/MinIO 实现并消费 reminder。
- 档位：**P3**，除非你明确要"分支对话"或"成员后台跑完自动回推"。

### B5 沙箱与文件系统面的四块

| 上游能力 | 类 | 对 harnax 的意义 |
|---|---|---|
| 按隔离键限制并发执行（可插拔闸门 + 租约） | `SandboxExecutionGuard`/`SandboxLease`/`SandboxIsolationKey`，由 `DistributedStore` 自动注入（`HarnessAgent.java:2202-2211`、`going-to-production.md:321`） | harnax 现在是 `KeepAliveSandboxManager` 只兜 idle 回收，没有"同一租户/同一 session 并发执行上限"。**这是"沙箱容器只增不减"那条记账的正解方向** |
| 原生文件上传下载（不走 exec） | `SandboxFileTransfer`（仅 2.0.2 有，main 无） | harnax 的 `SandboxFileWriter` 走 `docker exec` 写，大文件与二进制路径受限 |
| 工作区布局声明与投影 | `WorkspaceSpec` + `layout/*`：`FileEntry`/`DirEntry`/`BindMountEntry`/`LocalFileEntry`/`LocalDirEntry`/`GitRepoEntry`/`WorkspaceProjectionEntry`，配 `WorkspaceSpecApplier`/`WorkspaceProjectionApplier`（内容哈希跳过未变更）/`WorkspaceArchiveExtractor`（防路径穿越解 tar） | harnax 自己写了 `SandboxSkillProjector` + `CliPackageStore`；上游这套多了 **git 仓库直接进工作区**、以及带哈希去重的通用投影。可以少维护一处自有实现 |
| 四种额外沙箱后端 | `agentscope-extensions-sandbox-{kubernetes,e2b,daytona,agentrun}` | harnax 目前绑单机 docker CLI（`DockerCommandExecutor`），扩容受限于"哪台机器有容器"。K8s 后端是把 agent 从单机解开的现成路径 |

另：`WorkspaceIndex`（本地 SQLite 索引，加速 `agents/**/sessions/**`、`memory/**` 的 glob，仅 Remote 模式建，`HarnessAgent.java:2176-2181` 附近）与 `CompositeFilesystem`/`OverlayFilesystem`/`ProjectAwareOverlay` 的分层读 —— harnax 用 `RemoteFilesystemSpec` + `MinioBaseStore`，这两块是可选优化。

档位：并发闸 **P1**（与容器回收同批做）；其余 **P2/P3**。

### B6 会话新鲜度与历史检索

`SessionFreshnessEvaluator`（每日固定时刻重置 / 闲置 N 小时重置）、`SessionTree`（append-only JSONL + `log.jsonl` 永不压缩副本）、`SessionSearchTool`（`session_list`/`session_history`/`session_search`）。harnax 的会话历史在 MySQL（`loadSessionMessages`），没有"重置策略"也没有"跨会话检索"；A1 的压缩一旦把前缀裁掉，原始消息就无处可寻 —— 但这条兜底**默认就在**（`offloadBeforeCompact=true`，见 A1），不是"除非打开"。**副本的保真度分版**：2.0.2 走 `MemoryFlushManager.offloadToSessionTree`（`:262-288`），把整条消息 `renderContentBlocks` 全量写成一条 `MessageEntry`，**不截断**，但条目形状粗（工具调用与结果混在一条文本里）；2.0.4 改走 `SessionTranscriptWriter`，工具调用/结果拆成结构化行并**截断**（入参 JSON >500 字符只留 `_preview`，工具输出 >1000 字符截断，各自带 `truncated`/`originalSize`，`SessionTranscriptWriter.java:62-65,295-342`）。所以上游 `docs/v2/zh/docs/harness/compaction.md:84,107` 那句"原始消息整段写入永不压缩的日志、agent 能查回原文"两版都不完全成立。

档位：**P2**，但有个前置：B2/A6 决定打开时，`session_search` 必须与它同批 —— `offloadBeforeCompact` 已默认开、写侧不缺，缺的是**读回入口**：A6 一旦 `disableMemoryTools()`，`session_search` 就不在工具清单里，压缩掉的前缀模型自己查不回来。这条比"要不要长期记忆"更硬。

### B7 观测

`AgentTraceMiddleware`（`enableAgentTracingLog(boolean)`，INFO 记模型/工具名/消息长度，DEBUG 记入参与结果）；core 侧有 `OtelTracingMiddleware` 与 `hook/recorder`。harnax 的 `ProcessLogMiddleware`/`TokenStatsMiddleware` 已经覆盖了业务侧流程日志与 token 落库，缺 OTel 链路。

档位：**P3**（除非要接分布式 tracing）。

### B8 扩展族（决定是否引入外部依赖，只列不评）

`agentscope-extensions` 共 18 个模块：`channel`（feishu/wecom/dingtalk/github/gitlab/common）、`mem`（mem0/reme/bailian）、`model`、`mysql`、`postgresql`、`redis`、`oss`、`cos`、`higress`、`nacos`、`rag`（bailian/dify/ragflow/haystack/simple）、`protocol`（a2a/agent-protocol/agui/chat-completions-web）、`sandbox`（agentrun/daytona/e2b/kubernetes）、`scheduler`（common/quartz/xxl-job）、`skills`（`agentscope-extensions-skill-mysql-repository`，类名 `io.agentscope.core.skill.repository.mysql.MysqlSkillRepository`）、`studio`、`training`、`spring-boot-starters`。

两条需要单独表态的：

- **`MysqlSkillRepository`**：harnax 的 `SkillAdaptorImpl` 干的是同一件事（DB → `AgentSkillRepository`）。上游那版带 `createIfNotExist`/`writeable` 开关，且是官方文档推荐的生产装配（`going-to-production.md:442-446`）。要不要把自有实现换成扩展件 + 自己套一层租户谓词，是个真问题。
- **`extensions-channel` 的飞书/企微/钉钉**：harnax 有 `harnax-channel-feishu/-wecom/-dingtalk` 且已定稿（webhook 只飞书、文件投递只微信；原有代码不可修改约束）。**不建议换**，列出来是为了防止后面有人提"复用官方适配器"。

> 扩展件在 2.0.2 上的 Maven 坐标是否已发布 —— **未验**，本文只确认了源码存在于 main 检出；真要引入得先探坐标。

---

## 5. C 类 · 上游有、harnax 已自建，不建议替换

| 上游能力 | 不替换的理由 | 可以借的机制 |
|---|---|---|
| `Gateway`/`GatewayBootstrap`/`ChannelRouter`/`ChannelBinding`/`DmScope`/`Peer`/`ChatUiChannel` | harnax 的入口是 router + session-router + channel 服务，带租户、API Key/Internal JWT 二元鉴权、自有 iOS/web 客户端协议；换 gateway 等于重写入口层 | 会话互斥与唤醒两个机制在 2.0.4 已落地成具体类：`SessionTurnGate`/`TurnLease`/`TurnBusyException`（由 `distributedStore.sessionTurnGate()` 自动接入）、`WakeupDispatcher`（后台完成唤醒空闲会话） |
| `DistributedStore` 的 Redis/COS/OSS 实现 | harnax 用 `MinioBaseStore` + `MysqlAgentStateStore`，已在部署里跑通 | — |
| `WorkspaceSkillRepository` / 工作区技能目录 | harnax 技能是 admin 资产，带租户与审核 | `SkillVisibilityFilter` **这个接口可借，四个内置实现对 harnax 的资产空转**（只 gate agent-created，B3.1 第 3 条）；用量后端 2.0.4 已有 `SkillUsageBackend`（文件 / `BaseStore` CAS 两种），见 B3 与第 6 条的跨租户前置 |
| `tools.json` + `ToolsConfigLoader` + `McpServerRegistrar` | harnax 的工具/MCP 真相源是 admin DB（带租户与状态位），stdio 另有两道闸 | `ToolFilter` 的 allow/deny 语义可用 `toolsConfig(...)` 程序化喂，不引入文件；注册终态回调 2.0.4 有 `mcpServerRegistrationListener` |
| 框架 `subagent` 作为唯一委派路 | 团队口径已定稿：主管自带配置、成员只配 skill | `taskRepository`/`messageBus` 的抽象形状（见 B4-3）。**2.0.4 另有一整套原生 `team` 协调层**，归属见 Q11 |
| 产物文件检测 `OutputFileDetector`（扫 `/workspace/output`） | harnax 的产物可见性、扩展名白名单与每轮上限都在这一层，客户端协议依赖它 | 2.0.4 的 `ArtifactDeliveryTarget` SPI + 按需注册的 `deliver_artifact` 是更明确的投递形状（opt-in，不静默生效），见 §6.2 |
| `LocalFilesystemWithShell` | 多租户进程直接禁宿主 shell（harnax 已 `disableShellTool` 于 lead） | 反面案例：见 A4 |
| Plan Mode（`PlanModeManager`） | harnax 现用 `enablePlanMode` + `PlanNoteAdaptor`，计划文件在工作区 `plans/` 下由框架管理 | `planFileDirectory`/`allowShellInPlanMode` 两个开关尚未暴露到 `ChatSpec` |

---

## 6. 2.0.2 → 2.0.4 增量

上游仓库现在在 `release/2.0.4`（`2.0.4-SNAPSHOT`，比 tag `v2.0.2` 多 197 个提交）。这一节只回答两个问题：**本文的结论在 2.0.4 还成立吗**、**升级到 2.0.4 会多出什么**。所有 2.0.4 锚点指 `/Users/heqingsong/code/opensource/agentscope-java/` 的工作树文件。

### 6.1 A 类七条的跨版本复核

| 条 | 2.0.4 状态 | 证据（HEAD 锚点） |
|---|---|---|
| A1 压缩默认在跑 | **默认值不变；错误边界与 token 计数变了** | `HarnessAgent.java:1227` 初值仍是 `CompactionConfig.builder().build()`；溢出兜底 `:1047-1098`。**变的部分**：`CompactionMiddleware` 的 `onErrorResume` 从 `flatMapMany` 之后移到之前 —— 2.0.2 `:134-140` 会把**下游推理错误**一并吞掉并**重跑一次 `next.apply(input)`（harnax 现在带着的就是这个缺陷），2.0.4 `:113-128` 改成"压缩失败就按未压缩继续、下游错误照常上抛"；`TokenCounterUtil` 自 2.0.4 起把 `ThinkingBlock` 计入 token（`:132`），思考型模型会更早触发；#2659 让用户中断不再被当作压缩失败、#2799 让模型调用失败时仍持久化当前轮上下文 |
| A2 大结果卸载默认在跑 | **阈值不变，排除集变了** | `:1229` 仍是 `ToolResultEvictionConfig.defaults()`；排除集从 `{read/write/edit, grep, glob, list, memory_*, session_search}` 缩到 `{read/write/edit, memory_*, session_search}` —— `grep_files`/`glob_files`/`list_files` 在 2.0.4 **重新变成可驱逐** |
| A3 MessageBus/Inbox 空转 | **不变** | `:2504-2509` 仍是 workspace 默认自建 `WorkspaceMessageBus` |
| A4 lead 宿主本地文件系统 + `@path` | **不变，且展开器逐字未改** | `HarnessAgentBuilderSupport.java:147-170` 默认分支仍是 `new LocalFilesystemSpec().toFilesystem(...)`，注释明写 project 默认 `${user.dir}`；`LocalFilesystemSpec` 默认 `mode = ROOTED`、`project = System.getProperty("user.dir")`；`AtPathExpansionMiddleware.java` 相对 v2.0.2 **零 diff**（`MAX_ATTACHED_LINES = 1000`，Remote 型文件系统仍直接禁用） |
| A5 框架子 agent 工具装到成员 | **不变** | `:2633-2634` 条件仍是 `!leafSubagent && !disableSubagents && model != null` |
| A6 记忆工具注册、喂它的 hooks 被关 | **一半被上游补上了** | 工具仍由 `:2688 !disableMemoryTools` 注册、hooks 仍由 `:2563 !disableMemoryHooks` 决定；但 2.0.4 新增 `TranscriptMiddleware`，其 javadoc 明写"独立于 memory flush，`disableMemoryHooks` 时照样接线，好让会话历史保持完整"。即 **`session_search` 在 2.0.4 即使 hooks 关也有数据**，`memory_search` 的语料仍只有模型自己调 `memory_save` 那一条路，`MEMORY.md` 没人并（consolidation 归 hooks 管）；但 `memory/YYYY-MM-DD.md` 日报**有人写** —— 写侧是 A1 那条压缩前的 flush，不受 `disableMemoryHooks` 约束 |
| A7 `tools.json` 默认加载 | **仍默认加载，过滤语义变了** | `:2759 !disableToolsConfig`；`ToolFilter` 新增规则：`HarnessPlatformTools`（子 agent、team、后台 task、plan mode、skills admin）**不会被 allow 白名单剥掉**，只有显式 `deny` 才移除；另加 `isStrictAllow()` 与 MCP 按 server 名前缀独立放行 |

**结论：A 类七条一条都没被上游改掉默认值**，§3 的收口建议在 2.0.2 和 2.0.4 上都需要做，成本与判据不变。A6/A7 两条在升级后严重度下降，属于"升级顺手解决"。

### 6.2 2.0.4 新增能力（按 harnax 视角的集成候选）

| 能力 | 上游载体 | 与 harnax 的关系 | 档位 |
|---|---|---|---|
| 原生团队协调层 | `team/TeamClient`+`LocalTeamClient`（BaseStore CAS，等价 Claude 文件锁）、`TeamContext`（含 `availableActions`、恢复上下文）、`TeamTask`（pending/in_progress/completed/failed + `version` 乐观锁）、`TeamMessage` 邮箱、`TeamWakeups` 钩子桥；`tool/TeamTool` 单工具多 action，按角色裁剪（lead 拿 create/assign/spawn/shutdown/approve/complete，成员拿 list/claim/message）；`middleware/TeamsMiddleware` 注入系统提示，可选 `wireMessageBus` 把邮箱唤醒接到 inbox；builder `teamsMode(...)` | harnax 的团队层是**产品层**（DB 行、admin 配置与审核、主管确认流、`maxDelegations` 预算、成员 turn 超时），上游是**运行层协议**（共享任务板 + 邮箱 + 版本 CAS）。两者不是同一层，但重叠在"委派与状态归属"上，必须先定谁说了算 | **Q10**，不自动替换 |
| 产物投递 SPI | `artifact/ArtifactDeliveryTarget`（宿主/对象存储/WebDAV 等）、`ArtifactDeliveryResult`/`Request`、`tool/ArtifactDeliveryTool` 的 `deliver_artifact`；**只在配了 target 时注册**，配了之后沙箱系统提示会指示模型使用 | 直接对上 harnax 的 `/workspace/output` 扫描与 `team_artifact_publish/fetch`。是 opt-in，不会静默生效，接入风险低 | **P2，推荐接**：比扫目录更明确，且 harnax 自己也要写传输实现 |
| 会话逐字记录 | `transcript/TranscriptStore`（append-only **不可变分段**，键 `{tenant}/{agentId}/{sessionId}/events/{seqStart}-{seqEnd}-{writerId}.jsonl`，并发写不会互相覆盖）、`FilesystemTranscriptStore`/`ObjectStoreTranscriptStore`、`memory/session/SessionTranscriptWriter`（结构化工具调用/结果行，带 `truncated` + `originalSize`）、`TranscriptMiddleware`；builder `transcriptStore`/`transcriptTenant`/`disableTranscript` | harnax 有 MySQL 会话与逐轮 token，但没有**工具级逐字记录**；对象存储后端正好吃 harnax 已有的 MinIO；且与 `session_search`/恢复直接联动 | **P2**，与 B6 同批 |
| 跨节点周期闸 | `coordination/PeriodicGate`、`LocalPeriodicGate`、`StoreBackedPeriodicGate`（最小间隔节流，`tryClaim` 语义） | harnax 的定时任务集群化**已定稿 D1~D8**，这一层属同一职责，不能双跑 | **只作对照**，动它前先读既有裁定 |
| 会话 turn 租约 | `gateway/TurnLease`（幂等 close）、`LocalSessionTurnGate`、`TurnBusyException`，由 `distributedStore.sessionTurnGate()` 自动接入（`:694-697`） | 正是 harnax 记账里「同成员并行双委派」「切会话不断流」的运行层解法 | **P1，推荐接** |
| 技能使用统计后端 | `skill/curator/SkillUsageBackend` + `FilesystemSkillUsageBackend`/`BaseStoreSkillUsageBackend`（单进程文件或跨节点 CAS） | B3 里的"用量遥测"上游已实现，接法从自建变成配后端 —— 但**只省掉 CAS 这一层**：不解决 `.usage.json` 跨租户共享一份，也不解决 view 有计数、use 恒空（B3.1 第 6、4 条） | 从 P2 自建改为 **P2 配置 + 两道前置** |
| 文件系统前缀路由 | builder `filesystemRoute(prefix, fs)`、`RoutedSandboxFilesystem`（路由仍保留主沙箱的 `shell_execute`）、`PinnedSandboxFilesystem`（异步镜像上传期间钉住沙箱） | harnax 现在沙箱与 MinIO 二选一；路由可让 memory-store 类前缀挂到别的后端 | **P3** |
| 内置 Web 工具 | `tool/WebTools` 的 `web_fetch`/`web_search`（Tavily，无 key 返回配置错误）、builder `webHttpClient`/`disableWebTools` | harnax **完全没有** web 工具（全仓无 `web_fetch`/`web_search` 实现） | 见 §6.3 的默认注册风险 |
| MCP 注册结果回调 | builder `mcpServerRegistrationListener`、`McpConnectionException`/`McpServerRegistrationResult`（不向动态子 agent 传播） | 对上 harnax 记账里「未授权提示不可见」：注册终态有回调就有可见性 | **P2** |
| 外部 schema 注册 | builder `registerExternalSchemas(...)`（self_hosted 环境下挂挂起等待 worker 执行的 hands tools） | harnax 无对应形态 | 只记录 |

### 6.3 升级兼容闸（升级前必须逐条解决）

| # | 闸 | 证据 | 处理 |
|---|---|---|---|
| U1 | `GitSkillRepository` **从 core 移到扩展件**，FQN 不变、artifact 变 | HEAD 上该类只在 `agentscope-extensions/agentscope-extensions-skills/agentscope-extensions-skill-git-repository/`，`agentscope-core/src/main/java/io/agentscope/core/skill/repository/` 里已无此文件；harnax `harnax-admin/.../GitSkillLoader.kt:3` import、`:68` 直接构造 | 加 `agentscope-extensions-skill-git-repository` 依赖；JGit 版本仍由 `agentscope-dependencies-bom` 管，`harnax-admin/pom.xml:133` 现在没写 version，加了扩展件后要确认收敛 |
| U2 | **`web_fetch`/`web_search` 默认注册** | `:1246 disableWebTools = false`，`:2725-2732` 无条件注册（不看沙箱、不看 lead），`WebTools.java:79-80` 只校验 URL 以 `http://`/`https://` 开头，**没有宿主/网段黑名单** | harnax 的 lead 不调用 `disableWebTools()`，升级后 lead 会在**宿主 JVM 里直接出网**且可打内网地址（SSRF 面）。升级这一跳必须同时显式 `disableWebTools()` 或给出受控 `webHttpClient`（代理/白名单在客户端层做） |
| U3 | transcript 默认落盘 | `:1253 disableTranscript = false`，默认写到 `<workspace>/.agentscope/transcripts` | 与 A2 的 `large_tool_results/` 同一类归属问题：`clearSession` 要不要连带清（见 §9 未验） |
| U4 | `ToolFilter` allow 语义变化 | §6.1 A7 | 升级后重跑一遍装配期工具清单断言（批 0 的验收判据正好复用） |
| U5 | 驱逐排除集收窄 | §6.1 A2 | 与 Q4 一起定阈值与清理归属 |
| U6 | builder 面无移除 | 75 → 86 个 `public Builder` 方法，差集里**没有**被删项 | 装配层不需要预防性改写 |
| U7 | 类跨模块迁移（FQN 不变也可能换 artifact） | 已核实两处：`GitSkillRepository` 由 core 迁到扩展件（即 U1，harnax **受影响的就这一处**）；`RuntimeContextSkillRepository` 由 `harness.agent.skill` 迁到 `core.skill.repository`（FQN 也变了，harnax 未 import） | 升级闸的做法是**逐个 FQN 对源树做存在性核对**（本文已跑：harness 24/24 命中，core 58/59 命中）；`ModelContextWindows` 在 2.0.2 与 2.0.4 都在 `core/model/`，未迁移 |
| U8 | 版本成熟度 | 2.0.4 仍是 `-SNAPSHOT`，上游 `docs/v2/zh/service/*` 自标"预览文档，正式版本尚未发布" | 升级窗口跟 release 走，别跟 SNAPSHOT 走 |

harnax import 面的机械核对：24 个 harness FQN 在 HEAD **全部存在**；59 个 core FQN 里只有 `io.agentscope.core.skill.repository.GitSkillRepository` 一处缺席（即 U1）。

### 6.4 同层的 `agentscope-service`（战略观察，不是集成项）

HEAD 多出整个 `agentscope-service/`：`service-gateway`、`service-dataplane`、`service-scheduler`、`service-common`、`aistio`、`frontend`、`helm`、`deploy`、`docker`、`scripts`，配套 `docs/v2/zh/service/*`。按其 index 文档，这是一个 Agent 运行/管理/编排平台（Gateway 统一入口与认证 → Control 存 Agent 目录、Team、Workflow 与工作记录 → Scheduler 协调执行 → Managed/External/Hosted 三种运行形态，持久层 PostgreSQL + Workspace + Artifact）。

这层与 harnax 的 router / admin / scheduler / agent-service / webui **同层**，不是"harness 里某个功能"。它带来的是定位问题：harnax 继续自建，还是按 External Agent 方式接入一套上游平台。这条不该混进功能批次表，但会影响后面几批的取舍权重（见 Q12）。

---

## 7. 建议批次（等你拍板后按批走）

**批 0 · 收口（不新增能力，只把隐式变显式）**
- A4 `disableAtPathExpansion()`（或给 lead 显式文件系统）—— 先实测再改
- A6 `disableMemoryTools()`
- A5 成员侧 `disableSubagents()` + `disableDynamicSubagents()`
- A7 `disableToolsConfig()`
- A3 消掉 `wait_async_results` 空工具
- A2 定下大结果卸载的阈值与清理归属
- 验收判据：装配后的 toolkit 工具名清单逐条比对（`agent.getToolkit()`），断言里不出现 `wait_async_results`/`memory_*`/`session_search`/`agent_*`/`task_*`；lead 会话里 `@./pom.xml` 不再产生 `<attached_file>`。

**批 1 · 低成本高价值**
- B1 模型弹性：`maxRetries` + `fallbackModel`（+ 可选 `modelExecutionConfig`）
- A1 压缩显式化：模型域登记上下文窗口 → `.compaction(...)` 钉阈值；`DefaultAgentRunner.kt:246` 的 TODO 改写
- 验收判据：造一次主模型 5xx/超时的用例，断言走了 fallback；构造超长历史，断言在预期 token 处压缩且 plan/task 状态未丢。

**批 2 · 需要产品口径**
- B5 `SandboxExecutionGuard` 并发闸（与容器回收同批）
- B3 技能治理面（可见性过滤 + 用量遥测 + 审计）—— 两个前置：`.usage.json`/`.audit`/`.curator_state.json` 的跨租户共享要先定归属（B3.1 第 6 条），灰度过滤器要 harnax 自建 `SkillVisibilityFilter` 实现（第 3 条），否则接上是空转。
- B2 + B6 + A6 的"真接记忆"（三者必须同批，否则又是半开）

**批 3 · 形态级**
- B4-3 后台任务反向通知；B4-4 子 agent 暴露给用户；B5 K8s 沙箱后端；B7 tracing；B8 `MysqlSkillRepository` 替换评估。

**批 U · 升级到 2.0.4（独立决策，取决于 Q9）**
- 前置：U1 `GitSkillRepository` 扩展件依赖 + U2 `disableWebTools()`（或受控 `webHttpClient`）。这两条不同批解决就不许升。
- 顺手：U3 transcript 清理归属、U4 工具清单断言重跑、U5 驱逐排除集与 Q4 一起定。
- 只有升级后才拿得到：会话 turn 租约（`SessionTurnGate`/`TurnLease`）、transcript 分段存储（MinIO 后端）、`deliver_artifact` 投递 SPI、`SkillUsageBackend`、MCP 注册结果回调、`filesystemRoute`。
- 批 0 与批 1 **与版本无关**（改的是 harnax 自己的装配调用），可以先做再升级，这样升级那一跳的 diff 只剩上游行为变化。

---

## 8. 需要你拍板的问题

| # | 问题 | 我的推荐 | 理由 |
|---|---|---|---|
| Q1 | 成员的 `agent_spawn` 系列工具留不留 | **关** | 与"团队只有一条委派路"的既有口径冲突；现在它已进 ALLOW 规则，说明在生产里活着 |
| Q2 | lead 的宿主文件系统默认要不要改 | **改**，显式给受控 filesystem 或至少 `disableAtPathExpansion()` | 当前 2.0.2 上唯一的安全面结论，成本一行（升级后还有 §6.3 U2 第二个） |
| Q3 | 长期记忆要不要做（归属维度、存储落点） | 先不做，先把 A6 关干净 | 三个前提没定就接必成半开 |
| Q4 | 大工具结果卸载 | **保留但配平**：调阈值 + 纳入清理，不直接关 | 80k 字符阈值对 shell 输出是有效的上下文保护 |
| Q5 | fallback 模型从哪来 | 需要你给口径：模型域加"备用"字段，还是全局配置一把 | 决定要不要动表结构 |
| Q6 | 技能自学习（agent 自己写技能） | **P3**，且必须先定 admin 审核流怎么接 | 会绕过租户与审核。补两条代码事实：上游默认组合本就等于"只能写草稿、永远晋升不了"（`promotionGate` 兜底 `RejectAllGate`，B3.1 第 2 条），`skill_manage` 写侧无权限判定且审计 `actor` 硬编码 `"agent"`（第 7 条）—— 所以审核账本必须建在 admin 侧，不能指望上游 `.audit` |
| Q7 | 技能按用户灰度 + 用量遥测 | 仍在 P2，但**从"优先做"降为"先过两道闸再做"** | 不改产品形态、纯治理增益这个判断不变，但内置实现接不上：四个 filter 只 gate agent-created 技能，对 harnax 的 admin DB 资产逐条放行（B3.1 第 3 条，`visibilityFilter` 还只能通过 `enableSkillPromotionGate` 设）；`SkillUsageStore` 的两个装配点嵌在 `skillManageToolEnabled` 分支内、无独立开关（第 1 条），且 `.usage.json` 走 `RuntimeContext.empty()` 是跨租户一份（第 6 条）；遥测只有 view 没有 use（第 4 条）。`SkillUsageBackend` 抽象确实省掉了 CAS 那一层，但省不掉上面四条 |
| Q8 | K8s 沙箱后端 | 视部署形态定 | 单机 docker 是当前扩容瓶颈，但换后端是运维工程 |
| Q9 | 要不要升级到 2.0.4、什么时候升 | **升，但排在批 0 之后**，且 U1/U2 与升级同跳 | turn 租约、transcript、投递 SPI、用量后端只有 2.0.4 有；但它是 SNAPSHOT，且升上来会静默多两个默认行为（§6.3） |
| Q10 | 要不要给 agent 联网能力（`web_fetch`/`web_search`） | 要做得单独拍板，且**必须**配受控 `webHttpClient` 或域名白名单 | harnax 现在完全没有 web 工具；上游默认注册、只校验 http/https 前缀、无网段黑名单 → 多租户进程里等于开放 SSRF 面 |
| Q11 | 团队层归属：用上游 `team` 协议还是继续 harnax 自建 | **继续自建**，只借它的 CAS 任务板与邮箱语义 | harnax 的团队层带 DB 行、admin 审核、主管确认流与预算，属产品层；换协议会动既有口径（见"Multi-agent 产品边界"的三条已定稿） |
| Q12 | `coordination/PeriodicGate` 与已定稿的定时任务集群化 | **不动既有裁定** | 同一职责不能双跑，scheduler 集群化 D1~D8 已定稿 |
| Q13 | 与上游 `agentscope-service` 平台的定位关系 | 本轮**只观察不决策** | 它自标预览，且属"平台 vs 自建"的战略层，不该混进功能批次 |

---

## 9. 取证与未验清单

**已取证（源码静态阅读）**：`HarnessAgent.java` 的 `Builder` 全量方法（`:1109-2026`）与 `build()` 装配分支（`:2096-2642`）；`HarnessAgentBuilderSupport.java`（`resolveFilesystem`、两个 subagents 装配）；`CompactionConfig`/`ToolResultEvictionConfig`/`MemoryConfig` 默认值；`AtPathExpansionMiddleware` 的匹配式与 home 展开；`LocalFilesystemSpec` 的 ROOTED 默认与 `user.dir` 兜底；`MemoryFlushManager` 的 jsonl 写入点；`ModelContextWindows`/`ModelConfig.DEFAULT_MAX_RETRIES`；harnax 的 `HarnessAgentLauncher.kt:218-621`、`HarnessAgentBuilder.kt:32-170`、`HarnessConfig.kt`、`application.yml:42-95`、`harnax-deploy/docker-compose.yml:478/524`；harnax 的 import 面与 builder 调用面统计。

**针对 2.0.4 的核对方式**：`git describe` = `v2.0.2-197-g3c1c29c0`（分支 `release/2.0.4`，`<revision>2.0.4-SNAPSHOT`）；harness 源文件 `git ls-tree` 计数 230 → 269，新增子包由差集得出（`team`/`transcript`/`artifact`/`coordination`）；builder 面用 `public Builder <name>(` 方法名集合做差集（75 → 86，无移除项）；A 类七条按 2.0.4 的字段初值与装配条件逐行重读；`AtPathExpansionMiddleware.java` 与 `core/…/memory/` 包相对 v2.0.2 **零 diff**，`ToolResultEvictionConfig.java`/`ToolFilter.java` 有 diff 且已逐条读；harnax 侧用 24 个 harness FQN + 59 个 core FQN 逐个对 HEAD 源树做存在性核对（唯一缺席项 `GitSkillRepository`，已定位到扩展件）。§0 提到的 16 个类也逐个定位过：15 个在两版位置相同，只有 `RuntimeContextSkillRepository` 由 harness 迁到 core。tag `v2.0.2` 的 harness 文件数与 2.0.2 sources jar 相等（230 = 230），故在用版本的事实源成立。

**本轮（会话 compact 专项）新增取证**：逐行读 2.0.4 的 `memory/compaction/{CompactionConfig,ConversationCompactor,ToolResultEvictionConfig,TokenCounterUtil}.java`、`middleware/{CompactionMiddleware,ToolResultEvictionMiddleware,TranscriptMiddleware}.java`、`memory/MemoryFlushManager.java`、`memory/session/{SessionTree,SessionTranscriptWriter}.java`、`transcript/*`，以及 `agentscope-admin-spring-boot-starter` 的 `SessionAdminController`/`SessionOperations`/`AdminProperties`/`CompactRequest`/`CompactResponse`；2.0.2 侧同一批文件用 `git show v2.0.2:…` 取（与 §0 的 sources jar 同源），**逐处比行号而不是比结论**。`AgentState.summary` 的读取方用全仓 `\.getSummary()` 反查（排除 test）定性；admin 的 `:compact` 端点存在性用 `git ls-tree -r v2.0.2` 核。压缩相关的上游提交链：`7ab520dba` #1802（**默认开就是这一笔落的**，且已用 `git merge-base --is-ancestor` 确认它是 `v2.0.2` 的祖先 —— 即"升级前不压缩"这个假设不成立）、`17fee94f` #2360（链式摘要保留旧 summary）、`e728e7bc` #2659（中断不再被当压缩失败）、`3f0f3dfa` #2799（模型失败仍持久化当前轮）、`d19e8cb7` #3144（只修了 `keepTokens` 一行文档默认值）。

**上游文档与代码不一致（本文一律以代码为准）**：① `docs/v2/zh/docs/harness/compaction.md:16,27` 写"默认是关的""四套策略默认全部不开"，而 `HarnessAgent.java:1227-1230` 的初值是 `CompactionConfig.builder().build()` + `disableCompaction=false`，同一文件 `:1869` 的 KDoc 又自写 "Compaction is enabled by default" —— 上游两页文档互斥，代码侧默认**开**；② `compaction.md:84,107` 的"原始消息整段写入永不压缩的日志、agent 能查回原文"与 2.0.4 的 transcript 截断不符（见 B6）；③ `docs/v2/zh/docs/harness/memory.md` 的压缩字段表漏 `prune`/`reserved`/`keepTokensMin`/`keepTokensMax`/`keepTokensRatio` 五项，`truncateArgs` 另有专段但同样不给默认值；④ `docs/v2/en/docs/harness/skill.md:210` 与 `workspace.md:375` 把 curator 写成"background curator tidies up / LLM 合并"，代码侧 umbrella 那一趟自陈是 stub、`consolidations`/`prunings` 为空（B3.1 第 5 条）；⑤ `tool/SkillManageTool.java:138,386` 的 "immediately visible on the next reasoning turn"，与 `ReActAgent.java:765,771-774,2421` 的"每次 `call()` 才生成一次 system prompt"不符（第 8 条）；⑥ `middleware/SkillUsageMiddleware.java:52` 的注释自陈 "empty today so bumpUse stays unused"，而 `:54` 的集合非空、只是指向一个全仓不存在的 `use_skill`（第 4 条）。

**本轮（self-improving 专项）新增取证**：逐行读 2.0.4 的 `skill/curator/{SkillCurator,SkillCuratorConfig,SkillUsageStore,SkillUsageRecord,SkillUsageBackend,FilesystemSkillUsageBackend,BaseStoreSkillUsageBackend,SkillAuditLog,SkillPromoter,SkillPromotionGate,RejectAllGate,LocalApprovalGate,NotifyAndWaitGate,SkillSecurityScanner,AbstractAgentCreatedFilter,CanaryFilter,EnvironmentFilter,AllowListFilter,CompositeFilter}.java`、`tool/{SkillManageTool,ProposeSkillTool,SkillManageConfig,AgentGenerateTool}.java`、`skill/WorkspaceSkillRepository.java`、`skill/runtime/{SkillLoadTool,SkillPromptBuilder}.java`、`middleware/{SkillUsageMiddleware,SkillCuratorMiddleware}.java`，以及 `HarnessAgent.java:1263-1274`（builder 字段初值）、`:2126-2160`（三个 enable 方法）、`:2775-2860`（装配块）。四种判据形状，后面复用：装配面看 **`if` 嵌套层级**（`:2783` 包住 `:2843` → curator 单独开是 no-op）；功能有效性看 **被引用的工具名是否真的存在**（`"use_skill"` 全仓只命中那一个集合 → `bumpUse` 恒不触发）；隔离性看 **传的是哪个 `RuntimeContext`**（`RuntimeContext.empty()` 在 usage/audit/curator 状态三处命中、在 skill 本体读写零命中）；生效粒度看 **`scope.systemMsg` 的生命周期**（`ReActAgent.java:765` 置空、`:771-774` 生成、`:2421` 复用）。「闭环里没有 eval/judge」是否定式 grep（`eval|benchmark|judge|critique|rubric` 在 `skill/` 包零命中），只能当"未发现"，不当"不存在"。

**未验（需要实跑或产物核对）**：
1. `@path` 在 lead 上的实际可读范围（批 0 的第一条，实测三条输入即可定性）。
2. 无声明时 `agent_spawn` 的可用面（是否只剩 `general-purpose`）。
3. `clearSession` 是否连带删除 `large_tool_results/` 与 `.agentscope/bus` 命名空间；升级后还要加 `.agentscope/transcripts`。
4. 现网模型名是否命中 `ModelContextWindows` 表（决定压缩阈值）。
5. `agentscope-extensions-*` 各件在 2.0.2 的 Maven 坐标是否可用；`agentscope-extensions-skill-git-repository` 在 2.0.4 的坐标是否已发布（U1 的前提）。
6. harnax 部署分支的判定依据是 `application.yml` + `docker-compose.yml` 的字面 env；若有其他环境注入（K8s configmap 等）以现场为准。
7. 升级到 2.0.4 后 `web_fetch` 的实际出网能力（沙箱内 vs lead 宿主 JVM 两条路径分别测；含内网地址可达性）。
8. `LocalTeamClient` 在 MinIO `BaseStore` 上的 CAS 语义是否成立（若 Q11 决定借它的任务板才需要验）。
9. 压缩是否**真的触发过**：现网会话消息数是否到过 50 条（或动态 token 档），以及 `offloadBeforeCompact` 在容器/沙箱工作区里是否真写得进去、归谁清。查法：agent-service 日志找 `Compaction triggered`（2.0.2 与 2.0.4 同在 `ConversationCompactor.java:127`）与 `Compaction complete`（2.0.2 `:196` / 2.0.4 `:202`），再看 workspace 下是否出现 `agents/<agentId>/sessions/*.jsonl`。
10. 压缩回写与 harnax 会话展示是否分叉：`applyToContext` 清空并覆写 `contextMutable()`（2.0.4 `CompactionMiddleware.java:220-233`），`MysqlAgentStateStore` 存的就是压缩后的 `AgentState`，而 harnax 的用户侧历史读 `loadSessionMessages` 那份 —— 同一会话上两份数据何时开始不一致、iOS/web 翻页能否跨过压缩点，需实测。
11. `skills/.usage.json`、`skills/.audit/*.jsonl`、`skills/.curator_state.json` 经 `RuntimeContext.empty()` 在 harnax 的 `RemoteFilesystemSpec`（MinIO）下**实际落到哪个命名空间** —— B3.1 第 6 条的代码事实已定（传的是 empty），落点是实测项。若跨租户共用同一文件，则治理面必须先自建侧车再谈接入。
12. `agent_generate` 在成员上是否真能写 `subagents/<name>.md` 并被下一步装载（成员未 `disableSubagents`，见 A5）；这条决定 A5 的收口清单要不要把它一起列进去。

**源码位置**：harnax 在用版本 2.0.2 的源文件在 `/Users/heqingsong/code/opensource/m2-sources/agentscope-harness-2.0.2/` 与 `…/agentscope-core-2.0.2/`；上游最新检出 `release/2.0.4`（`2.0.4-SNAPSHOT`）在 `/Users/heqingsong/code/opensource/agentscope-java/`，官方 v2 中文文档在 `agentscope-java/docs/v2/zh/docs/harness/`（`architecture`/`channel`/`compaction`/`filesystem`/`memory`/`plan-mode`/`sandbox`/`skill`/`subagent`/`workspace`）、`docs/v2/zh/docs/others/going-to-production.md` 与 `docs/v2/zh/service/`（平台层，自标预览）。
