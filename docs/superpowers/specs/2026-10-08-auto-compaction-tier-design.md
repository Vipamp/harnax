# 自动压缩档位显式化 · 设计

日期：2026-10-08　状态：已实现。本文件是当天的规划稿，落地后的口径以 `prod_doc/harness-compaction.zh-CN.md` / `.en-US.md` 为准，两份不一致时读那一对。

## 0. 一句话

自动压缩路径今天吃的是 agentscope 2.0.4 的 `CompactionConfig` 默认值，harnax 一个字面量都没写；本次把那套档位逐条钉成 harnax 自己的具名常量并交给上游 builder，同时把 `offloadBeforeCompact` 显式关掉。除 offload 外，**行为一条都不变**。

## 1. 现状（自动路径为什么没有单一来源）

装配链 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` 没有任何 `compaction(...)` 调用，所以上游走的是它自己的默认件（`HarnessAgent.java:1227`、`:2601`，`Builder.compaction()` 在 `:1873`，其中 `disableCompaction = (config == null)`）。

由此有两处不一致：

1. **读数是推的，不是读的。** `HarnessAgentWrapper.kt:433-452` 的 `contextUsage()` 先 `CompactionConfig.builder().build()` 现建一份默认件（`:436`），再按 `modelWindow - reserved` 推出页面上那行「自动压缩阈值」（`triggerTokens(...)`，`:473-480`），KDoc `:427-428` 自己写明「no builder call here overrides the compaction tier」。上游改 `reserved`，线上档位与页面读数会一起静默变。
2. **同一件事两条路径口径相反。** 手动 `/compact` 在 `ContextCompactionService.kt:144-149` 显式建件：`triggerMessages(1)`、`flushBeforeCompact(false)`、`offloadBeforeCompact(false)`、`keepTokens` 可选；自动路径两个开关都开。

`offloadBeforeCompact=true` 落的是哪一份数据已经查清：`MemoryFlushManager.offloadMessages`（上游 `memory/MemoryFlushManager.java:207-216`）直接 `new SessionTranscriptWriter(...).appendMessages(...)`，写 `agents/<agentId>/sessions/<sessionId>` 下的会话正文（`memory/session/SessionTranscriptWriter.java:86`）。它与已被 `disableTranscript()` 关掉的每轮 transcript 是**同一个写入器**，只是入口换成压缩；而它在 harnax 里唯一的消费方 `session_search` 已被显式摘除（`HarnessAgentLauncher.kt:942`，理由记在 `HarnessAgentLauncherMemoryTest.kt:171`：它读的是单个副本的本地 transcript，不是属主桶）。这份副本因此没有读回入口，也不在 `clearSession` 的删除范围内。

## 2. 钉下来的档位

数值一律写 harnax 自己的字面量，取值等于上游 2.0.4 今天生效的默认值。

| 常量 | 值 | 理由 |
|---|---|---|
| `TRIGGER_MESSAGES` | 50 | 消息数那条线，保持上游现值 |
| `TRIGGER_TOKENS` | 0 | 0＝按模型窗口动态。窗口是每模型不同的（`model.contextWindowSize`，操作者在模型行填的 `context_window` 经 `ModelHelper.kt:64/82/104` 进到模型件里），钉绝对值会让小窗口模型永远够不着线 |
| `RESERVED_TOKENS` | 20 000 | 动态触发线让出的余量，也就是页面上那行阈值的减数 |
| `KEEP_MESSAGES` | 20 | 无窗口可推时的保留条数 |
| `KEEP_TOKENS` | -1 | -1＝动态尾档 |
| `KEEP_TOKENS_MIN` / `_MAX` / `_RATIO` | 2 000 / 8 000 / 0.25 | 动态尾档 `min(MAX, max(MIN, usable × RATIO))` 的三个参数 |
| `PRUNE_PROTECT_TOKENS` | 40 000 | 最近这么多 token 的工具结果不参与裁剪 |
| `PRUNE_MINIMUM_TOKENS` | 20 000 | 可裁总量越过这条才真裁 |
| `PRUNE_MAX_OUTPUT_CHARS` | 2 000 | 裁完只留头尾预览时的每份字符数 |
| `PRUNE_EXCLUDED_TOOLS` | `read_file, memory_search, memory_get, session_search` | 后三件在 harnax 里都是只读或已摘除，裁它们换不到余量 |

另三项显式写出但不钉值，各带一句理由：

- `summaryPrompt(CompactionConfig.DEFAULT_SUMMARY_PROMPT)`：**引用**上游常量而不是拷贝正文——摘要措辞的改进该跟，且这一跟是写在代码里的跟。
- `model(null)`：摘要用 agent 自己的模型。
- `truncateArgsConfig(null)`：工具入参截断没开（上游开它是 500/1000 字符常量、不可配，那正是当初否掉 transcript 通道的理由之一）。

两个开关：

- `flushBeforeCompact` **true**：压缩前把即将被裁掉的前缀抽进当日记忆账，memory 域吃这一路。
- `offloadBeforeCompact` **false**：见第 1 节末尾，那份副本没有读者、不受删除，写它只是把已被否掉的通道从压缩这一头留了回来。

## 3. 单一来源的形状

新文件 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/compaction/AutoCompactionTier.kt`：

```kotlin
internal object AutoCompactionTier {
    const val TRIGGER_MESSAGES = 50
    const val TRIGGER_TOKENS = 0
    const val RESERVED_TOKENS = 20_000
    const val KEEP_MESSAGES = 20
    const val KEEP_TOKENS = -1
    const val KEEP_TOKENS_MIN = 2_000
    const val KEEP_TOKENS_MAX = 8_000
    const val KEEP_TOKENS_RATIO = 0.25
    const val PRUNE_PROTECT_TOKENS = 40_000
    const val PRUNE_MINIMUM_TOKENS = 20_000
    const val PRUNE_MAX_OUTPUT_CHARS = 2_000
    val PRUNE_EXCLUDED_TOOLS = setOf("read_file", "memory_search", "memory_get", "session_search")

    fun auto(): CompactionConfig = // 上面这套数 + flush true + offload false
    fun command(keepTokens: Int?): CompactionConfig = // 同一套数 + triggerMessages(1) + flush/offload 都关
}
```

四处改动：一处是必需的透传，三处是这套数的消费点。`HarnessAgentBuilder`（harnax 自己的装配 builder）今天没有 `compaction(...)` 透传——`:225-243` 那一段只有 disable 系列，`:247` 直接 `build()`——所以档位要能递到上游，先得把这层接上。

| 落点 | 改法 |
|---|---|
| `HarnessAgentBuilder.kt`，`disableTranscript()`（`:243`）之前 | 加透传 `fun compaction(config: CompactionConfig): HarnessAgentBuilder = apply { builder.compaction(config) }`，指向 `HarnessAgent.java:1873` |
| `HarnessAgentLauncher.kt`，`disableTranscript()`（`:870`）那一段之后 | 加 `agentBuilder.compaction(AutoCompactionTier.auto())`；注释把 `offloadBeforeCompact=false` 的理由挂到同段已有的 `disableTranscript()` 与 `removeTool("session_search")`（`:942`）判据上 |
| `HarnessAgentWrapper.kt:433-452` `contextUsage()` | 删掉 `:436` 那行自建默认件，改读 `AutoCompactionTier.RESERVED_TOKENS` 与 `TRIGGER_MESSAGES`；KDoc `:427-428` 的「上游默认」改口为「harnax 钉的档位」 |
| `ContextCompactionService.kt:144-149` `commandConfig()` | 一行委托 `AutoCompactionTier.command(keepTokens)`，手动与自动的数从此同源 |

读数一侧不再需要一份 `CompactionConfig` 实例，只取两个常量；触发线仍按 `window - reserved` 推，因为它是每模型的量，但减数现在是钉住的那个数。

## 4. 测试方案

1. **`AutoCompactionTierTest`（新增，harness-core）**
   - `auto()` 逐条值断言（上表每个常量、两个开关、以及 `truncateArgsConfig == null`、`model == null`）。
   - `command(null)` 与 `auto()` 的差异**恰好只有** `triggerMessages`、`flushBeforeCompact`、`offloadBeforeCompact` 三项；`command(1500)` 在此基础上只再动 `keepTokens`。
   - **上游漂移哨兵**：把 `auto()` 与 `CompactionConfig.builder().build()` 逐字段比，断言差异只有 `offloadBeforeCompact` 一项。上游哪天改任何默认值，这条会转红——那是要人裁决的信号，不是要修的红。这条用例的注释必须写明它是故意会红的。
2. **装配级接线判据**（新增 `HarnessAgentBuilderCompactionTest`，形状照 `HarnessAgentBuilderTranscriptTest`）：反射读出中间件上那份实际生效的 `CompactionConfig`——`CompactionMiddleware` 把 `config` 存成私有 final 字段（上游 `middleware/CompactionMiddleware.java:65`），读私有字段的理由与写法沿用 `HarnessAgentLauncherMemoryTest.kt:594-598` 那条 `privateField`（「上游把这两个字段留成私有并自己挑，所以要诚实地回答『这个 agent 拿到的是哪道闸』，就得读 hook 真正会 consult 的那个字段，而不是读它自己打的那行日志」）。两条用例：
   - **前提**：不调 `compaction(...)` 时，装配出的那份 config 就是上游默认件（`offloadBeforeCompact` 为 true）。这条挡住「passthrough 根本没接到 builder 也照样绿」那一形。
   - **接线**：调 `compaction(AutoCompactionTier.auto())` 之后，同一反射读出的 config 满足 `offloadBeforeCompact == false` 且 `reserved == 20_000`。
   另带一条 `getCompactionHook()`（上游 `HarnessAgent.java:264`）非 null，挡「传了个 null 把自动压缩整条关掉」——上游 `:1874` 是 `disableCompaction = (config == null)`。
3. **读数同源**（`HarnessAgentContextArchiveAndUsageTest.kt`，现有那批用例）：`triggerTokens` 走钉住的 `RESERVED_TOKENS`、`triggerMessages` 走 `TRIGGER_MESSAGES`、三档 `ContextWindowSource` 形状不变。数值没换，这批应当原样绿；若有红，说明改的是行为而不是写法。
4. **一轮真栈复验**（harnax-deploy 全栈、真实 qwen 端点、模型行 `context_window` 按需填）：
   - 填小窗口跑几轮让自动压缩真触发；
   - 判据 a：`GET /api/router/agent/context/{sessionId}` 的 `triggerTokens` 等于按钉值推出的那个数；
   - 判据 b：压缩发生后，工作区与对象存储侧**不再新增**会话正文文件，取到与本轮改动前的对照形状；
   - 判据 c：主断言顺带再取一次——页面仍是全量原文气泡、无摘要气泡。

## 5. 边界与失败

- 传 null 给 `compaction(...)` 会整条关自动压缩（上游 `:1874`）。测试 2 是这一形的闸。
- 上游 offload 失败本来就静默吞（`ConversationCompactor.java:153` 起，失败继续走），所以「关掉它」不会引入新的失败面，只是少一份没读者的写盘。
- `flushBeforeCompact` 保持 true：memory 域若哪天改成不吃压缩前那一路，是另一个决定，不在本次。
- 本次不动命令路径的三态读数、不动 `/context` 契约、不动前端与 iOS：两端读的仍是服务端给的 `triggerTokens`/`triggerMessages`，值没变。

## 6. 文档回写

不新开第三份 harness 方案件。as-built 结果折回既有那两份成对文档 `prod_doc/harness-compaction.zh-CN.md` / `.en-US.md`：档位那段落在 §5 下的 `### 5.1 自动压缩档位`（与该文既有的 10.1/10.2 同形，§5、§6、§7 都指得到它），§2 事实一改成「上游那份初值 + harnax 在装配层钉成自己的档位」，§6 把「那个阈值是推出来的」改口成「钉出来的」并给出钉住的减数，§7 改动清单加一行，§10 加本轮真栈记录，§11 把「自动压缩档位的显式化」这条划出。

## 7. 验收清单

- 设计稿落盘并合入 kotlin-dev（本文件）。
- 代码：`AutoCompactionTier.kt` 新增，三个落点各改一处；`mvn` 定向门禁与 harness-core 全量各跑一次，日志留档。
- 测试：第 4 节四类各有可核输出（用例名与计数），漂移哨兵那条单独确认「今天绿」。
- 真栈：判据 a/b/c 三条各取到一次，b 要有改动前后的对照。
- 文档：两份成对文档同步，§11 那条划出；§10 计数重算。
- 提交分域：后端一笔、文档一笔，不碰 `harnax-app`，不 push。
