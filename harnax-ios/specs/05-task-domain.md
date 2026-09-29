# Harnax iOS — 定时任务域实现规格（`/agent/task`）

范围：FEATURES.md §4 的七项 v1 能力——任务列表（含下次执行时间、轮询刷新）、新建/编辑表单（cron 预设与表达式、目标智能体、提示词）、启停、删除、立即执行、执行日志列表（筛选 + 分页）、单条日志详情、停止运行中的执行（`harnax-ios/FEATURES.md:68-79`）。任务失败推送为 v1.1，不在本规格。
所有锚点均为实际读过的 `相对路径:行号`。

> 架构事实（必须先知道）：本域自 release 2 起已从 `harnax-admin` 迁到独立服务 `harnax-scheduler`。`harnax-admin` 侧只剩一层转发代理，`harnax-webui`/iOS/CLI 调用的仍是 `harnax-admin` 的 `/api/admin/agent-tasks/**`，admin 把请求逐字节转发到 scheduler 的 `/api/scheduler/agent-tasks/**` 并把响应原样回传（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:23-44`、`:47`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt:109-134`）。**iOS 只对接 admin 路径**，但字段/校验/错误码的真正定义在 scheduler，本规格的字段级锚点因此大量落在 `harnax-scheduler/`。

---

## 0. 全局约定（iOS 网络层必须先落地）

- **统一响应包装 `ResultVo<T>` = `{ code, message, data, timestamp }`**（`harnax-common/src/main/kotlin/com/agnetix/harnax/common/dto/ResultVo.kt:10-22`）。`code == 200` 才算成功（`:61`），其余都是业务失败。成功工厂 `success()` → `code 200, message "success", data null`（`:31`）；`success(data)` → `data` 填充（`:37`）；`error(message)` → **`code 500`**（`:49`）；`error(code, message)` → 指定 code（`:55`）。iOS 判定只看 body 的 `code`，**不看 HTTP 状态**（见下条）。
- **业务失败仍是 HTTP 200 + body code**：admin 的转发代理对 scheduler 返回的错误状态码「不做任何处理」，直接把 body 里的 `ResultVo` 原样回传（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt:127` 的 `onStatus({...isError},{})` 注册为空操作，意图见 `:96-108` 注释）。因此 iOS **必须在 decode 出 `ResultVo` 后按 `code != 200` 抛错并把 `message` 原文呈现**，不能只判 HTTP 码。这一条与系统域一致（`harnax-ios/specs/03-system-domain.md:8`、`:340`；webui 判据 `harnax-webui/src/requestErrorConfig.ts:80-91`）。
- **本域错误 message 不走 i18n**：所有拒绝文案都是 scheduler 里的**硬编码英文**（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:76,81,87,119,134,144,163,182,186`、`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerController.kt:37,49,54,69,149,172`），bean 校验消息来自注解里的 `message="..."` 也是英文（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskCreateRequest.kt:24-25,29,36,40,50`）。admin 只是逐字节转发，`Accept-Language` 对本域错误**无效**。iOS 直接展示原文，不要期待本地化。（对照系统域：那边错误走 `error.*` i18n key，本域没有——这是两域必须区别对待的地方。）
- **请求头**：沿用全局——`Authorization: Bearer <accessToken>`、`X-Tenant-ID`、`Accept-Language`（`harnax-ios/specs/03-system-domain.md:9`）。**但本域不受租户影响**：`agent_task.tenant_id` 只是创建时快照，读写都不带租户谓词（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:40-42`；日志读同理 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskLogQueryServiceImpl.kt:49-52`）。可见性与权限的门是「用户名」：admin 用 JWT 认证后，把用户名放进 `X-Forwarded-User` 头转发给 scheduler，由 scheduler 按 creator 判定（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt:54-60,222-224`；scheduler 侧读 `CallerContext.username`，`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:236-237`）。
- **分页体 `Page<T>` = 七个 key**：`pageNum / pageSize / total / records / pages / hasPrevious / hasNext`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/Page.kt:14-36`）。`pageNum/pageSize/total` 是 `Long`；`pages/hasPrevious/hasNext` 由 getter 派生也会序列化进 JSON；`records` 为 null 时是空数组（默认 `emptyList()`，`:18`）。列表页与日志页共用这一个信封。
- **null 字段在 JSON 里直接缺失**：admin 与 scheduler 都配了 `default-property-inclusion: non_null`（`harnax-scheduler/src/main/resources/application.yml:33`、`harnax-admin/src/main/resources/application.yml:25`）。实体里可空的字段（`lastRunStatus`、`lastRunTime`、`startTime`、`endTime`）为 null 时**键不出现**。iOS 解码必须把这些字段当可选，缺失即「无值」，不能按必填解码否则整个对象解码失败。
- **时间字段形状**：服务端配了 `date-format: yyyy-MM-dd HH:mm:ss` + `time-zone: GMT+8`（`harnax-scheduler/src/main/resources/application.yml:31-32`）。但这些是 `LocalDateTime`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTask.kt:56,59,66` 等），Spring 的 `date-format/time-zone` 是否作用于 `java.time` 存在不确定性（见 §7 未确认 3）。**请求方向是确定的**：iOS→admin 的时间过滤参数必须是 `yyyy-MM-dd HH:mm:ss` 空格分隔字符串（webui 同形，`harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:53-54`；后端按字符串比较 `AgentTaskMapper` 里 `start_time >= #{startTimeFrom}`，`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:151-156`；测试实参 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskControllerTest.kt:506-507`）。**响应方向** iOS 采用宽松解析（同时接受 ISO `T` 分隔与空格分隔，无时区后缀按服务器本地 GMT+8 处理）。
- **认证前提**：iOS 调 admin，`JwtAuthenticationFilter` 先校验 bearer，未到登录态即 401（沿用全局，`harnax-ios/specs/03-system-domain.md:10`）。scheduler 端无用户凭据，缺 `X-Forwarded-User` 时以 "Not logged in"（RuntimeException → 经转发折叠为 code 500）拒绝——iOS 只要保证带合法登录态即可。

---

## 1. 接口清单（admin 侧，iOS 实际调用的路径）

全部挂在 `@RequestMapping("/api/admin/agent-tasks")`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:47`）。前端 service 一一对应 `harnax-webui/src/services/ant-design-pro/agentTask.ts`。除 `GET /agents` 由 admin 本地应答外，其余全部转发 scheduler（`withAgentName` 在 create/update 前给 body 盖 `agentName`，`:88,100,230-239`）。

| # | 方法 + 完整路径 | admin 锚点 | 请求 | 成功响应（`data`） | iOS v1 是否用 |
|---|---|---|---|---|---|
| 1 | `GET /api/admin/agent-tasks/page` | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:55-72` | query `name?,agentId?,taskStatus?,pageNum=1,pageSize=10` | `Page<AgentTask>`（18 字段，见 §2） | ✅ 列表 |
| 2 | `GET /api/admin/agent-tasks/{id}` | `:74-83` | path `id` | 单条 `AgentTaskResponse`（不存在 → code 500 "Agent task not found"，scheduler `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:81-84`） | ❌（web 未用；iOS 表单回填走列表行缓存即可） |
| 3 | `POST /api/admin/agent-tasks` | `:85-92` | body `AgentTaskCreateRequest`（§3） | `null`（`Void`） | ✅ 新建 |
| 4 | `PUT /api/admin/agent-tasks/{id}` | `:94-109` | path `id` + body `AgentTaskUpdateRequest`（§3，全字段可缺省） | `null` | ✅ 编辑 |
| 5 | `DELETE /api/admin/agent-tasks/{id}` | `:111-121` | path `id` | `null` | ✅ 删除 |
| 6 | `POST /api/admin/agent-tasks/toggle/{id}?status=` | `:127-146` | path `id` + query `status: Int`（`1` 启用 / `0` 停用） | `null` | ✅ 启停 |
| 7 | `POST /api/admin/agent-tasks/{id}/start` | `:148-150` | path `id` | `null` | ❌（与 toggle?status=1 等价，web/iOS 用 toggle） |
| 8 | `POST /api/admin/agent-tasks/{id}/pause` | `:152-154` | path `id` | `null` | ❌（与 toggle?status=0 等价） |
| 9 | `POST /api/admin/agent-tasks/{id}/trigger` | `:156-158` | path `id` | `null` | ✅ 立即执行 |
| 10 | `POST /api/admin/agent-tasks/logs/{logId}/stop` | `:166-168` | path `logId` | `null` | ✅ 停止运行中的执行 |
| 11 | `GET /api/admin/agent-tasks/{id}/logs` | `:170-200` | path `id` + query `taskName?,status?,startTimeFrom?,startTimeTo?,keyword?,pageNum=1,pageSize=10` | `Page<AgentTaskLog>`（14 字段，见 §5） | ✅ 日志列表 |
| 12 | `GET /api/admin/agent-tasks/agents` | `:202-210` | 无 | `Array<{ id: Long, name: String }>`（admin 本地 `agentService.getActiveAgents()` 投影，非转发） | ✅ 表单目标智能体下拉 |

**参数名逐字取后端声明**（`@RequestParam` 名即 query key，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:57-62,129-131,172-180`）：`name / agentId / taskStatus / pageNum / pageSize / status / taskName / startTimeFrom / startTimeTo / keyword`。注意 page 的过滤字段叫 `name`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:58`），而 logs 的过滤字段叫 `taskName`（`:174`）——两个名字不同，iOS 不要合并成一个参数。
**`{id}` 的含义**：列表/详情/写操作里的 `id` 是任务 id；stop 路径里的 `logId` 是执行日志 id（不是任务 id）。日志列表路径的 `{id}` 是任务 id，返回该任务的日志。

---

## 2. 任务列表屏

### 2.1 数据形状（`Page<AgentTask>.records[*]`，18 字段）

后端 page 直接返回**实体** `AgentTask`（非 DTO），JSON 键即实体字段（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:72` 返回 `Page<AgentTask>`；实体 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTask.kt:8-66`）：

| JSON 键 | 类型 | 可空 | 来源 | iOS 用途 |
|---|---|---|---|---|
| `id` | Long | 否 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTask.kt:14` | 行标识、各写操作 path |
| `tenantId` | Long | 否 | `:16` | 忽略（本域不隔离） |
| `name` | String | 否 | `:20` | 列表主标题 |
| `agentId` | Long | 否 | `:23` | 表单回填 |
| `agentName` | String | 否（缺省 `""`） | `:26` | 副标题「目标智能体」 |
| `prompt` | String | 否 | `:29` | 详情/编辑 |
| `cronExpression` | String | 否 | `:32` | 展示 + iOS 本地算下次执行时间 |
| `taskStatus` | Int | 否 | `:35` | **`0`=已停用/paused，`1`=运行中/running**；驱动状态开关 |
| `concurrent` | Int | 否 | `:38` | `0/1`，编辑回填 |
| `timeoutSeconds` | Int | 否 | `:41` | 编辑回填 |
| `description` | String | 否（缺省 `""`） | `:44` | 详情 |
| `isPublic` | Int | 否 | `:47` | `0/1`，决定是否只读（见 2.4） |
| `creator` | String | 否（缺省 `""`） | `:50` | 判断「我能否写」 |
| `active` | Int | 否 | `:53` | 软删标志；列表恒为 `1`（`harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml:84`） |
| `createTime` | LocalDateTime | 否 | `:56` | 排序/展示 |
| `updateTime` | LocalDateTime | 否 | `:59` | **列表默认排序键（DESC）** |
| `lastRunStatus` | Int? | **是，可为 null/缺失** | `:63`（join 而来） | 上次执行状态徽标；null=从未运行 |
| `lastRunTime` | LocalDateTime? | **是，可为 null/缺失** | `:66`（join 而来） | 上次执行开始时间 |

`lastRunStatus / lastRunTime` **不是 `agent_task` 列**，是 `selectTaskList` 对每任务「最新一条执行日志」LEFT JOIN 得来（`harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml:72-83`：取 `MAX(id)` 的日志行，`last_run_status = 该日志.status`，`last_run_time = 该日志.start_time`）。从未跑过的任务两列均为 null → JSON 里键缺失（non_null），iOS 必须按「无值」处理。

### 2.2 列表列（对齐 web）

Web 列：`name` / `agentName` / `cronExpression` / `lastRunStatus` / `lastRunTime` / `taskStatus`（开关）/ 操作（`harnax-webui/src/pages/agent-task/index.tsx:174-257`）。iOS 精简为卡片行：标题 `name`、副标题 `agentName`、`cronExpression`（等宽展示）、`lastRunStatus` 徽标、`lastRunTime`、启停开关、溢出菜单（日志/立即执行/编辑/删除）。

**`lastRunStatus` 徽标映射**（`harnax-webui/src/pages/agent-task/index.tsx:205-213`；缺省/异常回退到 `0` 的样式 `:213`）：

| 值 | 文案 | 语义 |
|---|---|---|
| null/undefined | Never（从未运行） | `harnax-webui/src/pages/agent-task/index.tsx:202-204` |
| 0 | Failed | 失败 |
| 1 | Success | 成功 |
| 2 | Timeout | 超时 |
| 3 | Running | 运行中（转圈） |
| 4 | Stopping | 停止中 |
| 5 | Stopped | 已停止（用户中断） |

取值定义与状态机见 §5.1；写入时机见 §5.2。

### 2.3 列表的筛选、排序、分页

- 过滤参数：`name`（模糊，`t.name LIKE CONCAT('%',#{name},'%')`，`harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml:86-88`）与 `taskStatus`（等值 `= #{taskStatus}`，`:92-94`）。`agentId` 后端支持但 web 列表未暴露（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:59`），iOS 可不做。
- web 过滤状态：`{ name: '', taskStatus: undefined }`（`harnax-webui/src/pages/agent-task/index.tsx:32-35`），UI 为一个名称搜索框 + 一个状态下拉（仅 `1 Enabled`/`0 Disabled`，`harnax-webui/src/pages/agent-task/index.tsx:288-298`），Reset 清空并回到第 1 页（`:299`）。过滤变更即触发重载（`useEffect([pageNum,pageSize,filters])`，`:62-64`）。
- 排序固定 `ORDER BY t.update_time DESC`（`harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml:95`），iOS 无排序参数可传。
- 分页：`pageNum`/`pageSize`（默认 1/10），服务端 `pageSize` 夹在 `1..1000`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:61-62`）。web 用 `showSizeChanger`（`harnax-webui/src/pages/agent-task/index.tsx:322`）。

### 2.4 可见性与「只读」模型（iOS 必须落地）

列表只返回「公开任务或我创建的任务」：`t.is_public = 1 OR t.creator = #{currentUsername}`（`harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml:85`）。因此列表里会混入**别人的公开任务**——这些在 iOS 上必须视为**只读**：写操作（编辑/删除/停止/启停）只允许 `creator == 当前用户名`，否则后端会以 400「Only the task creator ...」拒绝（见 §4）。判定口径：iOS 用 `record.creator == currentUser.username` 决定是否灰化写操作，别让用户点了才发现没权限。

### 2.5 轮询刷新（Web 节奏 + 触发条件，逐字回代码）

- **触发条件**：当前页任务里存在 `lastRunStatus === 3 || lastRunStatus === 4`（Running 或 Stopping）时开启轮询（`harnax-webui/src/pages/agent-task/index.tsx:67-68`）。
- **节奏**：`setInterval(..., 3000)`，即**每 3000ms** 重新 `loadTasks()`（`harnax-webui/src/pages/agent-task/index.tsx:70-72`）。
- **停止/清理**：`useEffect` 依赖 `[tasks]`，每次重载后重算是否有运行中项；没有就 `clearInterval` 且置空（`harnax-webui/src/pages/agent-task/index.tsx:74-80`）。这是「数据驱动的开/关」，不是恒定轮询。
- 另外：立即执行成功后延迟 `setTimeout(loadTasks, 1500)` 刷一次（`harnax-webui/src/pages/agent-task/index.tsx:129`）；启停/删除成功后立即 `loadTasks()`（`:89,153`）。
- iOS 适配：把这套「有 3/4 状态才轮询、否则不轮询」搬成 `Task`/`Timer` 循环，间隔同为 3s；`scenePhase != .active` 时暂停、回前台续跑（与 §7 iOS 注意点 2 一致）。

### 2.6 「下次执行时间」——服务端不产出，iOS 本地计算

**关键事实：整条链路没有「下次执行时间」这个字段。**
- 响应实体 `AgentTask` 的 18 个字段里没有 `nextRun*`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTask.kt:8-66`），`AgentTaskResponse` 同样没有（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskResponse.kt:16-69`）。
- 全仓 grep `nextRun/nextExecution/nextFire/fireTime` 只在 job 执行体内部命中一次 `context.scheduledFireTime`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AbstractAgentTaskJob.kt:83`），那是 Quartz 触发某次执行时读的内部时刻，**不进任何 HTTP 响应**。
- web 列表**根本没有**「下次执行时间」列（`harnax-webui/src/pages/agent-task/index.tsx:174-257`），FEATURES.md 把它写进 v1（`harnax-ios/FEATURES.md:72`）是 iOS 侧的新增诉求。

因此 iOS 的「下次执行时间」是**纯客户端计算**：拿 `cronExpression`（6~7 段 Quartz 风格，见 §3.3）+ 服务器时区，本地算下一个匹配时刻。落地要求：
- 计算者：iOS 本地。建议内置一个 Quartz-cron 解析器（字段序：秒 分 时 日 月 周 [年]，`?` 通配，周用 `MON/SUN` 名，见 §3.3 预设样本 `harnax-webui/src/pages/agent-task/components/TaskForm.tsx:99-105`）。
- 形状：一个本地日期时间，按用户设备 locale 展示。
- 时区：cron 由服务端 Quartz 在**调度节点默认时区**触发——配置层面 MySQL/序列化都指向 `GMT+8 / Asia/Shanghai`（`harnax-scheduler/src/main/resources/application.yml:14,31-32`；job 内用 `ZoneId.systemDefault()`，`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AbstractAgentTaskJob.kt:83`）。iOS 计算「下次执行」时应以 **GMT+8** 作为 cron 的解释时区，再转本地时区显示；该口径依赖「调度机就是 GMT+8」这一部署事实，列入 §7 未确认 3。
- 停用任务（`taskStatus == 0`）：Quartz 侧无触发器（启停走 `pause`→`unscheduleTask`，`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:426-433`），**没有下次执行**。iOS 对 `taskStatus==0` 一律显示「已停用 / —」，不显示本地算出的时间。

---

## 3. 新建 / 编辑表单

表单弹窗 `harnax-webui/src/pages/agent-task/components/TaskForm.tsx`。新建走 `POST /api/admin/agent-tasks`（body `AgentTaskCreateRequest`），编辑走 `PUT /api/admin/agent-tasks/{id}`（body `AgentTaskUpdateRequest`）。**编辑复用同一套字段，但后端 update 所有字段可缺省**（partial，见 §3.2）。

### 3.1 字段矩阵（create 语义）

| 字段（JSON 名） | 控件（web） | 必填 | 前端校验 | 后端校验（scheduler） | 缺省/初始 | 备注 |
|---|---|---|---|---|---|---|
| `name` | Input | ✅ | 仅 required（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:128-134`） | `@NotBlank` + `@Size(max=128)`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskCreateRequest.kt:23-26`）；且**全局唯一** `uk_name`（`harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql:229`），重名拒绝（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:74-77`） | — | iOS 需自加 maxLength=128（web 未拦，后端会 400） |
| `agentId` | Select 可搜索 | ✅ | 仅 required（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:136-152`） | `@NotNull`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskCreateRequest.kt:28-30`） | — | 选项来自 `GET /agents`（§1 #12）；`agentName` 快照由 admin 解析后盖进 body（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:230-239`），**iOS 不用自己填 agentName** |
| `prompt` | TextArea(3 行) | ✅ | 仅 required（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:154-163`） | `@NotBlank`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskCreateRequest.kt:35-37`）；**无长度上限**（列是 TEXT，`harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql:216`） | — | iOS 不要设 maxLength |
| `cronExpression` | Input | ✅ | 仅 required（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:165-171`） | `@NotBlank`（`:39-41`）+ **结构性**校验：按空白切分字段数须 6~7（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:222-225`），否则「Invalid cron expression」 | 占位符 `0 0 9 * * ?`（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:170`） | 校验只数字段数，不校验语义（见 3.3） |
| `timeoutSeconds` | InputNumber `min=30 max=3600` | ❌ | 无 rules，仅控件限幅（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:195-202`） | 无注解校验，DTO 默认 300（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskCreateRequest.kt:46-47`）；DB 列默认 300（`harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql:220`） | 初始 300（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:36-40`） | iOS 沿用 30..3600；越界仅前端拦 |
| `concurrent` | Switch | ❌ | 无（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:204-213`） | 无注解，DTO 默认 0（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskCreateRequest.kt:43-44`） | 初始 false（`:38`） | 提交时 `bool→1/0`（`:63`） |
| `description` | TextArea(2 行) | ❌ | 无（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:217-225`） | `@Size(max=512)`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskCreateRequest.kt:49-51`）；DB 列 `VARCHAR(512)`（`harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql:221`） | 空串 | iOS 自加 maxLength=512 |
| `isPublic` | Switch（Public/Private） | ❌ | 无（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:227-236`） | 无注解，DTO 默认 0（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskCreateRequest.kt:53-54`） | 初始 false（`:39`） | 提交时 `bool→1/0`（`:64`）；影响可见性（§2.4） |

- 表单**没有** `taskStatus`/`active`/`creator`/`tenantId` 字段：新建时 `taskStatus` 由后端强制置 `0`（默认停用，`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:97`），`active=1`、`creator=当前用户名`、`tenantId=CallerContext 或 1` 均由后端填（`:102-105`）。iOS 提交体里不要带这些。
- iOS 提交前的 `bool→0/1` 转换、缺省值填充，对齐 `harnax-webui/src/pages/agent-task/components/TaskForm.tsx:61-65`（web 在 `handleSubmit` 里把 `concurrent`/`isPublic` 从布尔转 `1/0`）。

### 3.2 编辑差异（update 语义：partial，不覆盖未提交字段）

后端 `AgentTaskUpdateRequest` 每字段可空（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskUpdateRequest.kt:16-45`），service 逐字段「非 null 才改」（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:122-154`）。要点：
- 打开编辑时用列表行数据回填，`concurrent === 1 → true`、`isPublic === 1 → true`（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:29-33`）。
- **`name` 仅在改名时**才查重（`:122-128`）；**`cronExpression` 仅在提供时**才校验（`:131-135`）；**换 `agentId` 时才需要新 `agentName`**，否则保留存量快照（`:139-147`）——iOS 走 admin 转发即可，admin 会用 body 的 `agentId` 解析并盖 `agentName`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:100,230-239`）。
- ⚠️ **编辑会把任务重置为停用**：`task.taskStatus = 0` 无条件（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:156-157`）。即「保存即暂停」——iOS 编辑成功后必须重新拉列表，且要在文案上让用户知道这次保存会停掉调度。
- iOS 编辑提交：可只发改动字段（partial），但注意 `agentId` 若不变则不发（避免触发 `agentName` 解析分支）。

### 3.3 cron：预设 ↔ 自定义表达式的互斥关系与映射

- UI 形态：一个 `cronExpression` 文本框（可手填），其下挂 5 个「预设」链接；点预设 = 直接 `form.setFieldsValue({ cronExpression: preset.value })` 把表达式写进同一个文本框（`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:180-188`）。**没有独立的「预设/自定义」二选一开关**——预设只是填表助手，最终提交体里永远只有一个字符串 `cronExpression`。iOS 若做「预设 chip + 自定义输入」，落库仍只提交 `cronExpression` 一个字段，二者天然互斥由「同一字段只有一个值」保证；点预设即覆盖手填值。
- **预设 → 表达式映射表**（逐字 `harnax-webui/src/pages/agent-task/components/TaskForm.tsx:99-105`，6 段 Quartz 风格 cron）：

| 预设 i18n id（逐字） | 展示文案 | cronExpression 值 |
|---|---|---|
| `pages.agentTask.cron.preset.every5min` | Every 5 minutes | `0 */5 * * * ?` |
| `pages.agentTask.cron.preset.everyHour` | Every hour | `0 0 * * * ?` |
| `pages.agentTask.cron.preset.everyDay9` | Every day at 9:00 | `0 0 9 * * ?` |
| `pages.agentTask.cron.preset.everyMonday9` | Every Monday at 9:00 | `0 0 9 ? * MON` |
| `pages.agentTask.cron.preset.everyDay0` | Every day at 0:00 | `0 0 0 * * ?` |

- 字段语法：Quartz 6 段 `秒 分 时 日 月 周`（可选第 7 段年），日/周其一必须是 `?`（见样本）。`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:212-225` 明确：后端只数字段数（6~7），**不做语义校验**；像 `0 0 0 * * *`（日与周冲突）这类能通过结构校验、却被 Quartz 在建 trigger 时拒绝的表达式，**要到启用/触发那一步才报错**（见 §4「toggle 转达 start 的原因」）。iOS 表单层可自加一层「6 或 7 段、日/周至少一为 `?`」的轻校验以尽早提示，但不得替代后端拒绝。

---

## 4. 写侧拦截：删除 / 停用被拒时后端返回什么

iOS 判定一律看 `ResultVo.code`；`message` 是可直接展示的英文原文（本域不 i18n，§0）。被 HTTP 200 承载，业务码在 body。

### 4.1 拒绝路径与 code/message 对照

| 场景 | 触发点 | body `code` | body `message`（原文） | 锚点 |
|---|---|---|---|---|
| 编辑/删除**别人的任务** | `updateById` / `deleteById` 带 `creator=#{currentUsername}` 谓词，命中 0 行 | **400** | `Only the task creator can modify this task` / `Only the task creator can delete this task` | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:161-164,185-186`；谓词 `harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml:64,69`；异常→ResultVo 见 scheduler `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:116-120,135-137` |
| 编辑/删除/启停**不存在的任务**（或对你不可见） | `selectById(id, username)` 返回 null | **400** | `Agent task not found` | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:118-119,181-182,197-198` |
| 新建重名 | `selectByName` 命中 | **500**（见 4.2） | `Failed to create agent task: Task name already exists` | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:74-77` |
| cron 字段数非 6/7 | 结构校验 | **500**（create，见 4.2）/ **400**（update） | `... Invalid cron expression` | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:80-82,131-134` |
| 停用再启用时 cron 语义非法（Quartz 拒绝） | `toggle?status=1` → `start` → 建 trigger 抛 | **500** | `Failed to start task: CronExpression '...' is invalid` | 转发链 scheduler `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:147-164`（转达 start 的 message，`:160-163`）+ `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerController.kt:45-60` |
| 调度实例被禁用（`scheduler.enabled=false`） | toggle/start/pause/trigger 前置 gate | **40903** | `Scheduling is disabled on this instance` | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerController.kt:167-173,184`；常量 `harnax-webui/src/pages/agent-task/constants.ts:25` |

`SchedulerBizException` 单参构造默认 `code = 400`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/support/SchedulerBizException.kt:16`），scheduler 的 advice 把它转成 `ResultVo.error(code, message)`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/config/SchedulerWebConfig.kt:59-63`，无 `@ResponseStatus` → **HTTP 200**）。

### 4.2 一个必须知道的不对称：create 的业务拒绝是 500，update/delete 的是 400

scheduler 的 `update`/`delete` **显式 catch `SchedulerBizException`** 并原样带出 code（400/40902）（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:116-120,135-137`）；但 `create` 只有 `catch (e: Exception)`，于是 create 里抛的 `SchedulerBizException`（重名 / invalid cron / Agent not found）被折叠成 `ResultVo.error("Failed to create agent task: ${e.message}")` → **code 500**（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:99-101`）。iOS 不能假设「创建的业务失败一定 400」；判失败只看 `code != 200`，展示 `message`。
而 `name`/`prompt`/`agentId`/`cronExpression` 为空、`name`/`description` 超长这类**是** `@Valid` 在进入方法前被拦，走校验 advice → **HTTP 400 body code 400 `field: 消息`**（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/config/SchedulerWebConfig.kt:68-77`；注解定义 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskCreateRequest.kt:23-54`）。所以 create 的校验失败(400，形如 `name: Task name must not exceed 128 characters`) 与 create 的业务失败(500) 是两条不同路径。

### 4.3 40902 / 40903：改了但没排上，属于成功而非失败

`update`/`delete` 数据已提交，但提交后向共享 Quartz store 的 reconcile 没收敛时，抛 `SchedulerBizException(40902, "Task saved/deleted, but the scheduler did not reload: ...")`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:264-293,302`；create 不触发，见 4.2）。iOS 遇 **40902** 应**按已完成处理**（关表单/移除行 + 刷新列表 + 仅提示 warning），文案对齐 web：`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:85-91`（编辑 40902 → warning + `onSuccess`）、`harnax-webui/src/pages/agent-task/index.tsx:158-162`（删除 40902 → warning + 刷新）。常量 `CODE_SCHEDULER_SYNC_FAILED = 40902`（`harnax-webui/src/pages/agent-task/constants.ts:19`）。
**40903** 是「请求发到了不接受调度写作的节点」，重试/回滚都不是调用方该做的（`harnax-webui/src/pages/agent-task/constants.ts:21-25`）；iOS 原样报错即可，不要自动重试。

---

## 5. 执行日志

### 5.1 数据形状（`Page<AgentTaskLog>.records[*]`，14 字段）

日志 page 直接返回**实体** `AgentTaskLog`（非 DTO；scheduler `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:206-223` 返回 `Page<AgentTaskLog>`），JSON 键即实体字段（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskLog.kt:8-54`）：

| JSON 键 | 类型 | 可空 | 锚点 | 说明 |
|---|---|---|---|---|
| `id` | Long | 否 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskLog.kt:14` | 日志 id；stop path 用它 |
| `taskId` | Long | 否 | `:17` | 所属任务 |
| `taskName` | String | 否（缺省 `""`） | `:20` | 详情展示 |
| `prompt` | String | 否（缺省 `""`） | `:23` | 列表截断展示 |
| `response` | String | 否（缺省 `""`） | `:26` | agent 返回全文 |
| `sessionId` | String | 否（缺省 `""`） | `:29` | 详情展示；格式 `task-{taskId}-{agentId}-{uuid}`（`harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql:239`） |
| `status` | Int | 否 | `:32` | **0..5**，见下 |
| `errorInfo` | String | 否（缺省 `""`） | `:35` | 失败/停止原因 |
| `tokenUsage` | String | 否（缺省 `""`） | `:38` | **不是 JSON**：写入的是 `TokenUsage` data class 的 `toString()`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:493` → `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:62-68`），形如 `TokenUsage(inputTokens=1240, outputTokens=386, totalTokens=1626, costTime=68.4, timestamp=…)`；列注释 `Token usage JSON`（`harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql:242`）与 `AgentTaskLog.kt:37` 的 schema 描述都与此不符。iOS **不展示**（web 详情 `harnax-webui/src/pages/agent-task/components/LogDetailModal.tsx` 无此字段，草图 C6 亦无），字段名不属本路由契约，不解析 |
| `startTime` | LocalDateTime? | **是** | `:41` | 运行中已写；异常行可缺省 |
| `endTime` | LocalDateTime? | **是** | `:44` | 运行中为 null → 键缺失 |
| `durationMs` | Long | 否 | `:47` | 毫秒 |
| `creator` | String | 否（缺省 `""`） | `:50` | = 任务 creator |
| `createTime` | LocalDateTime | 否 | `:53` | 列表排序键（DESC） |

> ⚠️ 注意 DTO 陷阱：`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskLogResponse.kt:35-36` 的 schema 注释把 status 只写成 `0:failed,1:success,2:timeout,3:running`（漏 4/5），且该 DTO **不参与列表**（列表发的是实体）。以实体 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskLog.kt:31-32` 的注释为准：`0:failed, 1:success, 2:timeout, 3:running, 4:stopping, 5:stopped`，默认值 `3`。web 三处映射表都覆盖了 0–5（列表 `harnax-webui/src/pages/agent-task/index.tsx:205-213`、日志 `harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:174-181`、详情 `harnax-webui/src/pages/agent-task/components/LogDetailModal.tsx:18-25`）。

### 5.2 状态枚举全取值 + 写入时机

`agent_task_log.status`（列默认 `1`，注释陈旧只列 0–2，见 `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql:240`；实际全取值以代码为准）：

| status | 含义 | 谁写、何时写 | 锚点 |
|---|---|---|---|
| 3 | Running | 执行开始时插入 running 行即 `status=3` | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:564-596`（`insertRunningLog`，`:575`），`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskLog.kt:32` 默认 |
| 1 | Success | router.chat 正常返回，行仍为 3 时 `finishExecution` 写 1 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:491-495,516`；`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:78-87` |
| 0 | Failed | router.chat 抛异常，`errorInfo`=异常消息截 4000，`finishExecution` 写 0 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:496-499,516`；`:499` |
| 4 | Stopping | 用户点停止，`markStopping` 把 3→4（`error_info="Stopping..."`），即时反馈 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:682-691`；`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:68-71` |
| 5 | Stopped | 执行线程观察到行为 4 后收尾（`finalizeStopped`，`errorInfo="Task stopped by user"`），或 stop 时 router 回报「无实活」由 stop 直接写 5 | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:616-621`（closeOutLateExecution 的 4 分支）、`:707-732`（Missed 分支）；`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:90-99` |
| 2 | Timeout | 僵尸回收：`expireStale` 把仍处 3/4 且超过 `1.5×` 有效超时的行改 2（`error_info="Auto-expired..."`） | `harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:185-196`；触发点 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:814-827`、启动/清扫 `:347,355-356` |
| 2→{0,1,5} | 回收后被证伪 | 线程回来后按真实结果覆盖被 reaper 判成 2 的行（`reclaimExpired`，仅动 status=2） | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:646-667`；`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:115-123` |

- `finishExecution` 只在 `status=3` 时命中（`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:78-87`）；抢不到（0 行）说明有人（用户停止 → 4，或 reaper → 2）先动了这行，走 `closeOutLateExecution` 分派（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:616-676`）。这解释了为何一次运行的终态可能是 0/1/2/5 中任一个，iOS 不能假设「跑完只剩 0/1」。
- 有效超时 = `agent_task.timeout_seconds`（0/NULL 回退 `scheduler.timeout-seconds` 默认 300）；`expireStale` 用的死线是 `1.5×`（`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:190-195`，`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:54,761-787`）。
- 字段配对不变量（谁写哪一列推出来的，造数据与判读都要照它）：`status=0` 的行 **不可能**有 `response` 或 `tokenUsage`——这两个赋值在 try 分支，异常路径一行都不写（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:491-499`）；`status=5` 可以同时有 `response`、`tokenUsage` 与 `errorInfo="Task stopped by user"`，因为 `finalizeStopped` 写的是执行线程自己手里的值（`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:90-99`）；`status=2` 由 reaper 写，只改 `status/end_time/error_info/duration_ms`，故它必无 `response`；`status=3/4` 的 `duration_ms` 恒为插入时的 `0`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskLog.kt:47`，无任何语句在收尾前写该列），所以「已跑多久」在行上读不出来；`concurrent=0` 的任务同时最多一行 3/4（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:450-454` 的 `runTaskOnce` 卫兵，判据 `:761` 的 `blocksManualRun` = `concurrent == 0 && hasActiveRunningExecution`，命中回 40901），且 join 到任务上的 `last_run_*` 就是这一行（`harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml:72-83`）——列表徽标与日志首行必须同源。

### 5.3 日志列表：筛选参数、分页、排序

入口：列表行「日志」按钮打开日志弹窗（`harnax-webui/src/pages/agent-task/index.tsx:242-244`）；「立即执行」成功后自动打开对应任务日志（`harnax-webui/src/pages/agent-task/index.tsx:114-119`）。端点 `GET /api/admin/agent-tasks/{id}/logs`（`{id}` = 任务 id）。

| 筛选 | web 控件 | 请求参数（逐字） | 后端谓词 | 锚点 |
|---|---|---|---|---|
| 时间区间 | RangePicker（带时分秒） | `startTimeFrom` / `startTimeTo`，格式 `yyyy-MM-dd HH:mm:ss` | `start_time >= #{startTimeFrom}` / `start_time <= #{startTimeTo}` | `harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:53-54,267-276`；`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:151-156` |
| 状态 | Select（6 项 1/0/2/3/4/5） | `status` | `l.status = #{status}` | `harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:277-291`；`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:148-150` |
| 关键字 | Input（回车即查） | `keyword` | `prompt OR response OR error_info LIKE %keyword%` | `harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:292-299`；`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:157-161` |
| 任务名 | （弹窗内不暴露；后端有此参） | `taskName` | `task_name LIKE %..%` | 声明 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:174`；谓词 `harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:145-147` |

- 分页：`pageNum`/`pageSize`（默认 1/10），服务端夹 `1..1000`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskLogQueryServiceImpl.kt:45-46`）。排序固定 `ORDER BY l.create_time DESC`（`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:162`）。
- 可见性：日志读经所属任务门禁 `t.active=1 AND (t.is_public=1 OR t.creator=#{username})`（`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:136-141`）——别人公开任务的日志也**可见**（读比停宽）。已删任务（active=0）的日志被 join 掉（`:128-130` 注释）。
- 列表列（`harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:183-248`）：状态徽标 / `prompt`（截 80）/ `response`（截 60，空显示 `-`）/ `durationMs`（显示 `(ms/1000).toFixed(1)+"s"`，`:219`）/ `startTime` / 操作（仅 `status===3` 才出「停止」按钮，`:232`）。
- 轮询：弹窗打开后自调度——**有 3/4 状态的行时 3000ms，否则 5000ms**（`harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:77-113`，判据 `l.status===3||l.status===4` 在 `:85-86`）；弹窗关闭时清空并回调父级刷新任务列表（`:121-132`）。iOS 日志页同样数据驱动开/关轮询，前后台规则见 §6。

### 5.4 单条日志详情：**无独立端点**

FEATURES.md 写「单条日志详情 · 依赖日志详情接口」（`harnax-ios/FEATURES.md:77`），但**后端没有 GET log/{id} 端点**（service 全表见 `harnax-webui/src/services/ant-design-pro/agentTask.ts`，无该方法；scheduler 控制器也无 `GET /logs/{id}`）。web 的详情弹窗 `harnax-webui/src/pages/agent-task/components/LogDetailModal.tsx` 直接渲染列表已有的那一行 `AgentTaskLog`（`harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:318-319,332-335` 把 row 塞给详情），运行中的行靠列表轮询把新值同步进 `selectedLog`（`harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:61-66,101-105`）。
详情展示字段（`harnax-webui/src/pages/agent-task/components/LogDetailModal.tsx:37-99`）：`taskName` / `status`（徽标）/ `startTime`（空 `-`）/ `endTime`（空 `-`）/ `durationMs`（`/1000` + `s`）/ `sessionId`（空 `-`）；`prompt` 全文块；`response` 全文块（标题「Agent Response」）；`errorInfo` **仅存在时**渲染（红色块）。
iOS 结论：详情页数据 = 列表行的 `AgentTaskLog`（14 字段已全部含在列表响应里），**不需要**再发请求；若要「深链单条日志」，只能重拉该任务日志列表再按 `id` 本地定位（没有按 logId 单读的接口）。

### 5.5 「停止运行中的执行」的真实语义：既改状态、也真中断

端点 `POST /api/admin/agent-tasks/logs/{logId}/stop`（`{logId}` = 日志 id，不是任务 id）。语义（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:682-744`）：
1. 权限门：`requireOwnedLog(logId)` —— **创建者专属**（比日志读更窄：看得到 ≠ 停得了），非创建者/不存在 → 400「Agent task log not found」（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:200-210`；`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:42-48`；scheduler `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:198-203`）。
2. 前置判活：行状态非 3/4 → `stopTask` 返回 false → 控制器 code **500**「Task is not running or already completed」（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerController.kt:144-150`；`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:684-687`）。
3. **真中断 + 状态流转**：先 `markStopping` 把 3→4（即时反馈），再经 router 下发 `INTERRUPT` 命令去**真正打断**正在跑的 agent 会话（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:691,703`；`harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml:68-71`）。
   - router 回报 `Delivered`：行留 4，由执行线程随后 `finalizeStopped` 收尾到 5（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:704-705,616-621`）。
   - `Missed`（无实活可停）：stop 当场把 4→5，`errorInfo="No live execution to interrupt"`（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:707-732`）。
   - `Unanswered`：行停在 4，交给 stale 清扫兜底（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:735-740`）。
4. stop **不受 `scheduler.enabled` gate 拦**（不写 Quartz 对象），因此在停用节点上也能停已在跑的执行（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerController.kt:132-154`）。
成功返回 200「Task stopped」；iOS 停止后按 web 做法延迟一次静默重载日志（`harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:159-161`，`setTimeout(loadLogs(true), 1000)`）。

---

## 6. iOS 适配注意点

1. **一切以 body `code` 为准，且 message 是英文原文**：本域错误不 i18n（§0）。iOS 的 `URLSession`/解码层必须先解出 `ResultVo`，`code != 200` 抛错并把 `message` 原样展示，不看 HTTP 状态、不套本地化表（对照系统域 §7 的处理，`harnax-ios/specs/03-system-domain.md:340`）。
2. **轮询是数据驱动的，且要管前后台**：列表「有 3/4 才轮、3s」`harnax-webui/src/pages/agent-task/index.tsx:67-80`；日志「有 3/4 用 3s 否则 5s」`harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:77-113`。iOS 用 `Task`/`Timer` 实现同频，并在 `scenePhase != .active` 暂停、回前台续跑；单次轮询失败**不终止循环**（对齐日志轮询 `catch { /* ignore */ }` `harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:107`）。离开页面/关闭弹窗必须取消定时器并刷新上游（`harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:121-132`）。
3. **「下次执行时间」客户端自算，停用不显示**：服务端零提供（§2.6）。iOS 需内置 Quartz 6/7 段 cron 解析器（含 `?`、`*/n`、星期名），按 GMT+8 解释再转本地显示；`taskStatus==0` 显示「已停用 / —」。解析器对无法识别的表达式要降级为「—」而非崩溃。
4. **保存即停用**：编辑成功会把 `taskStatus` 打回 0（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt:156-157`）。iOS 编辑返回后必须重拉列表并提示「本次修改已暂停该任务，需手动启用」，否则用户会以为改完还在跑。
5. **别人公开的任务是只读的**：列表混入他人 `is_public=1` 任务（`harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml:85`），写操作仅 `creator==自己`。iOS 用 `record.creator == currentUser.username` 灰化 编辑/删除/启停/停止；日志对这些任务仍**可读**（读比停宽，§5.3/§5.5）。
6. **null 键缺失要当无值解码**：`lastRunStatus/lastRunTime/startTime/endTime` 为 null 时 JSON 里**没有该键**（non_null，§0）。iOS 解码器必须把这些声明为 optional，否则整行解码失败。
7. **成功响应 `data` 恒为 null**：create/update/delete/toggle/trigger/stop 返回 `ResultVo<Void>`（`data:null`；relay 还会显式清空 data，`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:230`）。iOS 只看 `code`，不要从这些响应取数据。
8. **40902 当成功、40903 当环境错误**：见 §4.3。iOS 编辑/删除遇 40902 → warning + 当作完成并刷新；遇 40903 → 直接报错不重试。
9. **时间入参格式固定**：`startTimeFrom/To` 必须是 `yyyy-MM-dd HH:mm:ss`（空格分隔，非 ISO `T`）。iOS 拼请求串时用该格式；`+`/空格/冒号会被 admin 转发时 URL 编码（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt:136-149`），iOS 正常 urlencode 即可。
10. **表单不含后端字段**：提交体只有 `name,agentId,prompt,cronExpression,concurrent(0/1),timeoutSeconds,description,isPublic(0/1)`；`agentName` 由 admin 补、`taskStatus/creator/active/tenantId` 由后端填（§3.1）。iOS 多传无用且可能误导。

---

## 7. 未确认

1. **iOS 是否照搬「管理员专属」门禁**：本域接口在 admin 侧无 `canAccessUserManagement` 类 access（不同于 api-key 页 `harnax-ios/specs/03-system-domain.md:94`），路由仅 `path: '/agent/task'`（`harnax-webui/config/routes.ts:54-57`）无 access。iOS 是否给定时任务 tab 加额外角色门禁，无代码依据，待产品裁定。
2. **「立即执行」是否需要与 cron 停用态解耦**：`trigger` 走 `runTaskOnce`，对停用任务也照样投递一次性执行（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:446-478` 未检查 `taskStatus`），且 `concurrent==0` 且有活跑时回 40901（`:451-454`）。web 故意不做客户端预检、让后端区分「真跑」与「僵尸行」（`harnax-webui/src/pages/agent-task/index.tsx:98-101`）。iOS 是否对停用任务的「立即执行」按钮特殊标注/放行，属产品口径，未定。
3. **响应时间戳的确切 wire 形状与调度时区**：`date-format/time-zone` 是否作用于 `LocalDateTime` 未从代码得到确证（§0），且调度节点真实时区只能由部署保证（`harnax-scheduler/src/main/resources/application.yml:14,31-32` + `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AbstractAgentTaskJob.kt:83` 的 `ZoneId.systemDefault()`）。「下次执行时间」的时区口径与所有 `createTime/startTime/...` 的分隔符形状，需**真机抓一次响应包**定稿；在此之前 iOS 用宽松解析。
4. **create 业务拒绝稳定为 500 而非未来被补 catch**：§4.2 的 create=500  asymmetry 是当前代码事实（`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:99-101`），但与 update/delete=400 不一致；是否会被后端统一，未确认。iOS 现阶段**只判 `code!=200`**，不针对 500/400 分支做差异处理。
5. **列表/日志是否暴露 `agentId` 维度过滤**：后端 page 支持 `agentId` 参数（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:59`），但 web 列表未做该筛选（`harnax-webui/src/pages/agent-task/index.tsx:32-35` 只有 name+status）。iOS 要不要提供按智能体筛选，属产品选择，非接口缺失。
