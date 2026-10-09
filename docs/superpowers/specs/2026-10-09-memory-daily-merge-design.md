# 记忆 · 会话层按日期并入 agent 粒度 · 设计规格

日期：2026-10-09
分支：`feat/memory-daily-merge`（基线 `kotlin-dev` @ `68e8f0e0`）

## 0. 要解决的问题

记忆有两层桶，键都由运行时的 `MinioBaseStore` 生成（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/minio/MinioBaseStore.kt:208`）：

| 层 | 结论对象 | 按日期对象 |
|---|---|---|
| 会话层 | `store/tenants/<t>/users/<u>/agents/<名>/sessions/<sid>/root/MEMORY.md` | `…/sessions/<sid>/memory/<YYYY-MM-DD>.md` |
| agent 长期层 | `…/agents/<名>/root/MEMORY.md` | `…/agents/<名>/memory/<YYYY-MM-DD>.md` |

长期层的结论段有唯一写者：候选批准（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryDraftServiceImpl.kt:229`）。长期层的日期段**没有写者**——晋升提名时只读会话层的日期文件、把内容折进一份新的 `MEMORY.md`，日期本身在并入时丢掉。结果是：

1. 记忆页「Daily Notes」列（`harnax-webui/src/pages/memory/index.tsx:232`）与详情里的按日条目（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:124`）对一个新 agent 永远为空，只有双层结构之前遗留的旧文件会显示；
2. 同一天里三个会话各写了什么，批准后无法在 agent 粒度复原——`sources` 列记录了它们被并入过，但那些对象批准后就被删（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:308`）；
3. 目标形态是 agent 粒度也有一份按日期拆开的记忆，多个会话落在同一天时，那一天的内容合并成 agent 的同一个日期文件。

现有 `memory_draft` 一行只携带**一个**目标文本（`merged_md`）与**一个**前提版本（`base_version`），要写 `1 + K` 个对象就必须先解决这个模型冲突，否则第二份文本没有前提版本可校，批准就可能覆盖一份没人读过的日期文件。

## 1. 决策清单

| # | 决策 | 取值 |
|---|---|---|
| D1 | 目标集合 | 一条候选写 `1 + K` 个对象：结论层 `root/MEMORY.md` 一份 + 每个日期 `memory/<date>.md` 一份 |
| D2 | 队列形状 | **一条候选带多目标**。不拆成 `1+K` 行——拆了就没有「这次并入是一个决定」这件事，owner 要逐条点，同日两个会话的串行也失去原子性 |
| D3 | 日期的归属 | 日期取会话层 ledger 文件名里的 `YYYY-MM-DD`，不重新按批准时刻计算 |
| D4 | 并发口径 | **严格 CAS**：批准前对全部 `1 + K` 个目标做版本预检，任一不符⇒整条 `STALE_BASE`、一个都不写；被拒的一方下一窗口重提名（重读当前文本再 merge） |
| D5 | 日期段读者 | **不注入**。长期层的日期文件不进 system prompt，只有 `MEMORY.md` 经 `LongTermMemoryContextMiddleware` 注入。本期只补写者与页面可见性 |
| D6 | 提名规模 | 每次尝试最多 7 个日期，按日期名升序（最早优先），超出的日期留在会话层等下一窗口 |
| D7 | 失败口径 | 任一日期目标的读取或模型调用失败⇒整条不提名，会话层一字不动。不接受「只并入了 3 个日期、另 2 个说不清」的候选 |
| D8 | `targets` 空值 | 列 `NULL` 或空数组都读作「本次并入没有日期目标」，是自然语义而非兼容开关；结论层照旧单独成立 |
| D9 | 结论层输入范围 | 与会话层被并入的日期集合一致：只喂入选中的 ≤7 个 ledger。否则未并的日期会被 `MEMORY.md` 吃进去、其 ledger 却留在桶里，下一窗口二次折叠 |
| D10 | 迁移 | `harnax-admin` 前向增量 `V8`，不清库、不动已应用的 V1~V7 |
| D11 | iOS | 本轮不做。记忆域 iOS 落点尚未确认 |

## 2. 数据模型

### 2.1 `memory_draft` 的列变化

| 列 | 现在 | 改后 |
|---|---|---|
| `targets` `mediumtext` | — | 加，`NULL`；JSON 数组，形状见 §2.2 |
| `merged_md` `mediumtext NOT NULL` | 结论层新文本 | 不动 |
| `base_md` / `base_version` | 结论层旧文本与其存储版本 | 不动。结论层仍由这三列承载 |
| `sources` `mediumtext NOT NULL` | `{path, content}` 数组 | 不动，但**内容口径收紧**：只列本条候选实际并入过的会话对象（D6/D9 之下最多 `1 + 7` 个） |
| 索引 | `idx_memory_draft_tenant_status`、`idx_memory_draft_session` | 不动：`targets` 只随行整体读，没有按目标检索的读法 |

V7 头注释写着「One row per conversation, not per object」，D2 之后这句仍然成立，但含义变了：一行仍是一次决定，只是这一决定覆盖多个对象。V8 的注释要把这点写进 schema，否则下一个读 DDL 的人会以为一行的 `base_version` 是整个候选的前提。

### 2.2 `targets` 的 JSON 形状

```json
[
  {
    "path": "memory/2026-10-09.md",
    "expectedVersion": 3,
    "baseText": "<agent 长期层该日文件在读取时的正文，对象不存在时为 null>",
    "mergedText": "<本条候选要写入该日文件的完整新正文>"
  }
]
```

- `path` 相对于**长期层桶**，且只能是 `memory/<ISO 日期>.md`。`MEMORY.md` 出现在这里即整条拒绝：结论层的文本与前提已经有三列承载，同一行里出现第二个写它的入口等于两个互不相干的前提版本指同一个对象。
- `expectedVersion` 是该长期对象被 merge 读取时的存储版本，`0` 表示当时不存在、批准要 create。这个取值口径必须与 `MemoryStoreGateway.readCuratedLayer` 给结论层的口径一致（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:228`：无对象⇒`0`），两侧不同名会让一个新建日期文件被读成「前提不符」。
- `baseText` 是审阅证据。owner 批准的是「把这一天换成这份文本」，没有 `baseText` 就只有 `mergedText`，一天被折掉四分之三与一天被忠实合并他在屏上看不出区别；§3.4 的缩水闸只是粗筛，兜不住。
- 序列化：与 `sources` 完全同一条路径——`MemoryDraftCodec.targetsJson` 按 `path` 升序 canonical 写出（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/MemoryDraftCodec.kt:28`），`targetsOf` 坏列读成空数组而不是丢行（同文件 `:31`）。排序是因为 merge 读到的顺序不携带任何 owner 要决定的信息，两份同内容的提名必须存成同一段字节。

### 2.3 不变量

- T1 一条候选内 `targets` 的 `path` 互不相同。两个同日目标指同一个对象，第二个 CAS 必然失败，而这只是把一次写拆成了一次必然的拒绝。
- T2 `path` 与 `expectedVersion` 同源：都由提名时那次 `store.get` 读出，不由文件名推算版本。
- T3 `sources` 与本条候选实际并入的会话对象逐字一致（路径与字节都一致）。批准只清 `sources` 里的对象，所以「没并进去的东西被清掉」在这条下不可能发生；反过来「并进去了却留在桶里」也不可能。
- T4 `contentDigest` 覆盖 `targets`。批准回签的摘要若不覆盖要写的字节，owner 就能签一份与库里不同的日期文本，而摘要存在的理由正是防这件事（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/MemoryDraftCodec.kt:48` 的长度前缀字段清单里必须多一项）。
- T5 结论层与日期层对同一段会话材料各表一次：`MEMORY.md` 是跨天蒸馏，日期文件是当天流水。两者都从会话层读，但写入的是不同对象，注入侧只读结论层（D5），因此一段材料不会在 prompt 里出现两遍。

## 3. 运行侧提名

改动集中在 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/MemoryPromoter.kt`。

### 3.1 步骤

`proposeNow()`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/MemoryPromoter.kt:145`）现有形状是「读会话层⇒读长期结论层⇒一次模型 merge⇒提名」。改后：

1. 读会话层（`readCurated` + `readLedgers`，`:305`/`:315`，后者已按名升序）。
2. `ledgers.take(MAX_DAILY_TARGETS)` 取入选日期，`inScope` 即本条候选覆盖的会话对象。未入选的日期既不参与任何 merge，也不进 `sources`。
3. 读长期结论层（现有 `:163`）⇒ 一次模型调用 ⇒ `mergedMarkdown` + `baseMarkdown` + `baseVersion`。这一步的形状与判据完全不动，只把喂给 `userContent`（`:292`）的 ledger 集合换成 `inScope`。
4. 对 `inScope` 的每个日期各做一轮：
   - `store.get(domain.ledgerNamespace(null), "/" + date + ".md")` 读该日长期文件，拿到文本与版本。`MemoryDomain.ledgerNamespace(sessionId: String?)`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/MemoryDomain.kt:61`）传 `null` 即长期层路由。
   - 一次模型调用：输入是「该日现有正文」（空则 `(empty)`）与「该会话该日的 ledger」，输出该日完整新正文。
   - 缩水闸与结论层同一条：`mergedText.length * 2 < existing.length`⇒视为模型丢内容，整条不提名。
   - 组 `MemoryDraftTarget(path = "memory/<date>.md", expectedVersion = item?.version() ?: 0, baseText = existing?.takeIf { it.isNotBlank() }, mergedText)`。
5. 一次 `draftAdaptor.propose(...)` 提交全部：结论层三件 + `targets` + `sources(inScope)`。

`domain.ledgerNamespace(null)` 与 `curatedNamespace(null)` 同族，所以日期段与结论段的桶差异只在那个 route 尾，不会出现日期文件写进会话桶。

### 3.2 失败与预算

`Outcome`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/MemoryPromoter.kt:54`）不增加档位：长期日期对象读不到⇒`STORE_FAILED`（与结论层读不到同一口径），日期 merge 失败/超时/答空/触发缩水闸⇒`MODEL_FAILED`。任一发生⇒`propose` 根本不被调用，会话层一字不动，下一窗口重来。D7 要的就是这个：宁可这一窗口什么都不提，也不提一条「三个日期并了、两个说不清」的候选让 owner 猜。

一次尝试的模型调用预算单独设一条：`TOTAL_MODEL_BUDGET = 25 分钟`，每次调用取 `min(DEFAULT_MODEL_TIMEOUT, remaining)`（单次仍是 `:340` 那 5 分钟），`remaining <= 0` 即 `MODEL_FAILED`。理由不是省时间，而是**一次尝试必须严格短于它自己的节流窗口**：窗口默认 30 分钟（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt:70`，装配处 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:847`），而 `tryClaim` 写时间戳是在领取时而非结束时（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/MemoryPromotionMiddleware.kt:55`）。8 次调用各等满 5 分钟会跑到 40 分钟，越过窗口后同一会话自己会并发起第二次尝试。自重叠不丢数据（同层两次提名，`selectPendingBySession`⇒`updateContent` 后写覆盖前写，摘要变化让屏上那次批准拿到 `DRAFT_CHANGED`），但白花一轮模型调用；预算把这条路封掉。

### 3.3 日期的 merge 提示词

不复用 `MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT`：那份提示词声明的两个输入是「the current MEMORY.md」与「new entries to merge」，并明确要求「Output the complete new MEMORY.md」，用它合日期文件会让模型把两天合成一份结论。日期 merge 用本类自带的提示词，三段：任务（把同一天的两份记录合成一份当日流水，保留事实、去掉重复与已过时的过程性内容）、`MemoryConfigFactory.PROHIBITIONS`（与结论层同一条禁令，防把密钥或他人数据洗进要进队列的文本）、输出契约（只输出该日文件完整正文，不要 diff、不要围栏）。预算算术（`CONSOLIDATION_MAX_TOKENS`）不带：4000 token 是 `MEMORY.md` 的预算，日期文件由 flush 自由追加，给它套同一预算会让 merge 把当天条目删到装得下为止。

### 3.4 提名规模的选择

`MAX_DAILY_TARGETS = 7` 与 §3.2 的预算是一对：最坏 8 次调用，每次在预算内截断。7 不是设计上限而是「一轮能把窗口用满而不越窗」的算术结果，与 T3 一起决定了超出部分的日期必然留桶、必然在后续窗口被并（升序取前 7⇒日期只会被延后，不会被跳过）。

## 4. 批准

`MemoryDraftServiceImpl.approve`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryDraftServiceImpl.kt:196`）现有的闸序是：digest⇒层版本⇒store 可达⇒条件抢占⇒条件写⇒清来源。改后把「层版本」一步扩成「全部 `1 + K` 个目标的版本」预检，其余顺序不动。

### 4.1 预检（不写任何东西）

```
layer        = readCuratedLayer(...)          // 现有一步
dailyReads   = targets.map { readDailyLayer(tenant, user, agent, dateOf(it.path)) }
applyCurated = layer.version == draft.baseVersion
applyDaily(i)= dailyReads(i).version == targets(i).expectedVersion
landed(i)    = !apply(i) && current(i).content == mergedText(i)
```

任一目标既不满足 `apply` 也不满足 `landed`⇒整条 `STALE_BASE`，响应带当前结论层版本（现有 `currentBaseVersion`）并新增 `staleTarget` 指出是哪一个对象动了。每个目标各自幂等：现存正文逐字等于要写的文本即视为已落，不重复写、继续补其余。这不是锦上添花——§4.3 的部分失败重放路径全靠它，否则一条候选写成了 3 个日期里的 2 个就永远补不完。

### 4.2 写序

抢占（`markReviewed`，条件 `status='PENDING'`）之后：**先写 K 个日期账本，最后写结论层**。这个顺序是有意的：结论层是注入进每一次模型调用、也是页面上「这个 agent 记得什么」的主视图，日期账本没有读者（D5）。把有读者的那一个放最后，§4.3 的半程失败就最可能停成「日期齐了但结论层还是旧的」——那是一份可解释、可重放、且对会话行为零影响的状态；反过来会得到一份已经进 prompt 的、日期层却没跟上的一致性缺口。

每个目标各自条件写（版本＋对象自身 ETag），沿用 `writeCuratedIfVersion`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:245`）那两条：版本先在本进程比一次，`If-Match`/`If-None-Match: *` 再到存储层比一次，412 是答案不是故障、返回 false；其他异常抛出。

### 4.3 失败与回滚

任一 `false`⇒抛 `BizException(409)`。抢占在事务里（`@Transactional(rollbackFor = [Exception::class])`），于是行退回 `PENDING`，owner 重读看到的还是待决候选；桶里已经写成功的对象**留着**——它们逐字等于候选要写的字节，正是 §4.1 的 `landed`，所以下一次批准把剩下的补完即可。清来源（`clearSessionSources`）仍旧排在所有写之后，一个字节都没写成功的拒绝路径不会清掉任何东西。

### 4.4 响应

`MemoryDraftDecisionResponse`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftDecisionResponse.kt:20`）加两枚可空字段：`dailyTargetsApplied: Int`（本次写成功或已就位的天数）与 `staleTarget: String?`（`STALE_BASE` 时动过的那个对象的 path）。`longTermVersion` 语义不动，仍只指结论层。日期层的版本不进响应：屏上没有按目标分别显示的读法，而多报 K 个数字会让 owner 以为那是他要核对的东西。

## 5. 同日两个会话

两个会话各写 `memory/2026-10-09.md`，两条候选的 `expectedVersion` 都是同一次读到的值。先批准者写成功、版本 +1；后批准者预检失败⇒整条 `STALE_BASE`，`sources` 一个都不清（清排在写之后）。它的会话层于是还在，下一窗口 `hasUnpromotedContent`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/MemoryPromoter.kt:124`）仍然为真，提名会读到已经并了第一个会话的那天，merge 出「那天有两个会话」的文本，前提版本是新版本。owner 侧的动作只有「再批准一次」，第二份内容不会覆盖第一份——这就是 D4 选择严格 CAS 而不是自动重跑 merge 的原因：重跑要在服务端调模型，而服务端没有装配侧的那份模型，也不该在一个人点下批准的时候决定再花一次他的 token。

清来源判据一字不改：只在字节未动时清（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:336`）。会话还在写，批准晚于新 flush 时那一天的 ledger 属于一份没人读过的候选。

## 6. 报文与契约

`POST /api/admin/internal/memory/drafts` 的请求体加 `targets`，与运行侧 `MemoryDraftProposal` 逐字段同名（跨语言/跨模块的两侧判据必须一致）：

| 位置 | 改动 |
|---|---|
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/MemoryDraftAdaptor.kt:39` | `MemoryDraftProposal` 加 `targets: List<MemoryDraftTarget>`，默认空列表；新增 `data class MemoryDraftTarget(path, expectedVersion, baseText, mergedText)` |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/client/AdminApiClient.kt:357` | `body` 加 `"targets"`，逐目标 map 出四个键；空列表照 `sources` 的写法直接送 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftSubmitRequest.kt:22` | 加 `targets: List<MemoryDraftTarget>? = null` |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftSource.kt` | 同文件新增 `MemoryDraftTarget` DTO（与运行侧同名同序） |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftResponse.kt:67` | Detail 加 `targets`；List 加 `targetCount: Int`（清单要能看出这条候选要动几个对象，与现有 `sourceCount` 同一理由） |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/MemoryDraft.kt` | 加 `var targets: String?`；类注释里「一行=一个 conversation」改成「一行=一次决定，覆盖结论层加 K 个日期」 |
| `harnax-entity/src/main/resources/mapper/MemoryDraftMapper.xml` | `resultMap`/`insert`/`updateContent` 三处各加一列。`updateContent` 的注释现在写「the four columns a proposal carries」，加列后是五个，注释要说清它们仍是一次整体重写 |

校验（`MemoryDraftServiceImpl.submit`，`:57`）在现有 `sources` 校验之后加一段，全部走 `BizException`⇒运行侧 `Refused`（校验类拒绝重试也是同一句拒绝）：

- `targets` 为空/`null`⇒通过（D8）。
- 每个 `path` 必须能被 `MemoryObjectKeys.longTermSourceKey(...)`（新增，见 §6.1）解析出一个长期对象键，否则拒绝并点名路径。走这个函数而不是本类里的模式匹配，理由与 `sources` 完全相同（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryDraftServiceImpl.kt:104`）：入账和批准不许对「什么算一个长期日期对象」有两份答案。
- `expectedVersion < 0` 拒绝。
- `mergedText` 空白拒绝；单目标与总量各自有上限（沿用 `MAX_MERGED_BYTES = 200_000` 与 `MAX_SOURCES_BYTES = 2_000_000` 那两条的形状，另立常量名）。
- `targets.size > MAX_TARGETS` 拒绝。`MAX_TARGETS = 64`，与 `MAX_SOURCES` 同一个定位：一个「防止无限堆积」的界，不是设计上限（设计上限在运行侧的 7）。
- path 重复拒绝（T1）。

### 6.1 键与解析

`MemoryObjectKeys`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/MemoryObjectKeys.kt`）已经有 `dailyKey(...)`（`:159`），它就是长期日期对象的键，只是目前没有调用方。新增：

```kotlin
fun longTermSourceKey(keyPrefix, tenantId, userId, agentId, path, tenantScoped): String?
```

规则与 `sessionSourceKey`（`:199`）同形：先过 `isValidAgentId`，再要求 path 形如 `memory/<name>.md` 且 `<name>` 能被 `dateOf` 解成真实日期（`:332`，解析而非形状匹配：一个不存在的日子不是记忆文件），否则返回 `null`。`MEMORY.md` 在这里返回 `null`——长期结论层不通过 `targets` 写。

### 6.2 摘要

`MemoryDraftCodec.contentDigest`（`:48`）的字段列表在 `sources` 之后加 `targets.orEmpty()`。T4 靠这一行；漏了它，`targets` 就是队列里唯一一段没被批准动作覆盖的字节。

## 7. 存储写侧

`MemoryStoreGateway`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt`）新增两个方法，形状照现有结论层那一对：

- `readDailyLayer(tenantId, userId, agentId, date): DailyLayer(content, version)`，对象不存在⇒空文本 + 版本 `0`（与 `readCuratedLayer:223` 同口径）。
- `writeDailyIfVersion(tenantId, userId, agentId, date, expectedVersion, content): Boolean`，与 `writeCuratedIfVersion:245` 同两条闸。

两者都经过新的私有 `dailyKey(...)`，它先做 `isValidAgentId`（同 `curatedKey:412`），键由 `MemoryObjectKeys.dailyKey` 生成。

`writeEnvelope`（`:508`）现在把信封体的 `"key"` 硬编码成 `MemoryObjectKeys.MEMORY_MD_ITEM_KEY`。这必须改成入参：`MinioBaseStore.search` 用信封里的 `wrapper.key` 重建 `StoreItem` 的 itemKey（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/minio/MinioBaseStore.kt:181`），如果 admin 把 `/2026-10-09.md` 这个对象的信封里写成 `/MEMORY.md`，运行时列这个桶就会得到 N 个都叫 `/MEMORY.md` 的条目——页面上日期列照常显示（那里按对象键算，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:105`），坏掉的是运行侧的视图，而这正是最难查的那类不一致。

`deleteAgent`/`deleteUser` 不动：它们按前缀扫（`:590`），新增的日期对象天然在 `agents/<名>/` 前缀里。

## 8. 页面

`harnax-webui`：

- 审批详情 `harnax-webui/src/pages/memory/draftDetail.tsx`：结论层的 base/candidate 一块照旧，其下按目标各一节，标题是 `memory/2026-10-09.md`＋该目标的前提版本，正文给「当前」与「并入后」两份文本。仍只一枚「批准」按钮——摘要覆盖全部目标，一次点击就是这一整份决定（D2）。
- 批准成功文案追加「并入了 N 个日期账本」，`N` 取 `dailyTargetsApplied`；`STALE_BASE` 时把 `staleTarget` 带进提示，让人看得出是哪一个对象动了。
- 记忆页 `harnax-webui/src/pages/memory/index.tsx` 零改动：日期列 `dates` 与详情 `entries` 读的就是长期层的 `memory` 段，新对象一出现它们就跟着长。
- 类型 `harnax-webui/src/typings.d.ts` 加 `MemoryDraftTarget`、`targets`、`targetCount`、`dailyTargetsApplied`、`staleTarget`；文案 `src/locales/zh-CN/pages.ts` 与 `src/locales/en-US/pages.ts` 同步加 key。

## 9. 测试

命名按 RED/GREEN 分档记录，实跑输出留档。

| 层 | 用例 | 断言的是需求还是代理 |
|---|---|---|
| `MemoryPromoterTest` | 会话层 3 个日期⇒模型调用 4 次、`targets` 3 条且 path/version/baseText 逐个对上桩响应 | 需求（D1/D3） |
| | 10 个日期⇒只 7 条目标，且 `sources` 恰好是入选的 7 个 ledger + 结论草稿 | 需求（D6/T3） |
| | 某日期的 merge 抛错⇒`propose` 零调用、`Outcome.MODEL_FAILED` | 需求（D7） |
| | 某日期 merge 答回长度不到现存一半⇒不提名（缩水闸按目标生效） | 需求（§3.1.4） |
| | 长期日期对象 `store.get` 抛⇒`STORE_FAILED` 且 `propose` 零调用 | 需求 |
| | 结论层的 merge 输入不含未入选的日期 | 需求（D9） |
| `MemoryDraftServiceImplTest`（Digest 一节） | `targets` 变、其余全同⇒摘要变；同内容不同读入顺序⇒摘要与字节同 | 需求（T4） |
| `MemoryObjectKeysTest` | `longTermSourceKey` 收 `memory/2026-10-09.md`；拒 `MEMORY.md`/`memory/`/`memory/a/b.md`/`memory/x.md`/`memory/2026-02-30.md`/`../` 形 | 需求（§6.1） |
| `MemoryDraftServiceImplTest` | 入账：重复 path、负版本、空白文本、`MEMORY.md` 作 path⇒各自拒绝；`targets` 缺省⇒通过且落库 `NULL` | 需求（D8） |
| | 批准：第 2 个目标版本不符⇒`STALE_BASE` + `staleTarget` + 零次写 + 抢占未生效 | 需求（D4） |
| | 批准：某目标现存文本已逐字等于候选⇒不重写，其余照写，`dailyTargetsApplied` 计数含它 | 需求（§4.1） |
| | 批准：第 2 个日期条件写返回 false⇒抛 409、`clearSessionSources` 零调用 | 需求（§4.3） |
| `MemoryDailyMergeIT`（真 MinIO，`-Pintegration-test`） | A 会话批准⇒agent 桶出现 `memory/2026-10-09.md`，版本 1，信封 `key` 是 `/2026-10-09.md` | 需求（§7 的键污染防线） |
| | B 同日第二会话：批准⇒整条 `STALE_BASE`，A 的日期文件未被覆盖，B 的会话层未被清 | 需求（§5） |
| | B 以新的 `expectedVersion` 经入账接口再提一条候选（IT 直接写报文体，不调模型），批准⇒该日文件同时含 A 与 B 的材料；记忆页 `dates` 有 `2026-10-09`、详情 `entries` 有正文 | 需求（0 节目标形态，端到端） |
| | 批准后再 `readAgent`，日期条目文本=候选文本逐字相等 | 需求 |
| 排除项 | `pendingLayers` 与「待并入」tooltip 的口径错配（批准后残留未清文件仍计数，文案却写「等你审批」）本轮不修，单独记账 | 见 §12 |

## 10. 文件级改动清单

运行侧（harnax-harness-core）：
1. `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/MemoryPromoter.kt` — 多目标提名、日期 merge 提示词、总预算、`inScope`
2. `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/MemoryDraftAdaptor.kt` — `MemoryDraftProposal.targets` + `MemoryDraftTarget`

运行侧（harnax-agent-service）：
3. `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/client/AdminApiClient.kt` — 报文

admin：
4. `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryDraftServiceImpl.kt` — 入账校验 + 批准多目标
5. `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt` — `readDailyLayer`/`writeDailyIfVersion`/`dailyKey`，`writeEnvelope` 的 `key` 参数化
6. `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/MemoryDraftCodec.kt` — `targetsJson`/`targetsOf`/摘要字段
7. `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/MemoryObjectKeys.kt` — `longTermSourceKey`
8. `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftSubmitRequest.kt`
9. `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftSource.kt`（同文件加 `MemoryDraftTarget`）
10. `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftResponse.kt`（List + Detail）
11. `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDraftDecisionResponse.kt`

entity：
12. `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/MemoryDraft.kt`
13. `harnax-entity/src/main/resources/mapper/MemoryDraftMapper.xml`

schema：
14. `harnax-admin/src/main/resources/db/migration/V8__memory_draft_targets.sql`（`ALTER TABLE memory_draft ADD COLUMN targets mediumtext NULL` + 表注释改口径）
15. `harnax-entity/src/test/resources/schema-test.sql`（重放到 V8 的副本同步）

webui：
16. `harnax-webui/src/pages/memory/draftDetail.tsx`
17. `harnax-webui/src/typings.d.ts`
18. `harnax-webui/src/locales/zh-CN/pages.ts`、`harnax-webui/src/locales/en-US/pages.ts`

文档（中英成对）：
19. `prod_doc/memory-engineering.zh-CN.md` / `prod_doc/memory-engineering.en-US.md` — 见 §11

## 11. 文档要改的四处口径

1. 写者表（§11.1 那一类）：长期层日期段的写者由「无」改为「候选批准」。
2. `prod_doc/memory-engineering.zh-CN.md` 现在有一条说长期桶的流水段不长新文件、日期列一律为空（约第 292 行）。这条口径被本设计作废，两份成对删改；同段里「正文照常随每次批准被覆盖」的结论仍成立，但不能再拿它当「日期列为空不是丢记忆」的证据。
3. 晋升形状（§11.4 那一类）：一次提名⇒`1 + K` 个目标、K 上限 7、任一失败整条不提；批准⇒全量预检 + 日期先写结论层最后写。
4. 改动清单：admin 前向增量枚举加 `V8`，admin 库表数不变（只加列不加表），`schema-test.sql` 的重放版本从 V7 改 V8。

## 12. 成本、边界与不做

- 每窗口的模型调用从 1 次变最多 8 次，总预算 25 分钟；单次沿用 5 分钟超时。跑在会话外的 `boundedElastic` 上（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/MemoryPromotionMiddleware.kt:58`），不占答案路径。
- 超过 7 个日期的会话要多个窗口才排干（30 分钟窗口⇒10 个日期约 1 小时）。排干与否可观测：`pendingSessionLayers` 仍在。
- 一条候选的 `targets` 与 `sources` 一起进 `contentDigest`，所以任一目标被重提名改写都会让屏上那次批准拿 `DRAFT_CHANGED`。这是 D4 的代价，也是它的保障。
- 长期层日期文件仍然没有任何注入读者（D5）。它们此刻的价值是页面可见与「按天的 agent 记忆」这份数据结构本身；要给它们加读者是下一期的决定，要动的地方是 `LongTermMemoryContextMiddleware`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/LongTermMemoryContextMiddleware.kt:45`）与它的截断预算。
- 不做：agent 层日期的归档/保留窗口（上游的 `memory/archive/` 只作用于会话桶）；iOS 记忆域；`pendingLayers` 与「待并入」文案的口径修正（现状：批准成功后若某个会话层文件因字节变动没被清，列仍显示 1，而 tooltip 写「正等你审批」，这是两套事实，需要单独定口径）；把 `targets` 拆成可按目标检索的行。
