# 自动压缩档位显式化 · 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把自动压缩路径的 `CompactionConfig` 从 agentscope 2.0.4 的默认值改成 harnax 自己钉住的具名常量，并把 `offloadBeforeCompact` 显式关掉。

**Architecture:** 新增单一来源对象 `AutoCompactionTier`（常量 + `auto()` + `command(keepTokens)`），装配链经一层新透传 `HarnessAgentBuilder.compaction()` 把 `auto()` 交给上游 builder；占用读数与 `/compact` 两条消费路径改读同一对象，手动与自动的档位从此同源。

**Tech Stack:** Kotlin 2.2.21 / Maven 3.9.12 / JDK 21 / JUnit 5 + Mockito / agentscope-harness 2.0.4。

## Global Constraints

- 设计稿：`docs/superpowers/specs/2026-10-08-auto-compaction-tier-design.md`（已获批准）。除 `offloadBeforeCompact` 由 true 改 false 外**行为一条都不变**；任何转红都按「先证前提，再判是不是改了行为」处理。
- 数值一律取上游 2.0.4 今天生效的默认值，逐字来自 `agentscope-harness-2.0.4-sources.jar` 的 `io/agentscope/harness/agent/memory/compaction/CompactionConfig.java`（Builder 默认段 `:271-288`、`PruneConfig` 默认段 `:532-603`）。
- 上游 2.0.4 只能这样读：`unzip -p ~/.m2/repository/io/agentscope/agentscope-harness/2.0.4/agentscope-harness-2.0.4-sources.jar <path>`；**不得**把解出来的源码 `git add`。
- 全仓代码注释/KDoc/日志串用英文。
- 门禁跑法（本项目既有配方）：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home /Users/heqingsong/software/apache-maven-3.9.12/bin/mvn`；改过 Kotlin 先 `mvn -q spotless:apply`（**故意不带 `-am`**）；agent 模块必须 `-am`；判定只认带 `-- in com.agnetix...<ClassName>` 的行；计数按模块聚合行 `grep -E '^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$'` 后 awk 求和，**不要用 `grep FAIL`**（会命中 `AUTH_FAILED`）；定向用例用逗号列表，管道收尾用 `tail` 不用 `head`（`head` 会 SIGPIPE 打断测试进程造假红）。
- 门禁命令统一形态：**在仓库根 `/Users/heqingsong/code/my_project/harnax` 跑**，`-pl` 用带路径的模块名 `harnax-agent/harnax-harness-core` / `harnax-agent/harnax-agent-service`，一律带 `-am`。不能在 `harnax-agent/` 里跑：`harnax-harness-core` 的主源码 import 了 `com.agnetix.harnax.entity.ToolInvocationLog`（类在仓库根的 `harnax-entity`），只从 `harnax-agent` 聚合走会从 `~/.m2` 那份旧 jar 解析、报一串 `Unresolved reference 'ToolInvocationLog'`。
- 定向跑（`-Dtest=X`）必须再带 `-Dsurefire.failIfNoSpecifiedTests=false`，否则 reactor 里上游模块（如 `harnax-tools-sdk`）没有同名用例就先判 FAILURE。
- 本计划各步写的 `mvn ...` 都默认上面这两条前缀与开关。
- 提交：分支 `kotlin-dev`，**不 push**；不含 `harnax-app` 任何文件；不 `git add` `harnax-deploy/data`、不 `git add` `tmp/`。本轮分两笔：后端代码一笔、文档一笔。
- 落地校正值：Task 1–4 步末各自写的 `git commit` 没有单独执行，四个任务的改动并成上面那一笔后端提交。
- 真栈判据 b 若在本轮允许的通道里取不到观察（工作区文件读端点看不到该路径），**如实记「未验」**，不许用单测绿代替界面/产物结论。

---

### Task 1: 单一来源 `AutoCompactionTier`

**Files:**
- Create: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/compaction/AutoCompactionTier.kt`
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/compaction/AutoCompactionTierTest.kt`

**Interfaces:**
- Consumes: 上游 `io.agentscope.harness.agent.memory.compaction.CompactionConfig`（builder 方法名逐字为 `triggerMessages/triggerTokens/reserved/keepMessages/keepTokens/keepTokensMin/keepTokensMax/keepTokensRatio/summaryPrompt/flushBeforeCompact/offloadBeforeCompact/truncateArgs/prune/model`；布尔读法是 `isFlushBeforeCompact()`/`isOffloadBeforeCompact()`）。
- Produces:
  - `internal object AutoCompactionTier`，常量 `TRIGGER_MESSAGES: Int`、`TRIGGER_TOKENS: Int`、`RESERVED_TOKENS: Int`、`KEEP_MESSAGES: Int`、`KEEP_TOKENS: Int`、`KEEP_TOKENS_MIN: Int`、`KEEP_TOKENS_MAX: Int`、`KEEP_TOKENS_RATIO: Double`、`PRUNE_PROTECT_TOKENS: Int`、`PRUNE_MINIMUM_TOKENS: Int`、`PRUNE_MAX_OUTPUT_CHARS: Int`、`PRUNE_EXCLUDED_TOOLS: Set<String>`
  - `fun auto(): CompactionConfig`
  - `fun command(keepTokens: Int?): CompactionConfig`
  - Task 3 的装配线、Task 4 的两处读数与命令路径都消费这套名字。

- [ ] **Step 1: 写失败的测试**

Create `AutoCompactionTierTest.kt`：

```kotlin
package com.agnetix.harnax.harness.compaction

import io.agentscope.harness.agent.memory.compaction.CompactionConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The compaction tier harnax pins, and the one difference it deliberately takes from upstream.
 *
 * Every number here equals what agentscope 2.0.4 uses today; the point of the object is not the values,
 * it is that they stop being inherited. `auto()` and `command()` are checked field by field against that
 * single source, so neither path can gain a knob without this test saying so.
 */
class AutoCompactionTierTest {

    @Nested
    @DisplayName("the automatic tier")
    inner class AutoTier {

        @Test
        @DisplayName("pins every knob to the number upstream uses today")
        fun pinsEveryKnob() {
            val config = AutoCompactionTier.auto()

            assertEquals(50, config.triggerMessages)
            assertEquals(0, config.triggerTokens)
            assertEquals(20_000, config.reserved)
            assertEquals(20, config.keepMessages)
            assertEquals(-1, config.keepTokens)
            assertEquals(2_000, config.keepTokensMin)
            assertEquals(8_000, config.keepTokensMax)
            assertEquals(0.25, config.keepTokensRatio)
        }

        @Test
        @DisplayName("pins the prune tier that trims old tool results")
        fun pinsPrune() {
            val prune = requireNotNull(AutoCompactionTier.auto().pruneConfig)

            assertEquals(40_000, prune.protectTokens)
            assertEquals(20_000, prune.minimumTokens)
            assertEquals(2_000, prune.maxOutputChars)
            assertEquals(
                setOf("read_file", "memory_search", "memory_get", "session_search"),
                prune.excludedTools,
            )
        }

        @Test
        @DisplayName("flushes before compacting and does not write a session copy")
        fun switches() {
            val config = AutoCompactionTier.auto()

            assertTrue(
                config.isFlushBeforeCompact,
                "memory reads the prefix that is about to be trimmed out of the daily ledger",
            )
            assertFalse(
                config.isOffloadBeforeCompact,
                "the offload copy is the transcript channel harnax disabled: no reader, no delete",
            )
        }

        @Test
        @DisplayName("leaves the summary prompt, model and arg truncation to upstream")
        fun leavesThree() {
            val config = AutoCompactionTier.auto()

            // Referenced, not copied: improving upstream's prompt should reach harnax through this line.
            assertEquals(CompactionConfig.DEFAULT_SUMMARY_PROMPT, config.summaryPrompt)
            assertNull(config.model, "the summary runs on the agent's own model")
            assertNull(
                config.truncateArgsConfig,
                "arg truncation is fixed at 500/1000 chars with no knob — the reason the transcript channel was rejected",
            )
        }
    }

    @Nested
    @DisplayName("the command tier")
    inner class CommandTier {

        @Test
        @DisplayName("differs from the automatic tier in exactly three fields")
        fun differsInThreeFields() {
            val auto = AutoCompactionTier.auto()
            val command = AutoCompactionTier.command(null)

            assertEquals(1, command.triggerMessages, "a user who asked for this must not be told the chat is too short")
            assertFalse(command.isFlushBeforeCompact)
            assertFalse(command.isOffloadBeforeCompact)
            // Everything else is the same tier, which is the whole point of one source.
            assertEquals(auto.triggerTokens, command.triggerTokens)
            assertEquals(auto.reserved, command.reserved)
            assertEquals(auto.keepMessages, command.keepMessages)
            assertEquals(auto.keepTokens, command.keepTokens)
            assertEquals(auto.keepTokensMin, command.keepTokensMin)
            assertEquals(auto.keepTokensMax, command.keepTokensMax)
            assertEquals(auto.keepTokensRatio, command.keepTokensRatio)
            assertEquals(auto.summaryPrompt, command.summaryPrompt)
            assertEquals(auto.pruneConfig.protectTokens, command.pruneConfig.protectTokens)
            assertEquals(auto.pruneConfig.minimumTokens, command.pruneConfig.minimumTokens)
            assertEquals(auto.pruneConfig.maxOutputChars, command.pruneConfig.maxOutputChars)
            assertEquals(auto.pruneConfig.excludedTools, command.pruneConfig.excludedTools)
            assertEquals(auto.truncateArgsConfig, command.truncateArgsConfig)
            assertEquals(auto.model, command.model)
        }

        @Test
        @DisplayName("keepTokens moves only the tail, and null leaves the dynamic tier in charge")
        fun keepTokensMovesOnlyTheTail() {
            assertEquals(1_500, AutoCompactionTier.command(1_500).keepTokens)
            assertEquals(-1, AutoCompactionTier.command(null).keepTokens)
            val withNumber = AutoCompactionTier.command(1_500)
            val without = AutoCompactionTier.command(null)
            assertEquals(without.triggerMessages, withNumber.triggerMessages)
            assertEquals(without.reserved, withNumber.reserved)
            assertEquals(without.keepMessages, withNumber.keepMessages)
        }
    }

    @Test
    @DisplayName("upstream drift: auto() differs from upstream's own default in exactly one field")
    fun driftSentinel() {
        // INTENTIONALLY the canary. Every field equals upstream's default today, so if agentscope ever
        // changes one of them this goes red on purpose — that is a decision to make, not a bug to fix.
        // Re-read the new default, decide whether harnax keeps its pinned number, and say so in the commit.
        val pinned = AutoCompactionTier.auto()
        val upstream = CompactionConfig.builder().build()

        assertEquals(upstream.triggerMessages, pinned.triggerMessages)
        assertEquals(upstream.triggerTokens, pinned.triggerTokens)
        assertEquals(upstream.reserved, pinned.reserved)
        assertEquals(upstream.keepMessages, pinned.keepMessages)
        assertEquals(upstream.keepTokens, pinned.keepTokens)
        assertEquals(upstream.keepTokensMin, pinned.keepTokensMin)
        assertEquals(upstream.keepTokensMax, pinned.keepTokensMax)
        assertEquals(upstream.keepTokensRatio, pinned.keepTokensRatio)
        assertEquals(upstream.summaryPrompt, pinned.summaryPrompt)
        assertTrue(upstream.isFlushBeforeCompact && pinned.isFlushBeforeCompact)
        assertEquals(upstream.truncateArgsConfig, pinned.truncateArgsConfig)
        assertEquals(upstream.model, pinned.model)
        assertEquals(upstream.pruneConfig.protectTokens, pinned.pruneConfig.protectTokens)
        assertEquals(upstream.pruneConfig.minimumTokens, pinned.pruneConfig.minimumTokens)
        assertEquals(upstream.pruneConfig.maxOutputChars, pinned.pruneConfig.maxOutputChars)
        assertEquals(upstream.pruneConfig.excludedTools, pinned.pruneConfig.excludedTools)
        // The one intended difference.
        assertTrue(upstream.isOffloadBeforeCompact, "upstream still defaults the session copy on")
        assertFalse(pinned.isOffloadBeforeCompact)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /Users/heqingsong/code/my_project/harnax/harnax-agent && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home /Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -q -pl harnax-harness-core -am test -Dtest=AutoCompactionTierTest -DfailIfNoTests=false 2>&1 | tail -30`
Expected: 编译失败，报 `Unresolved reference 'AutoCompactionTier'`。（若 `mvn` 顶层不在 `harnax-agent`，先 `ls harnax-agent/pom.xml` 确认聚合 pom 位置再定 `-pl` 路径。）

- [ ] **Step 3: 写实现**

Create `AutoCompactionTier.kt`：

```kotlin
package com.agnetix.harnax.harness.compaction

import io.agentscope.harness.agent.memory.compaction.CompactionConfig

/**
 * The compaction tier this runtime builds with, as harnax's own literals.
 *
 * Until now the automatic path inherited every default from agentscope 2.0.4 and harnax wrote none of
 * them, which left two things in the air: an upstream change to `reserved` would move both the live
 * trigger and the number `/context` reports with no call site to notice it, and the read side had to
 * reconstruct a default config just to subtract its margin. The values below are upstream's current
 * numbers, spelled out; `AutoCompactionTierTest` keeps them honest with a drift sentinel that goes red
 * the day upstream moves one.
 *
 * `offloadBeforeCompact` is the one field pinned to something other than the default. Upstream sends
 * the trimmed prefix through the same `SessionTranscriptWriter` that `disableTranscript()` turns off at
 * assembly — it writes `agents/<agentId>/sessions/<sessionId>` as a second copy of the history with no
 * reader here (`session_search` is removed from the toolkit), no read-back path and no delete in
 * `clearSession`. Harnax reads user-visible history from the `session_message` archive instead.
 */
internal object AutoCompactionTier {
    const val TRIGGER_MESSAGES = 50

    /** 0 means "derive from the model's window", which is per-model; pinning an absolute would put small windows out of reach. */
    const val TRIGGER_TOKENS = 0
    const val RESERVED_TOKENS = 20_000
    const val KEEP_MESSAGES = 20

    /** -1 means "the dynamic tail" below. */
    const val KEEP_TOKENS = -1
    const val KEEP_TOKENS_MIN = 2_000
    const val KEEP_TOKENS_MAX = 8_000
    const val KEEP_TOKENS_RATIO = 0.25
    const val PRUNE_PROTECT_TOKENS = 40_000
    const val PRUNE_MINIMUM_TOKENS = 20_000
    const val PRUNE_MAX_OUTPUT_CHARS = 2_000

    /** The last three names are read-only or removed tools here; pruning their results buys no margin. */
    val PRUNE_EXCLUDED_TOOLS = setOf("read_file", "memory_search", "memory_get", "session_search")

    /** The tier the automatic path runs on. */
    fun auto(): CompactionConfig = base().build()

    /**
     * The tier a `/compact` command runs on: the same numbers, no threshold that could answer a user who
     * asked for this, and neither file-writing step. `keepTokens` alone moves where the tail starts.
     */
    fun command(keepTokens: Int?): CompactionConfig = base()
        .triggerMessages(1)
        .flushBeforeCompact(false)
        .offloadBeforeCompact(false)
        .apply { keepTokens?.let { keepTokens(it) } }
        .build()

    private fun base(): CompactionConfig.Builder = CompactionConfig.builder()
        .triggerMessages(TRIGGER_MESSAGES)
        .triggerTokens(TRIGGER_TOKENS)
        .reserved(RESERVED_TOKENS)
        .keepMessages(KEEP_MESSAGES)
        .keepTokens(KEEP_TOKENS)
        .keepTokensMin(KEEP_TOKENS_MIN)
        .keepTokensMax(KEEP_TOKENS_MAX)
        .keepTokensRatio(KEEP_TOKENS_RATIO)
        // Referenced rather than copied, so a better summary prompt reaches this runtime through upstream.
        .summaryPrompt(CompactionConfig.DEFAULT_SUMMARY_PROMPT)
        .flushBeforeCompact(true)
        .offloadBeforeCompact(false)
        // Left unset on purpose: null keeps the agent's own model and the arg-truncation tier off, which
        // is the fixed-500/1000-char behaviour the transcript channel was rejected for.
        .prune(
            CompactionConfig.PruneConfig.builder()
                .protectTokens(PRUNE_PROTECT_TOKENS)
                .minimumTokens(PRUNE_MINIMUM_TOKENS)
                .maxOutputChars(PRUNE_MAX_OUTPUT_CHARS)
                .excludedTools(PRUNE_EXCLUDED_TOOLS)
                .build(),
        )
}
```

> 上游形状已核实，照写即可：`CompactionConfig.PruneConfig.builder()` 存在（`CompactionConfig.java:570`，返回内部类 `PruneBuilder`），链式方法为 `protectTokens(int)/minimumTokens(int)/maxOutputChars(int)/excludedTools(Set<String>)`（`:582-597`）；`CompactionConfig` 的字段全走 Java getter（`getTriggerMessages()` 等，`:151-240`），Kotlin 侧按属性语法读；两个布尔是 `isFlushBeforeCompact()`/`isOffloadBeforeCompact()`（`:212/:217`）。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q spotless:apply`（不带 `-am`），再跑 Step 2 那条命令。
Expected: 输出里有 `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in com.agnetix.harnax.harness.compaction.AutoCompactionTierTest`（自动档 4 条 + 命令档 2 条 + 漂移哨兵 1 条）。

- [ ] **Step 5: 提交**

```bash
cd /Users/heqingsong/code/my_project/harnax
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/compaction/AutoCompactionTier.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/compaction/AutoCompactionTierTest.kt
git commit -m "feat(compaction): 自动压缩档位落成 harnax 自己的具名常量——offload 显式关，漂移哨兵盯上游改默认值"
```

---

### Task 2: `HarnessAgentBuilder.compaction()` 透传

**Files:**
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt`（在 `disableWorkspaceContext()` 那一段之前，即 `// ===== Disable built-in features =====`（`:223`）上方新增一节）
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilderCompactionTest.kt`（新增）

**Interfaces:**
- Consumes: `AutoCompactionTier.auto()`（Task 1）；上游 `HarnessAgent.Builder.compaction(CompactionConfig)`（`HarnessAgent.java:1873`，其中 `:1874` 是 `disableCompaction = (config == null)`）；`HarnessAgent.getCompactionHook()`（`HarnessAgent.java:264`）。
- Produces: `fun compaction(config: CompactionConfig): HarnessAgentBuilder` — Task 3 的装配线调用它。

- [ ] **Step 1: 写失败的测试**

Create `HarnessAgentBuilderCompactionTest.kt`：

```kotlin
package com.agnetix.harnax.harness

import com.agnetix.harnax.harness.compaction.AutoCompactionTier
import io.agentscope.core.model.ChatModelBase
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.memory.compaction.CompactionConfig
import io.agentscope.harness.agent.middleware.CompactionMiddleware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Path

/**
 * Whether the built agent runs on harnax's pinned tier or on the defaults agentscope 2.0.4 picks.
 *
 * The config a middleware consults is a private final field (upstream
 * `middleware/CompactionMiddleware.java:66`), and the middleware list is where the assembled agent
 * actually carries it — so both the premise and the wiring are read off the composed agent, not off the
 * builder on the way there. Same reason the memory gate test reads private fields: the honest answer to
 * "which tier did this agent get" is the field the hook will consult, not the line logged about it.
 */
class HarnessAgentBuilderCompactionTest {

    private fun buildAgent(workspace: Path, tier: CompactionConfig?) = HarnessAgentBuilder()
        .name("tester")
        .description("tester")
        .maxIters(1)
        .systemPrompt("prompt")
        .model(mock(ChatModelBase::class.java))
        .workspace(workspace)
        .apply { tier?.let { compaction(it) } }
        .build()

    /** The config the installed hook will read, whichever middleware position upstream put it in. */
    private fun installedConfig(agent: HarnessAgent): CompactionConfig {
        val hook = agent.delegate.middlewares.filterIsInstance<CompactionMiddleware>().single()
        return privateField(hook, "config") as CompactionConfig
    }

    private fun privateField(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    @Test
    fun `upstream installs its own defaults when nothing pins a tier`(@TempDir workspace: Path) {
        // The premise this file exists to keep honest: a compaction tier that is never passed still leaves
        // the middleware in place with upstream's defaults, so a passthrough that does not reach the
        // builder would look green here. Offloading is on in that default set.
        val config = installedConfig(buildAgent(workspace, tier = null))

        assertTrue(config.isOffloadBeforeCompact, "agentscope 2.0.4 is expected to default the session copy on")
        assertEquals(50, config.triggerMessages)
    }

    @Test
    fun `compaction pins the tier the assembled agent runs on`(@TempDir workspace: Path) {
        val config = installedConfig(buildAgent(workspace, tier = AutoCompactionTier.auto()))

        assertFalse(config.isOffloadBeforeCompact, "harnax pins the automatic tier without the session copy")
        assertEquals(20_000, config.reserved)
        assertEquals(50, config.triggerMessages)
    }

    @Test
    fun `pinning a tier does not turn automatic compaction off`(@TempDir workspace: Path) {
        // Upstream reads a null config as "disable the middleware entirely" (HarnessAgent.java:1874), so
        // passing a tier must not be mistaken for passing none.
        assertNotNull(buildAgent(workspace, tier = AutoCompactionTier.auto()).getCompactionHook())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl harnax-harness-core -am test -Dtest=HarnessAgentBuilderCompactionTest -DfailIfNoTests=false 2>&1 | tail -30`
Expected: 编译失败 `Unresolved reference 'compaction'`（`HarnessAgentBuilder` 还没有这个透传）。

- [ ] **Step 3: 写实现**

在 `HarnessAgentBuilder.kt` 的 `// ===== Disable built-in features =====` 注释行（`:223`）**上方**插入：

```kotlin
    /**
     * Overrides the compaction tier the automatic path runs on. Passing null would turn automatic
     * compaction off entirely — upstream reads it as `disableCompaction` (`HarnessAgent.java:1874`) —
     * which is why the launcher passes [AutoCompactionTier] and never nothing.
     */
    fun compaction(config: CompactionConfig): HarnessAgentBuilder = apply { builder.compaction(config) }
```

并在 import 区加 `import io.agentscope.harness.agent.memory.compaction.CompactionConfig`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q spotless:apply`（不带 `-am`），再跑 Step 2 那条命令。
Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.agnetix.harnax.harness.HarnessAgentBuilderCompactionTest`。

- [ ] **Step 5: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilderCompactionTest.kt
git commit -m "feat(compaction): HarnessAgentBuilder 补 compaction 透传——装配层有路可把档位递进上游"
```

---

### Task 3: 装配链钉上档位

**Files:**
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`（`agentBuilder.disableTranscript()` 之后，即 `:870` 那行下面）
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncherCompactionTest.kt`（新增）

**Interfaces:**
- Consumes: `HarnessAgentBuilder.compaction(...)`（Task 2）、`AutoCompactionTier.auto()`（Task 1）、`HarnessAgentWrapper.harnessAgent.delegate.middlewares`。
- Produces: 装配出的每个 agent 的 `CompactionMiddleware` 都带 `offloadBeforeCompact == false && reserved == 20_000`；Task 4 的读数与命令路径不受影响。

- [ ] **Step 1: 写失败的测试**

Create `HarnessAgentLauncherCompactionTest.kt`。构造 launcher 的形状照 `HarnessAgentLauncherMemoryTest.kt:68-136`（`launcher(...)` 用 `ChatModelConfigAdaptor { OpenAIChatModelConfig(...) }` + 各 `mock(...)` adaptor + `HarnessConfig(sandbox = SandboxConfig(enabled = false))` + `minio()` 指向 `http://127.0.0.1:1` 的假凭据；`build(...)` 调 `createSingleAgent(agentSpec = spec(...), sessionId = "sess-1", chatSpec = ChatSpec.builder().build(), userIdentifier = UserIdentifier(userId = 1L))`）：

```kotlin
package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.harness.compaction.AutoCompactionTier
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.Memory
import com.agnetix.harnax.harness.config.MinioConfig
import com.agnetix.harnax.harness.config.SandboxConfig
import com.agnetix.harnax.tools.sdk.UserIdentifier
import io.agentscope.core.state.AgentStateStore
import io.agentscope.harness.agent.memory.compaction.CompactionConfig
import io.agentscope.harness.agent.middleware.CompactionMiddleware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Path

/**
 * The tier a real assembled agent gets, read off the middleware it will actually run.
 *
 * The builder passthrough has its own test; this one covers the other half — that the launcher calls it.
 * A passthrough nobody invokes leaves every session on upstream's defaults, including the session copy
 * harnax has no reader for.
 */
class HarnessAgentLauncherCompactionTest {

    // This module has no shared launcher fixture, so each launcher test carries its own construction
    // helpers; these are the same three HarnessAgentLauncherMemoryTest.kt:68-136 uses.

    private fun launcher(workspaceRoot: Path): HarnessAgentLauncher = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = mock(SkillAdaptor::class.java),
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = mock(ProcessLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = workspaceRoot,
        harnessConfig = HarnessConfig(
            sandbox = SandboxConfig(enabled = false),
            memory = Memory(enabled = false),
        ),
        minioConfig = MinioConfig(
            endpoint = "http://127.0.0.1:1",
            accessKey = "minioadmin",
            secretKey = "minioadmin",
        ),
        snapshotSpec = null,
    )

    private fun spec() = AgentSpec.builder()
        .id(1L)
        .tenantId(4L)
        .name("Research")
        .description("a research agent")
        .systemPrompt("answer")
        .chatModelId(100L)
        .build()

    private fun build(workspaceRoot: Path): HarnessAgentWrapper = launcher(workspaceRoot).createSingleAgent(
        agentSpec = spec(),
        sessionId = "sess-1",
        chatSpec = ChatSpec.builder().build(),
        userIdentifier = UserIdentifier(userId = 1L),
    )

    /** Upstream keeps the field private and picks the tier itself; the honest read is the one the hook consults. */
    private fun privateField(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun installedConfig(wrapper: HarnessAgentWrapper): CompactionConfig {
        val hook = wrapper.harnessAgent.delegate.middlewares.filterIsInstance<CompactionMiddleware>().single()
        return privateField(hook, "config") as CompactionConfig
    }

    @Test
    fun `the launcher pins the automatic tier onto every assembled agent`(@TempDir workspace: Path) {
        val config = installedConfig(build(workspace))

        assertFalse(
            config.isOffloadBeforeCompact,
            "the offload copy is the transcript channel disableTranscript() already turns off, from the other side",
        )
        assertEquals(AutoCompactionTier.RESERVED_TOKENS, config.reserved)
        assertEquals(AutoCompactionTier.TRIGGER_MESSAGES, config.triggerMessages)
    }
}
```

> `HarnessConfig(sandbox = SandboxConfig(enabled = false))` 只给这一组参数就够，其余走它自己的默认值；若 `createSingleAgent` 因为 memory/minio 分支报缺失，照 `HarnessAgentLauncherMemoryTest.kt:142` 那行 `build(workspace, Memory(enabled = false))` 的取值把 `memory` 显式传成关闭态，别为了跑绿去开沙箱。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl harnax-harness-core -am test -Dtest=HarnessAgentLauncherCompactionTest -DfailIfNoTests=false 2>&1 | tail -30`
Expected: FAIL，`expected: <false> but was: <true>`（launcher 还没调用 `compaction(...)`，装出来的仍是上游默认件）。这一跳就是「测试的是需求而不是代理指标」：它必须先真的红过一次。

- [ ] **Step 3: 写实现**

在 `HarnessAgentLauncher.kt` 的 `agentBuilder.disableTranscript()`（`:870`）之后插入：

```kotlin
        // The same channel from the other side: upstream's offload writes the trimmed prefix through the
        // SessionTranscriptWriter this call site has just disabled, into a copy with no reader here
        // (session_search is removed below), no read-back path and no delete in clearSession. Pinning the
        // tier is also what makes the number /context reports the number the runtime actually uses — read
        // from AutoCompactionTier on both sides instead of inferred from upstream's defaults.
        agentBuilder.compaction(AutoCompactionTier.auto())
```

并在 import 区加 `import com.agnetix.harnax.harness.compaction.AutoCompactionTier`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q spotless:apply`（不带 `-am`），再跑 Step 2 那条命令。
Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.agnetix.harnax.harness.HarnessAgentLauncherCompactionTest`。

- [ ] **Step 5: 回归这一条影响的既有判据**

Run: `mvn -q -pl harnax-harness-core -am test -Dtest=HarnessAgentLauncherMemoryTest,HarnessAgentBuilderTranscriptTest,HarnessAgentBuilderCompactionTest -DfailIfNoTests=false 2>&1 | tail -30`
Expected: 三个类各一行 `Tests run: ... -- in ...`，0 失败。若 memory 那批转红，先读失败消息再判断——它测的是 memory 两道闸，档位改动不该动到它；真动到了就是改了行为，回到设计稿第 5 节裁决，不要顺手改断言。

- [ ] **Step 6: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncherCompactionTest.kt
git commit -m "feat(compaction): 装配链把档位钉给上游——offload 与 disableTranscript 同判据，读数与运行时从此同源"
```

---

### Task 4: 两条消费路径改读单一来源

**Files:**
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt:427-480`
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/compaction/ContextCompactionService.kt:139-149`
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/HarnessAgentContextArchiveAndUsageTest.kt:309-341`（只改注释里那句「reserved is 20_000 by default」，数值断言不动）
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/compaction/ContextCompactionServiceTest.kt`（不新增用例：它断言的是压缩结果形状，档位同源由 Task 1 的差异用例钉住）

**Interfaces:**
- Consumes: `AutoCompactionTier.RESERVED_TOKENS`、`AutoCompactionTier.TRIGGER_MESSAGES`、`AutoCompactionTier.command(keepTokens)`。
- Produces: `ContextUsageResponse.triggerTokens` / `triggerMessages` 取值不变；`/compact` 行为不变。

- [ ] **Step 1: 先确认这一步不该有红**

Run: `mvn -q -pl harnax-harness-core -am test -Dtest=HarnessAgentContextArchiveAndUsageTest -DfailIfNoTests=false 2>&1 | tail -20`
Expected: 全绿。**这一步不写新测试**：读数数值本轮有意不变，改动是「从推出来」换成「从钉住的读」。若这一步就红，说明 Task 3 已经把行为改掉了，先回去核 `offload` 之外还有哪一项跟着变了。

- [ ] **Step 2: 改 `contextUsage`**

`HarnessAgentWrapper.kt`：删掉 `:436` 的 `val defaultConfig = CompactionConfig.builder().build()`，把 `:439` 与 `:450` 对它的引用换成常量，`triggerTokens` 私有方法（`:473-480`）去掉 `config` 参数：

```kotlin
    fun contextUsage(lastCallInputTokens: Int?): ContextUsageResponse? {
        val context = getLiveAgentState()?.context ?: return null
        val estimated = TokenCounterUtil.calculateToken(context)
        val modelWindow = harnessAgent.model.contextWindowSize
        val (window, source) = resolveContextWindow(modelWindow)
        // The ratio answers "how full is the window", so it goes on the real request size. [estimatedTokens]
        // stays the trigger's own number, reported as-is rather than used here.
        val numerator = (lastCallInputTokens ?: estimated).toDouble()
        return ContextUsageResponse(
            messageCount = context.size,
            estimatedTokens = estimated,
            lastCallInputTokens = lastCallInputTokens,
            contextWindow = window,
            windowSource = source,
            ratio = if (window > 0) numerator / window else 0.0,
            triggerTokens = triggerTokens(modelWindow),
            triggerMessages = AutoCompactionTier.TRIGGER_MESSAGES,
        )
    }
```

```kotlin
    /**
     * Where the automatic path compacts this model, recomputed as `CompactionMiddleware.resolveEffectiveConfig`
     * does: the window minus the margin this runtime pins, clamped to half the window when the margin would
     * eat it, and the fallback constant when no window is known at all.
     */
    private fun triggerTokens(modelWindow: Int): Int {
        if (modelWindow <= 0) return CompactionConfig.FALLBACK_TRIGGER_TOKENS
        val trigger = modelWindow - AutoCompactionTier.RESERVED_TOKENS
        return if (trigger <= 0) maxOf(1, modelWindow / 2) else trigger
    }
```

`resolveContextWindow`（`:459-466`）不动，它仍把 `CompactionConfig.FALLBACK_TRIGGER_TOKENS` 当窗口兜底。KDoc `:427-428` 那句「The trigger numbers come from upstream's default [CompactionConfig] because that is what this runtime builds with — no builder call here overrides the compaction tier.」改成：

```
     * The trigger numbers are harnax's own pinned tier ([AutoCompactionTier]), which the launcher passes to
     * the builder — so this is the same number the middleware will consult, not a reading of its defaults.
```

若 `CompactionConfig` 这一 import 只剩 `FALLBACK_TRIGGER_TOKENS` 一处引用，import 保留；`AutoCompactionTier` 是 `internal object`，同模块可直接引用，需要 `import com.agnetix.harnax.harness.compaction.AutoCompactionTier`。

- [ ] **Step 3: 改 `commandConfig`**

`ContextCompactionService.kt:139-149` 整块替换为委托，KDoc 留在这个文件里说明档位归 `AutoCompactionTier`：

```kotlin
    /**
     * The tier a command runs on: [AutoCompactionTier.command] — the same numbers the automatic path uses,
     * with `triggerMessages(1)` so no threshold can answer a user who asked for this, both file-writing
     * steps off, and `keepTokens` only moving where the tail starts.
     */
    private fun commandConfig(keepTokens: Int?): CompactionConfig = AutoCompactionTier.command(keepTokens)
```

- [ ] **Step 4: 更新那行会误导的注释**

`HarnessAgentContextArchiveAndUsageTest.kt:316` 的 `// reserved is 20_000 by default, so this is where the automatic path would fire.` 改为 `// reserved is the margin harnax pins, so this is where the automatic path would fire.`（三条 `assertEquals` 一个都不动。）

- [ ] **Step 5: 跑这两处涉及的用例**

Run: `mvn -q spotless:apply`（不带 `-am`），再 `mvn -q -pl harnax-harness-core -am test -Dtest=HarnessAgentContextArchiveAndUsageTest,ContextCompactionServiceTest,HarnessAgentSessionHistoryReadTest -DfailIfNoTests=false 2>&1 | tail -30`
Expected: 三类各一行 `Tests run: N, Failures: 0 -- in ...`；数值断言原样绿。

- [ ] **Step 6: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt \
        harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/compaction/ContextCompactionService.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/HarnessAgentContextArchiveAndUsageTest.kt
git commit -m "refactor(compaction): 占用读数与 /compact 都改读钉住的档位——不再自建上游默认件推数"
```

---

### Task 5: 全量门禁 + 真栈复验

**Files:**
- 无源码改动；产出留档在 `tmp/gate-2026-10-08/`（该目录已被 git 忽略，**不要** `git add`）。

- [ ] **Step 1: harness-core 全量**

Run: `mvn -q spotless:apply`；再 `mvn -pl harnax-harness-core -am test 2>&1 | tee tmp/gate-2026-10-08/harness-core-full.log | tail -40`
Expected: `BUILD SUCCESS`；计数用
`grep -E '^\[INFO\] Tests run: [0-9]+, Failures: 0, Errors: 0, Skipped: [0-9]+$' tmp/gate-2026-10-08/harness-core-full.log | awk -F'[ ,]+' '{t+=$4} END {print t}'`
逐类判定只看带 `-- in com.agnetix...` 的行。

- [ ] **Step 2: 受影响模块全量**

Run: `mvn -pl harnax-agent-service -am test 2>&1 | tee tmp/gate-2026-10-08/agent-service-full.log | tail -40`（模块名以 `ls harnax-agent` 实际目录为准；Docker 在本机可用，Testcontainers 那批照跑，minio 相关类在 `com.agnetix.harnax.harness.memory.**` / `harness.minio.MinioBaseStoreCasTest`。）
Expected: `BUILD SUCCESS`，0 failures / 0 errors。

- [ ] **Step 3: 部署 agent-service**

Run: `bash harnax-deploy/deploy-service.sh agent-service`（约 4 分钟；后台跑，pid 落盘后再看日志，避免读到旧日志假绿。）
Expected: `docker compose ps` 里 agent-service `(healthy)`。

- [ ] **Step 4: 真栈判据 a — 阈值等于钉出来的数**

admin 登录取 token（`POST /api/admin/auth/cli-login`，admin/admin123，宿主 28080），把某测试模型的 `context_window` 先填 4000，发一轮聊天（`POST /api/router/agent/chat`，28081，body 必带 `"type":"CHAT"`），再 `GET /api/router/agent/context/{sessionId}`。
Expected: `triggerTokens == 4000 - 20000 <= 0 → max(1, 4000/2) = 2000`（即 clamp 那一支），`triggerMessages == 50`，`windowSource == MODEL_FIELD`。
判据成立的标准是读数与按钉值手算的结果一致，且与改动前那一份（`prod_doc` §10.1 第 1 条记录的 `triggerTokens 111072` 那一形）形状相同、只是窗口档位不同。

- [ ] **Step 5: 真栈判据 b — offload 不再写盘**

窗口填 4000 让自动压缩真触发（连续几轮直到 `agent_state` 的 `$.context` 首条 `name = __compaction_summary__`）。压缩发生后，按本轮允许的通道查 `agents/<agentId>/sessions/<sessionId>` 这份副本有没有新增正文：
- 首选 `GET /api/router/agent/workspace/{sessionId}/files?path=%2Fworkspace` 列目录 + `…/read?path=…` 读文件（带 `X-Api-Key`）；
- 若该路径落在 workspace root 之外、这两个端点看不到，就改从对象存储侧核（minio 桶列表），仍取不到则**如实记「未验」**并写进文档 §10.2，不得用「单测已断言 offload=false」冒充产物级观察。

Expected: 取到与改动前的对照形状——改动前 §10.1 第 9 条记的是「transcript 通道运行时没有写过对象」，本轮要补的是压缩这一头也不写。

- [ ] **Step 6: 真栈判据 c — 主断言顺带再取一次**

压缩后按页面读路径重取历史。
Expected: 条数与逐条正文长度与压缩前一致、页面里摘要气泡命中 0。

- [ ] **Step 7: 留档**

把三条判据的原始读数（响应 JSON、目录清单、`agent_state` 查询结果）落到 `tmp/gate-2026-10-08/`，文件名带判据号。

---

### Task 6: 文档回写（成对两份）

**Files:**
- Modify: `prod_doc/harness-compaction.zh-CN.md`（§5 末尾、§6、§7、§10.1/§10.2、§11 第一条 `:264`）
- Modify: `prod_doc/harness-compaction.en-US.md`（同结构，逐条对应）

**Interfaces:**
- Consumes: Task 5 三条判据的留档读数。
- Produces: 现状文档里「自动压缩档位没被 harnax 钉过」这一说法清零。

- [ ] **Step 1: §5 末尾加档位段**

并入 `/compact 命令压缩` 一节末尾，**不新增编号章节**（免得与别处对 § 的引用撞号）。内容按 as-built 写：`AutoCompactionTier` 那张常量表（值 + 一句理由）、`auto()` 与 `command()` 只差三项、`offloadBeforeCompact=false` 的三条判据（同一个 `SessionTranscriptWriter`、`session_search` 已摘除、不进 `clearSession`）、`summaryPrompt`/`model`/`truncateArgs` 三项显式不钉值的理由。只写当前状态，不写「原来是默认值现在改了」这类演进句式。

- [ ] **Step 2: §6 改阈值口径**

把「那个阈值是按上游默认件推出来的」改口为「减数是 harnax 钉住的 `RESERVED_TOKENS = 20000`，与运行时 middleware consult 的那份 config 同源」；并保留 §6 原有的动态尾档与 clamp 说明（形状未变）。

- [ ] **Step 3: §7 改动清单加一行**

一行写明：新增 `AutoCompactionTier`（单一来源）、`HarnessAgentBuilder.compaction` 透传、装配链一处调用、读侧与命令侧各改一处。

- [ ] **Step 4: §10 加本轮记录**

在 §10.1 末尾追加一条（编号顺延），逐条注明判据取自哪一层（单测/容器级/真栈读数/产物级），并写 Task 5 三条的原始数字。b 若未验，落 §10.2（同样编号顺延）。**§10.1 与 §10.2 的条目总数与文末「对外演示口径缺口」段里提到的计数都要当场重算**，别沿用旧数。

- [ ] **Step 5: §11 划出该条**

删除 `## 11. 明确不在本轮` 的第一条 bullet（`自动压缩档位的显式化（给装配层钉 triggerTokens/keepTokens/prune），以及 flushBeforeCompact 在自动路径上的取舍。`）。该节剩余条目不动。

- [ ] **Step 6: 英文件逐条对应**

按同一结构改 `.en-US.md` 的同名五处，数值与键名逐字一致。

- [ ] **Step 7: 成对核验**

Run: `for f in prod_doc/harness-compaction.zh-CN.md prod_doc/harness-compaction.en-US.md; do echo "== $f"; grep -c "^## " $f; grep -n "AutoCompactionTier\|offloadBeforeCompact" $f | head -20; done`
Expected: 两份章节数一致；两处的 §11 都不再含「档位的显式化 / making the automatic tier explicit」那条；两份都提到 `AutoCompactionTier`。
再跑一遍「现状文档禁历史对照」关键词扫描：`grep -nE "原来|之前是|改成了|previously|used to |no longer " prod_doc/harness-compaction.*.md`，命中就逐条改成现状句式（真栈复验记录那节里的「改动前后对照」属于取数事实，允许保留）。

- [ ] **Step 8: 提交（文档一笔）**

```bash
git add prod_doc/harness-compaction.zh-CN.md prod_doc/harness-compaction.en-US.md \
        docs/superpowers/specs/2026-10-08-auto-compaction-tier-design.md \
        docs/superpowers/plans/2026-10-08-auto-compaction-tier.md
git commit -m "docs(compaction): 档位显式化 as-built 折回成对两份文档——§5 档位段/§6 口径/§10 真栈记录/§11 划出"
```
