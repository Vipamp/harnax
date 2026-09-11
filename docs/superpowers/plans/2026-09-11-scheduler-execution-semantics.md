# 定时任务执行语义与停止链路修复（S0 + S1）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 修好定时任务"完成/停止"链路上的 6 个正确性缺陷，并把执行收回 Quartz 线程内，使后续换 JDBC 集群存储时故障接管与优雅停机真的生效。

**架构：** 执行状态全部落在 `agent_task_log` 行的状态机上（3 运行中 → 1/0 定态，3 → 4 停止中 → 5 已停止，2 超时由回收写入）。本轮把三处"猜测"换成"事实"：中断是否命中由 agent-service 如实返回、被误判超时的行由拥有真实结果的一方写回、任务是否还在跑由宽限窗口后的 SQL 判定。同时 `AgentTaskJob` 不再起裸线程，改为在 Quartz 线程内同步跑完。

**技术栈：** Kotlin + Spring Boot 4 + Quartz（RAMJobStore，本轮**不动**存储方式）+ MyBatis（XML）+ MySQL 8（Testcontainers 集成测试）+ JUnit5/Mockito + React（webui）+ Go（harnax-cli）。

**规格：** [docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md](../specs/2026-09-11-scheduler-cluster-design.md) 的第 4 节与里程碑 S0/S1。本计划只做 S0+S1；S2（独立库 + JDBC 集群 + reconcile）、S3（域搬迁）、S4（one-shot 收口）各需一份独立计划，**不要在本计划里顺手实现**。

---

## 开始前的工作区注意（必读）

1. **当前分支 `kotlin-dev` 有一大片未提交改动**，属于另一条在途工作（MCP 加密 / Harness / AdminApiClient）。其中 `harnax-admin/.../controller/InternalApiController.kt` 与 `InternalApiControllerTest.kt` **正在被改**。本计划任务 3 要删的 legacy 端点就在 `InternalApiController.kt` 里。**先执行：**

```bash
git status --short harnax-admin harnax-agent harnax-channel
git stash list
```

   如果 `InternalApiController.kt` 仍是 modified 状态，**跳过任务 10 的步骤 2 与步骤 3**（它们改的正是这个文件），任务 10 其余步骤照做。不要 `git stash`，不要替别人保存现场。

2. **绝不使用 `git add -A` / `git add .`**。每次 commit 只 add 本任务明确列出的路径。

3. 本计划会改 `harnax-entity`（mapper XML + 接口）与 `harnax-protocol`（无改动，仅引用）。`harnax-entity` 的测试需要 Docker：`docker ps` 确认 daemon 在跑，否则那些测试会 skip 而不是失败——**看到 skip 不要当成通过**。

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `harnax-scheduler/.../controller/SchedulerController.kt` | 执行面 HTTP 入口，出业务码 40901 | 修改 |
| `harnax-scheduler/.../service/impl/SchedulerServiceImpl.kt` | 任务生命周期与执行状态机；本轮改 trigger 返回值、定态分支、超时基准 | 修改 |
| `harnax-scheduler/.../job/AbstractAgentTaskJob.kt` | 一次触发的共享执行体（抢锁 → 同步执行） | 创建 |
| `harnax-scheduler/.../job/AgentTaskJob.kt` | 允许重叠执行的 job 类 | 重写 |
| `harnax-scheduler/.../job/AgentTaskNonConcurrentJob.kt` | `concurrent=0` 任务用的互斥 job 类 | 创建 |
| `harnax-scheduler/.../job/SchedulerHousekeepingJob.kt` | 周期清理 guard 行与泄漏锁 | 创建 |
| `harnax-scheduler/.../client/RouterClient.kt` | `sendCommand` 如实返回是否送达 | 修改 |
| `harnax-scheduler/.../service/AgentTaskExecutionGuard.kt` | 多实例抢锁与清理策略（含自身超时基准） | 修改 |
| `harnax-entity/.../mapper/AgentTaskLogMapper.kt` + `resources/mapper/AgentTaskLogMapper.xml` | 状态机 CAS 语句；新增 `reclaimExpired`、`expireStale` 宽限 | 修改 |
| `harnax-entity/.../mapper/AgentTaskExecutionMapper.kt` + `resources/mapper/AgentTaskExecutionMapper.xml` | 新增 `deleteStaleRunning` | 修改 |
| `harnax-agent/.../runner/AgentRunner.kt` + `impl/DefaultAgentRunner.kt` | `interrupt` 返回是否命中 | 修改 |
| `harnax-agent/.../controller/AgentController.kt` | `/chat/interrupt/{sessionId}` 端点如实响应 | 修改 |
| `harnax-admin/.../service/AgentTaskLogService.kt`(+Impl) | 删死代码 `save()` | 修改（任务 10） |
| `harnax-admin/.../service/impl/SchedulerClientImpl.kt` | 修正与事实相反的注释 | 修改（任务 10） |
| `harnax-cli/cmd/task.go` / `task_test.go` | 日志字段 `id` + `task stop` 子命令 | 修改 / 创建 |
| `harnax-webui/src/pages/agent-task/index.tsx` | 冲突提示改判 code 而非文案 | 修改 |
| 新建测试：`scheduler/.../controller/SchedulerControllerTest.kt`、`scheduler/.../job/AgentTaskJobExecutionTest.kt`、`scheduler/.../job/SchedulerHousekeepingJobTest.kt`、`scheduler/.../service/impl/SchedulerScheduleTaskTest.kt`、`entity/.../mapper/AgentTaskExecutionMapperTest.kt`、`cli/cmd/task_test.go` | — | 创建 |
| 改动测试：`SchedulerStopStateMachineTest`、`SchedulerStartupLoadTest`、`entity/AgentTaskLogMapperTest`、`agent-service/DefaultAgentRunnerTest` | — | 见各任务 |

---

## 任务 1：trigger 冲突返回业务码 40901

**背景：** webui 现在靠 `response.message.toLowerCase().includes('already running')` 判断冲突（`harnax-webui/src/pages/agent-task/index.tsx:122`）。后端有两条冲突路径，只有其中一条（异常 message 被拼进 `ResultVo` 的那条）能命中，集群抢锁失败那条永远落到通用失败文案。改为业务码。

admin 侧**无需改动**：`AgentTaskServiceImpl.triggerTask` 直接 `return schedulerClient.triggerTask(id)`，而 `SchedulerClientImpl.postToInstance`（:72-86）把 scheduler 的 `ResultVo` 原样反序列化，`code` 字段天然透传。任务里要**验证**这件事而不是假设。

**文件：**
- 修改：`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerController.kt:19-31`
- 修改：`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt:268-285`
- 修改：`harnax-webui/src/pages/agent-task/index.tsx:120-127`
- 创建：`harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerControllerTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerControllerTest.kt`：

```kotlin
package com.agnetix.harnax.scheduler.controller

import com.agnetix.harnax.scheduler.service.SchedulerService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.mockito.kotlin.whenever

/**
 * A conflict is a business outcome, not a string to pattern-match: the caller needs a code that
 * survives the admin proxy, because the proxy forwards the message verbatim and the message has
 * already drifted from what the frontend matches on.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerControllerTest {

    @Mock
    private lateinit var schedulerService: SchedulerService

    @Test
    fun `a rejected trigger because an execution is in flight answers with business code 40901`() {
        val controller = SchedulerController(schedulerService)
        whenever(schedulerService.triggerManually(7L)).thenReturn(false)

        val result = controller.trigger(7L)

        assertEquals(40901, result.code)
    }

    @Test
    fun `a successful trigger still answers 200`() {
        val controller = SchedulerController(schedulerService)
        whenever(schedulerService.triggerManually(8L)).thenReturn(true)

        assertEquals(200, controller.trigger(8L).code)
    }

    @Test
    fun `an unexpected failure stays a generic error, not a conflict`() {
        val controller = SchedulerController(schedulerService)
        whenever(schedulerService.triggerManually(9L)).thenThrow(RuntimeException("db down"))

        assertEquals(500, controller.trigger(9L).code)
    }
}
```

- [ ] **步骤 2：运行测试，确认失败**

```bash
cd /Users/heqingsong/code/my_project/harnax
mvn -q -pl harnax-scheduler -Dtest=SchedulerControllerTest test
```
预期：`a rejected trigger…` 失败，报 `expected:<40901> but was:<500>`（现在走的是 `ResultVo.error(String)` → 500）。其余两条通过。

- [ ] **步骤 3：controller 出业务码**

改 `SchedulerController.kt` 的 `trigger`（原 :19-31），并加 companion 常量：

```kotlin
    @Operation(summary = "Manually trigger a one-time task execution")
    @PostMapping("/tasks/{id}/trigger")
    fun trigger(@PathVariable id: Long): ResultVo<String> = try {
        if (schedulerService.triggerManually(id)) {
            ResultVo.success("Task triggered")
        } else {
            // 40901, not a message: both "already running" and "another instance won the lock" mean
            // the same thing to the caller — try again later.
            ResultVo.error(CODE_EXECUTION_IN_PROGRESS, "Task execution is already in progress")
        }
    } catch (e: Exception) {
        log.error("Failed to trigger task: id={}", id, e)
        ResultVo.error("Failed to trigger task: ${e.message}")
    }
```

在类末尾（`stopTask` 方法之后、右花括号之前）加：

```kotlin
    companion object {
        /** The task already has a live execution; the caller should poll instead of retrying. */
        const val CODE_EXECUTION_IN_PROGRESS = 40901
    }
```

- [ ] **步骤 4：`triggerManually` 不再抛异常表达"正在执行"**

改 `SchedulerServiceImpl.kt:243-248` 与 `:274-285`。**两处都要改**——`runTaskOnce` 与 `triggerManually` 各有一份相同的抛异常代码：

```kotlin
        // Guard: reject if task already has an active running execution
        if (hasActiveRunningLog(task.id)) {
            log.warn("Task {} has an active running execution, rejecting trigger", task.id)
            return false
        }
```

`runTaskOnce` 里同样把 `throw RuntimeException("Task is already running, please wait for it to complete")` 换成 `return false`，并把日志文案里的 `runOnce` 保留。

- [ ] **步骤 5：运行测试，确认通过**

```bash
mvn -q -pl harnax-scheduler -Dtest=SchedulerControllerTest test
```
预期：3 条全 PASS。

再跑本模块全量，确认没有别的测试依赖那两处 throw：

```bash
mvn -q -pl harnax-scheduler test
```
预期：BUILD SUCCESS。若 `SchedulerStartupLoadTest` 有断言依赖"抛异常"，把它改成断言返回 false（该文件当前无此类用例，出现即说明假设错了，读一下那个用例再改）。

- [ ] **步骤 6：webui 改判 code**

`harnax-webui/src/pages/agent-task/index.tsx:120-127` 整段替换：

```tsx
          } else if (response.code === 40901) {
            message.warning(intl.formatMessage({ id: 'pages.agentTask.alreadyRunning', defaultMessage: 'Task is already running, please wait for it to complete' }));
          } else {
            message.error(intl.formatMessage({ id: 'pages.agentTask.triggerFailed', defaultMessage: 'Failed to trigger task' }));
          }
```

i18n key `pages.agentTask.alreadyRunning` 已存在（`src/locales/en-US/pages.ts:1111` 与 zh-CN 对应文件），**不要新增 key**。

- [ ] **步骤 7：类型检查**

```bash
cd harnax-webui && npm run tsc
```
预期：无新增报错（若仓库基线本身有报错，比较改动前后数量不增即可）。回到仓库根目录。

- [ ] **步骤 8：Commit**

```bash
cd /Users/heqingsong/code/my_project/harnax
git add harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerController.kt \
        harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt \
        harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerControllerTest.kt \
        harnax-webui/src/pages/agent-task/index.tsx
git commit -m "fix(调度): 触发冲突改用业务码 40901，替换前端的文案匹配"
```

---

## 任务 2：CLI 日志字段改 `id`，并补 `task stop` 子命令

**背景：** `harnax-cli/cmd/task.go:24` 声明 `LogID int64 \`json:"logId"\``，而 admin 的 `GET /api/admin/agent-tasks/{id}/logs` 下发的是实体 `AgentTaskLog`，JSON 字段是 `id`（`AgentTaskController.kt:147` 返回 `Page<AgentTaskLog>`）。所以 CLI 表格里 Log ID 恒为 0。另外 **CLI 根本没有 stop 子命令**——所谓"从 CLI 停止任务从未生效"的真实原因是功能不存在。

**文件：**
- 修改：`harnax-cli/cmd/task.go:23-27`（struct）、`:348`（表格）、命令注册区（`init()` 内，约 :420-450）
- 创建：`harnax-cli/cmd/task_test.go`

- [ ] **步骤 1：编写失败的测试**

创建 `harnax-cli/cmd/task_test.go`：

```go
package cmd

import (
	"encoding/json"
	"testing"
)

// TestTaskLogUsesIDField pins the field name the admin API actually sends: the endpoint returns the
// AgentTaskLog entity, whose key is `id`. Reading `logId` silently yields 0, which makes
// `task stop <id>` target the wrong row.
func TestTaskLogUsesIDField(t *testing.T) {
	raw := `{"id":42,"taskName":"Daily News","status":3,"startTime":"2026-09-11 10:00:00"}`

	var log TaskLog
	if err := json.Unmarshal([]byte(raw), &log); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if log.ID != 42 {
		t.Fatalf("ID = %d, want 42", log.ID)
	}
}
```

- [ ] **步骤 2：运行，确认失败**

```bash
cd /Users/heqingsong/code/my_project/harnax/harnax-cli
go test ./cmd -run TestTaskLogUsesIDField -v
```
预期：编译失败，报 `type TaskLog has no field and no method ID`（Go 对未知字段的报错形式）。这就是"红"。

- [ ] **步骤 3：改 struct 与用例**

`harnax-cli/cmd/task.go:23-27`：

```go
type TaskLog struct {
	ID        int64  `json:"id"`
	TaskName  string `json:"taskName"`
	Status    int    `json:"status"`
	StartTime string `json:"startTime"`
}
```

`task.go:348`（`taskLogsCmd` 的表格行）：

```go
				fmt.Sprintf("%d", item.ID),
```

- [ ] **步骤 4：运行，确认通过**

```bash
cd /Users/heqingsong/code/my_project/harnax/harnax-cli && go test ./cmd -run TestTaskLogUsesIDField -v
```
预期：`--- PASS: TestTaskLogUsesIDField`。

- [ ] **步骤 5：新增 `task stop` 子命令**

在 `taskLogsCmd` 定义之前插入（照 `taskTriggerCmd` 的既有形状）：

```go
var taskStopCmd = &cobra.Command{
	Use:   "stop <log-id>",
	Short: "Request a stop for a running task execution",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		// The path takes a LOG id, not a task id: one task can have several executions, and only
		// the execution row carries the session to interrupt.
		_, err = c.Request(ctx, "POST", fmt.Sprintf("%s/logs/%s/stop", taskPath, args[0]), nil, nil)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Stop requested for task execution.")
	},
}
```

在 `init()` 的 `taskCmd.AddCommand(taskLogsCmd)` **之前**加一行：

```go
	taskCmd.AddCommand(taskStopCmd)
```

- [ ] **步骤 6：验证构建与帮助文本**

```bash
cd /Users/heqingsong/code/my_project/harnax/harnax-cli
go build ./... && go vet ./cmd && go run . task --help
```
预期：构建无输出；`go vet` 无输出；帮助文本的 Available Commands 列表出现 `stop  Request a stop for a running task execution`。

- [ ] **步骤 7：Commit**

```bash
cd /Users/heqingsong/code/my_project/harnax
git add harnax-cli/cmd/task.go harnax-cli/cmd/task_test.go
git commit -m "fix(cli): 定时任务日志字段改为 id，并补 task stop 子命令"
```

---

## 任务 3：mapper 层——定态可回收、超时判定带宽限

**背景：** 两件独立的事，同一个文件所以一个任务。
(a) `finishExecution`（`WHERE status = 3`）返回 0 行时，`SchedulerServiceImpl` 一律按"被用户停止"处理并调 `finalizeStopped`。但那一行也可能是**已被 `expireStale` 抢先写成 2**：此时 `finalizeStopped`（`WHERE status = 4`）也是 0 行，代码只 `log.warn`，真实结果永久丢失。需要一条 `2 → {0,1}` 的回收语句。
(b) `expireStale` 的判定窗口正好等于任务超时本身，没有任何余量：一次在 agent-service 排队的正常执行会被判超时。加 1.5 倍宽限。

**关于不变量的说明（必须写进注释）：** `AgentTaskLogMapper` 的类注释声明"A late writer can never resurrect a row that already reached a terminal status"。`reclaimExpired` 是唯一例外：**仅** 拥有真实结果的执行线程可以把 2 改回 0/1，因为 2 本身就是一次猜错。这不是放松不变量，是修正一个已知为假的观测。其余状态转移不变。

**文件：**
- 修改：`harnax-entity/src/main/resources/mapper/AgentTaskLogMapper.xml:111-127`（`expireStale`）+ 新增 `reclaimExpired`
- 修改：`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentTaskLogMapper.kt:36-42`
- 修改：`harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/AgentTaskLogMapperTest.kt:161-177` + 新增用例

- [ ] **步骤 1：编写失败的集成测试**

在 `AgentTaskLogMapperTest.BasicCrudTests` 内、`finalizeStopped should close a stopping row as stopped` 之后插入：

```kotlin
        @Test
        @DisplayName("reclaimExpired - 拥有真实结果的执行可以回收被误判超时的行")
        fun `reclaimExpired should only take back a row the reaper marked timeout`() {
            val expired = insertExecutionLog(taskId = 1L, initialStatus = 2)
            val running = insertExecutionLog(taskId = 1L, initialStatus = 3)
            val stopped = insertExecutionLog(taskId = 1L, initialStatus = 5)

            val reclaim = AgentTaskLog().apply {
                id = expired.id
                status = 1
                response = "real result"
                errorInfo = ""
                endTime = LocalDateTime.now()
                durationMs = 480_000L
            }
            assertEquals(1, agentTaskLogMapper.reclaimExpired(reclaim))
            assertEquals(1, agentTaskLogMapper.selectById(expired.id)?.status)
            assertEquals("real result", agentTaskLogMapper.selectById(expired.id)?.response)

            // 运行中与已停止的行都不受影响
            assertEquals(0, agentTaskLogMapper.reclaimExpired(running.apply { status = 1 }))
            assertEquals(3, agentTaskLogMapper.selectById(running.id)?.status)
            assertEquals(0, agentTaskLogMapper.reclaimExpired(stopped.apply { status = 1 }))
            assertEquals(5, agentTaskLogMapper.selectById(stopped.id)?.status)
        }

        @Test
        @DisplayName("expireStale - 宽限窗口内的行不回收，超出才回收")
        fun `expireStale should keep a grace window before reclaiming`() {
            // seed: task 1 的 timeout_seconds = 300 → 回收窗口 450s
            val insideGrace = insertExecutionLog(taskId = 1L, initialStatus = 3, startedSecondsAgo = 400L)
            val outsideGrace = insertExecutionLog(taskId = 1L, initialStatus = 3, startedSecondsAgo = 700L)

            val expired = agentTaskLogMapper.expireStale(300)
            assertTrue(expired >= 1, "at least the row past the grace window must be reclaimed")

            assertEquals(3, agentTaskLogMapper.selectById(insideGrace.id)?.status)
            assertEquals(2, agentTaskLogMapper.selectById(outsideGrace.id)?.status)
        }
```

同时**改写**既有那条 `expireStale should expire only rows past their own task timeout`（:163-177）——它的 400s 样例现在落在宽限区内，不再是"应被回收"：

```kotlin
        @Test
        @DisplayName("expireStale - 按各任务自己的 timeout_seconds 回收残留")
        fun `expireStale should expire only rows past their own task timeout`() {
            // seed: task 1 的 timeout_seconds = 300（窗口 450s）, task 2 = 600（窗口 900s）
            val stale = insertExecutionLog(taskId = 1L, initialStatus = 3, startedSecondsAgo = 700L)
            val staleStopping = insertExecutionLog(taskId = 1L, initialStatus = 4, startedSecondsAgo = 700L)
            val fresh = insertExecutionLog(taskId = 1L, initialStatus = 3)
            // task 2 的窗口是 900s，700s 的行必须还活着
            val withinLongerTimeout = insertExecutionLog(taskId = 2L, initialStatus = 3, startedSecondsAgo = 700L)

            val expired = agentTaskLogMapper.expireStale(300)
            assertTrue(expired >= 2, "至少两条残留应被回收, 实际=$expired")

            assertEquals(2, agentTaskLogMapper.selectById(stale.id)?.status)
            assertEquals(2, agentTaskLogMapper.selectById(staleStopping.id)?.status)
            assertEquals(3, agentTaskLogMapper.selectById(fresh.id)?.status)
            assertEquals(3, agentTaskLogMapper.selectById(withinLongerTimeout.id)?.status)
        }
```

- [ ] **步骤 2：运行，确认失败**

```bash
cd /Users/heqingsong/code/my_project/harnax
mvn -q -pl harnax-entity -Dtest=AgentTaskLogMapperTest test
```
预期：编译失败 `unresolved reference: reclaimExpired`。**若整类被 skip（Docker 未跑）而不是失败，先修 Docker 再继续**——skip 不算验证。

- [ ] **步骤 3：加接口方法与类注释里的例外**

`AgentTaskLogMapper.kt`：类注释最后一段改为如实描述唯一例外（替换原 :6-11 的 `* 3 (running) -> ...` 与 `* that already reached a terminal status.` 两行的结尾）：

```kotlin
/**
 * Every write on an execution log carries a status guard in its WHERE clause. Several scheduler
 * nodes and the stop path can touch the same row, so the guard — not the caller — decides who wins:
 * 3 (running) -> {0, 1, 5}, 4 (stopping) -> 5, and 2 (expired) -> {0, 1} only through
 * [reclaimExpired], which is the one documented exception below. A late writer can never resurrect a
 * status the user or the stop path put there on purpose.
 */
```

在 `finalizeStopped` 声明之后插入：

```kotlin
    /**
     * Take back a row the reaper had judged [AgentTaskLog] status 2 (timeout) and write the real
     * outcome over it. Only the execution thread that actually produced a result calls this, and
     * status 2 is written by no path but `expireStale` — so a row still at 2 means the reaper was
     * wrong, and the real result is the more truthful record.
     */
    fun reclaimExpired(log: AgentTaskLog): Int
```

- [ ] **步骤 4：写 XML 语句 + 宽限窗口**

`AgentTaskLogMapper.xml`：在 `finalizeStopped` 的 `</update>` 之后插入：

```xml
    <!--
      2 -> {0,1}: the reaper guessed, and the execution then proved otherwise by returning a result.
      Restricted to status 2 so this can never undo a user stop (4/5) or a real failure already
      written (0/1). The marker in error_info is what tells the two kinds of 0/1 apart later.
    -->
    <update id="reclaimExpired" parameterType="com.agnetix.harnax.entity.AgentTaskLog">
        UPDATE agent_task_log SET
            response = #{response},
            status = #{status},
            error_info = CONCAT(IFNULL(#{errorInfo}, ''), ' (completed after auto-expiry)'),
            end_time = #{endTime},
            duration_ms = #{durationMs}
        WHERE id = #{id} AND status = 2
    </update>
```

把 `expireStale`（原 :116-127）整个 `<update>` 替换为带宽限的版本，**消息里的秒数仍是任务自己的超时**（那是用户配的值），只有判定条件乘 1.5：

```xml
    <update id="expireStale">
        UPDATE agent_task_log l
        JOIN agent_task t ON t.id = l.task_id
        SET l.status = 2,
            l.end_time = NOW(),
            l.error_info = CONCAT('Auto-expired: no completion within ',
                IFNULL(NULLIF(t.timeout_seconds, 0), #{defaultTimeoutSeconds}), 's (likely service restart)'),
            l.duration_ms = TIMESTAMPDIFF(SECOND, COALESCE(l.start_time, l.create_time), NOW()) * 1000
        WHERE l.status IN (3, 4)
          AND COALESCE(l.start_time, l.create_time) &lt;
              DATE_SUB(NOW(), INTERVAL CEIL(IFNULL(NULLIF(t.timeout_seconds, 0), #{defaultTimeoutSeconds}) * 1.5) SECOND)
    </update>
```

- [ ] **步骤 5：运行，确认通过**

```bash
mvn -q -pl harnax-entity -Dtest=AgentTaskLogMapperTest test
```
预期：全 PASS（含新写的 2 条与被改写的 1 条）。

- [ ] **步骤 6：Commit**

```bash
git add harnax-entity/src/main/resources/mapper/AgentTaskLogMapper.xml \
        harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentTaskLogMapper.kt \
        harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/AgentTaskLogMapperTest.kt
git commit -m "fix(调度): 超时判定加宽限窗口，允许真实结果回收被误判的执行行"
```

---

## 任务 4：`agent_task_execution` 泄漏锁清理语句

**背景：** `tryAcquireLock` 插入 `status=0`（运行中）的行，若进程在 `updateExecutionStatus` 前死掉，该行永远停在 0，挡住同 `trigger_time` 的重投，也让表只增不减。现有 `deleteOldExecutions(beforeTime)` 只按 `create_time` 删过期行，删不掉"还在运行中"的语义（它是无状态条件的 DELETE）。需要一条按状态 + 时间双条件的清理。

**文件：**
- 修改：`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentTaskExecutionMapper.kt`
- 修改：`harnax-entity/src/main/resources/mapper/AgentTaskExecutionMapper.xml:36-38` 之后
- 创建：`harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/AgentTaskExecutionMapperTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `AgentTaskExecutionMapperTest.kt`（容器与注解形状照 `AgentTaskLogMapperTest`）：

```kotlin
package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTaskExecution
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * A guard row left at status 0 is not just stale data: it is a lock nobody holds.
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class AgentTaskExecutionMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var executionMapper: AgentTaskExecutionMapper

    @Test
    @DisplayName("deleteStaleRunning - 只删除超时仍未定态的抢锁行")
    fun `deleteStaleRunning should only remove locked rows past the deadline`() {
        val leaked = insertExecution(taskId = 1L, status = 0, ageSeconds = 900L, triggerTime = LocalDateTime.now())
        val held = insertExecution(taskId = 2L, status = 0, ageSeconds = 30L, triggerTime = LocalDateTime.now())
        val done = insertExecution(taskId = 3L, status = 1, ageSeconds = 900L, triggerTime = LocalDateTime.now())

        val deleted = executionMapper.deleteStaleRunning(LocalDateTime.now().minusSeconds(600))
        assertEquals(1, deleted)

        assertEquals(null, executionMapper.selectByTaskIdAndTriggerTime(leaked.taskId, leaked.triggerTime))
        // 被持有的锁必须还在——删了它就等于允许同一触发时间在两个节点上各跑一次
        assertEquals(held.id, executionMapper.selectByTaskIdAndTriggerTime(held.taskId, held.triggerTime)?.id)
        // 已定态的老行也不归这里清，那是保留期清理的职责
        assertEquals(done.id, executionMapper.selectByTaskIdAndTriggerTime(done.taskId, done.triggerTime)?.id)
    }

    private fun insertExecution(
        taskId: Long,
        status: Int,
        ageSeconds: Long,
        triggerTime: LocalDateTime,
    ): AgentTaskExecution = AgentTaskExecution().apply {
        this.taskId = taskId
        this.triggerTime = triggerTime.withNano(0)
        instanceId = "test-instance"
        this.status = status
        createTime = LocalDateTime.now().minusSeconds(ageSeconds)
        executionMapper.insert(this)
    }
}
```

三行输入只在 `status` 与 `create_time` 上有差别，所以 `deleted == 1` 同时证明了两个条件都生效；`held.id` 用自增主键比对，避免依赖 `selectByTaskIdAndTriggerTime` 的字段映射。

- [ ] **步骤 2：运行，确认失败**

```bash
mvn -q -pl harnax-entity -Dtest=AgentTaskExecutionMapperTest test
```
预期：编译失败 `unresolved reference: deleteStaleRunning`。

- [ ] **步骤 3：加接口方法与 XML**

`AgentTaskExecutionMapper.kt` 在 `deleteOldExecutions` 声明之后加：

```kotlin
    /**
     * Delete locks that were never released: a node acquired one and died before it could report an
     * outcome. Older than [beforeTime] and still status 0.
     */
    fun deleteStaleRunning(@Param("beforeTime") beforeTime: LocalDateTime): Int
```

`AgentTaskExecutionMapper.xml` 在 `deleteOldExecutions` 之后加：

```xml
    <!--
      A status-0 row older than the deadline is a lock whose holder is gone. It must go, or the
      (task_id, trigger_time) unique key blocks that trigger from ever being re-delivered.
    -->
    <delete id="deleteStaleRunning">
        DELETE FROM agent_task_execution WHERE status = 0 AND create_time &lt; #{beforeTime}
    </delete>
```

- [ ] **步骤 4：运行，确认通过**

```bash
mvn -q -pl harnax-entity -Dtest=AgentTaskExecutionMapperTest test
```
预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentTaskExecutionMapper.kt \
        harnax-entity/src/main/resources/mapper/AgentTaskExecutionMapper.xml \
        harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/AgentTaskExecutionMapperTest.kt
git commit -m "feat(调度): 新增抢锁行的泄漏清理语句"
```

---

## 任务 5：INTERRUPT 如实返回是否命中

**背景（本轮最实的修复）：** `DefaultAgentRunner.interrupt()` 用 `agentCache.getIfPresent(sessionId)`，未命中时什么都不做；调用方 `executeCommand` 的 INTERRUPT 分支**无论如何都返回 `success("Stream interrupted")`**，而 scheduler 的 `sendCommand` 连响应体都没接收（签名返回 `Unit`）。所以 agent-service 重启过、或 session→instance 映射过期被 reroute 之后，"停止"完全空转，用户看到的是"停止中"最终变成"超时"，而任务其实成功产出了。

**注意这条测试现状：** `DefaultAgentRunnerTest` 的 `executeCommand INTERRUPT cancels active stream`（约 :88-98）在**没有缓存 agent、没有活跃流**的前提下断言 `response.success == true`。它把 bug 固化成了期望，本任务必须**反转**它。

**文件：**
- 修改：`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentRunner.kt:50`
- 修改：`.../impl/DefaultAgentRunner.kt:180-184`（INTERRUPT 分支）、`:239-254`（interrupt 本体）
- 修改：`.../controller/AgentController.kt:90-96`
- 修改：`harnax-scheduler/.../client/RouterClient.kt:127-143`
- 修改：`harnax-scheduler/.../service/impl/SchedulerServiceImpl.kt:401-409`
- 测试：`harnax-agent/.../runner/impl/DefaultAgentRunnerTest.kt`、`harnax-scheduler/.../impl/SchedulerStopStateMachineTest.kt`

- [ ] **步骤 1：写失败的 agent-service 测试（反转现有断言 + 新增命中用例）**

在 `DefaultAgentRunnerTest.Interrupt` 内，把 `interrupt does nothing when no active stream` 替换为：

```kotlin
        @Test
        fun `interrupt reports a miss when neither an agent nor a stream is live`() {
            // The miss is the whole point: a caller must be able to tell "stopped" from "already gone".
            org.junit.jupiter.api.Assertions.assertFalse(runner.interrupt("nonexistent-session"))
        }
```

并在同一个 `@Nested inner class Interrupt` 里新增命中用例（`runner` 的构造与 `cachedAgent` 的注入方式照该测试文件里已有的"缓存命中"用例；若文件里没有现成的注入助手，用 `process()` 触发一次真实构建太重，改为下面这条基于 `activeCalls` 的轻量用例）：

```kotlin
        @Test
        fun `a blocking call in flight counts as a live execution`() {
            // activeCalls is what a blocking /chat registers; an interrupt must see it even when the
            // agent wrapper itself was rebuilt out from under the cache key.
            ReflectionTestUtils.invokeMethod<Any>(runner, "registerCall", "session-blocking")
            try {
                org.junit.jupiter.api.Assertions.assertTrue(runner.interrupt("session-blocking"))
            } finally {
                ReflectionTestUtils.invokeMethod<Any>(runner, "unregisterCall", "session-blocking")
            }
        }
```

同时改 `ExecuteCommand` 里那条既有断言（原 :88-98）：

```kotlin
        @Test
        fun `executeCommand INTERRUPT reports the miss instead of claiming success`() {
            val response = runner.executeCommand(
                CommandAgentRequest(sessionId = "session-1", command = CommandType.INTERRUPT),
            )

            org.junit.jupiter.api.Assertions.assertFalse(response.success)
            assertEquals("No live execution for this session on this instance", response.message)
        }
```

（该文件已 import 的断言函数保持不动，新用例里用全限定名以免漏 import。）

该测试文件需要新增两个 import：

```kotlin
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.test.util.ReflectionTestUtils
```

`ReflectionTestUtils` 来自 `spring-test`，由 `spring-boot-starter-test` 传递引入，该模块已在用 `MockitoExtension`，无需加依赖。

- [ ] **步骤 2：运行，确认失败**

```bash
mvn -q -pl harnax-agent/harnax-agent-service -Dtest=DefaultAgentRunnerTest test
```
预期：编译失败——`interrupt` 返回 `Unit`，不能用于断言。这就是"红"。

- [ ] **步骤 3：实现 interrupt 返回 Boolean**

`AgentRunner.kt:50` 签名改为：

```kotlin
    /**
     * Interrupt whatever is running for this session on **this** instance.
     * @return false when neither a cached agent nor a live call/stream exists — i.e. nothing was
     * advanced or stopped by the call. The scheduler turns that into "this execution is over".
     */
    fun interrupt(sessionId: String): Boolean
```

`DefaultAgentRunner.kt`：把 `activeCalls.add/remove` 抽成两个小方法（供上面测试用 `ReflectionTestUtils` 调用，也消掉两处重复），并改 `interrupt`：

```kotlin
    /** Registers a blocking (non-streaming) call as live for this session. */
    private fun registerCall(sessionId: String) {
        activeCalls.add(sessionId)
    }

    private fun unregisterCall(sessionId: String) {
        activeCalls.remove(sessionId)
        drainPendingRelease(sessionId)
    }
```

`process()` 里 `activeCalls.add(sessionId)` → `registerCall(sessionId)`，`finally` 块里两行（`activeCalls.remove` + `drainPendingRelease`）→ `unregisterCall(sessionId)`。

`interrupt` 本体（原 :239-254）：

```kotlin
    override fun interrupt(sessionId: String): Boolean {
        // 1. Interrupt the agent execution via HarnessAgent.interrupt() (works for both streaming and blocking calls)
        val agent = agentCache.getIfPresent(sessionId)?.agent
        agent?.interrupt()

        // 2. Also cancel active stream subscription (belt-and-suspenders for the streaming case)
        val subscription = activeStreams.remove(sessionId)
        subscription?.cancel()

        val live = agent != null || subscription != null || activeCalls.contains(sessionId)
        if (live) {
            log.info("Interrupted live execution for session=$sessionId (agent={}, stream={})", agent != null, subscription != null)
        } else {
            log.warn("Interrupt requested for session=$sessionId but nothing is live on this instance")
        }
        return live
    }
```

INTERRUPT 分支（原 :181-184）：

```kotlin
            CommandType.INTERRUPT -> {
                if (interrupt(sessionId)) {
                    CommandResponse.success(sessionId, message = "Stream interrupted")
                } else {
                    CommandResponse.failure(sessionId, "No live execution for this session on this instance")
                }
            }
```

`STOP_SANDBOX` 分支里那句 `interrupt(sessionId)` 现在返回 Boolean——保持原样即可（Kotlin 允许丢弃返回值），不要加 `@Suppress`。

`AgentController.kt:90-96`：

```kotlin
    fun interrupt(@PathVariable sessionId: String): ResultVo<String> {
        log.info("Interrupting stream for session=$sessionId")
        return if (agentRunner.interrupt(sessionId)) {
            ResultVo.success("OK")
        } else {
            ResultVo.error("No live execution for session $sessionId")
        }
    }
```

- [ ] **步骤 4：运行，确认通过**

```bash
mvn -q -pl harnax-agent/harnax-agent-service -Dtest=DefaultAgentRunnerTest test
```
预期：全 PASS。若 `clearSession also interrupts active stream` 因签名变化而红，它断言的是 `assertDoesNotThrow`，`clearSession` 内部丢弃 `interrupt()` 返回值即可，无需改测试。

- [ ] **步骤 5：scheduler 侧——写失败的测试**

在 `SchedulerStopStateMachineTest` 中，给已有的 `stop claims the running row and interrupts its session` 补一行桩（`sendCommand` 现在返回 Boolean，未打桩时 Mockito 对 Boolean 返回 false，会让该用例误走"未命中"分支）：

```kotlin
        whenever(routerClient.sendCommand("sess-11", CommandType.INTERRUPT)).thenReturn(true)
```

`a repeated stop while already stopping still interrupts` 同样补 `whenever(routerClient.sendCommand("sess-13", CommandType.INTERRUPT)).thenReturn(true)`。

然后新增两条：

```kotlin
    @Test
    fun `a stop that finds nothing live closes the row as stopped immediately`() {
        whenever(agentTaskLogMapper.selectById(15L)).thenReturn(log(15L, status = 3, sessionId = "sess-15"))
        whenever(agentTaskLogMapper.markStopping(15L, "Stopping...")).thenReturn(1)
        // agent-service was restarted, or the session mapping moved on: nothing is advancing this row
        whenever(routerClient.sendCommand("sess-15", CommandType.INTERRUPT)).thenReturn(false)

        assertTrue(service.stopTask(15L))

        val written = argumentCaptor<AgentTaskLog>()
        verify(agentTaskLogMapper).finalizeStopped(written.capture())
        assertEquals(5, written.firstValue.status)
        assertEquals("No live execution to interrupt", written.firstValue.errorInfo)
    }

    @Test
    fun `a stop that did reach a live execution leaves the row to that node`() {
        whenever(agentTaskLogMapper.selectById(16L)).thenReturn(log(16L, status = 3, sessionId = "sess-16"))
        whenever(agentTaskLogMapper.markStopping(16L, "Stopping...")).thenReturn(1)
        whenever(routerClient.sendCommand("sess-16", CommandType.INTERRUPT)).thenReturn(true)

        assertTrue(service.stopTask(16L))

        // The node owning the execution closes 4 -> 5; finalising here would pre-empt its real result.
        verify(agentTaskLogMapper, never()).finalizeStopped(any())
    }
```

- [ ] **步骤 6：运行，确认失败**

```bash
mvn -q -pl harnax-scheduler -Dtest=SchedulerStopStateMachineTest test
```
预期：编译失败——`sendCommand` 返回 `Unit`。

- [ ] **步骤 7：实现 sendCommand 与 stopTask**

`RouterClient.kt:127-143` 整体替换（`CommandResponse` 已在文件头 import）：

```kotlin
    /**
     * Send a command to a session through the router.
     * @return whether the command reached a live execution. `false` covers both "the agent-service
     * instance no longer holds this session" and "the call failed", and the scheduler treats both as
     * "nothing is running" — which is exactly what it needs to close a stopped execution out.
     */
    fun sendCommand(sessionId: String, command: CommandType): Boolean = try {
        val request = CommandAgentRequest(sessionId = sessionId, command = command)
        val url = "$routerUrl/api/router/agent/command"
        log.info("[Scheduler→Router] POST {} - command: {}", url, command)
        val response = restClient.post()
            .uri(url)
            .header("X-Api-Key", apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})

        if (response?.data?.success == true) {
            log.info("[Scheduler←Router] Command {} delivered for session={}", command, sessionId)
            true
        } else {
            log.warn(
                "[Scheduler←Router] Command {} NOT delivered for session={}: {}",
                command, sessionId, response?.data?.message ?: "no response body",
            )
            false
        }
    } catch (e: Exception) {
        log.warn("[Scheduler←Router] Failed to send command {} for session={}: {}", command, sessionId, e.message)
        false
    }
```

`SchedulerServiceImpl.kt:401-409` 替换：

```kotlin
        val sessionId = taskLog.sessionId
        if (sessionId.isNullOrBlank()) {
            log.warn("Task log {} has no session id; the execution will only stop on its own", logId)
        } else {
            // Router -> agent -> harnessAgent.interrupt(). The session is NOT cleared here: the node
            // running the task owns that cleanup, and tearing it down from here would destroy a live run.
            val delivered = routerClient.sendCommand(sessionId, CommandType.INTERRUPT)
            if (!delivered) {
                // Nothing on any instance is advancing this execution, so the stop is already final.
                // Leaving the row at 4 would let the reaper label a finished run "timeout".
                val now = LocalDateTime.now()
                taskLog.status = 5
                taskLog.errorInfo = "No live execution to interrupt"
                taskLog.endTime = now
                taskLog.durationMs = Duration.between(taskLog.startTime ?: taskLog.createTime ?: now, now).toMillis()
                agentTaskLogMapper.finalizeStopped(taskLog)
                log.info("Task log {} closed as stopped: no live execution remained to interrupt", logId)
            }
        }
        return true
```

`Duration` 与 `LocalDateTime` 已在该文件 import（:23-24），不需要新增。

- [ ] **步骤 8：运行，确认通过**

```bash
mvn -q -pl harnax-scheduler test
```
预期：全 PASS（含任务 1 的 controller 测试）。

- [ ] **步骤 9：Commit**

```bash
git add harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentRunner.kt \
        harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt \
        harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt \
        harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunnerTest.kt \
        harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/client/RouterClient.kt \
        harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt \
        harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerStopStateMachineTest.kt
git commit -m "fix(调度): 中断命令如实返回是否命中，未命中的停止立即定态"
```

---

## 任务 6：定态三分支——真实结果不被 timeout 覆盖丢弃

**依赖：** 任务 3 的 `reclaimExpired`。

**文件：**
- 修改：`harnax-scheduler/.../service/impl/SchedulerServiceImpl.kt:343-362`
- 测试：`SchedulerStopStateMachineTest`

- [ ] **步骤 1：写失败的测试**

在 `SchedulerStopStateMachineTest` 中，**替换**现有的 `a row already finalised elsewhere is not rewritten`（:150-161——它断言 0 行时一律走 `finalizeStopped`，正是要改的行为）：

```kotlin
    @Test
    fun `a row the reaper mis-timed-out gets its real result written back`() {
        whenever(routerClient.chat(any(), any())).thenReturn(ChatResponse(sessionId = "s", content = "real output"))
        whenever(agentTaskLogMapper.finishExecution(any())).thenReturn(0)
        // The row had been reaped as 2 while this execution was still running
        whenever(agentTaskLogMapper.selectById(any())).thenReturn(log(21L, status = 2, sessionId = "sess-21").apply { id = 21L })
        whenever(agentTaskLogMapper.reclaimExpired(any())).thenReturn(1)

        service.executeTaskOnce(task().apply { id = 21L }, LocalDateTime.now())

        val written = argumentCaptor<AgentTaskLog>()
        verify(agentTaskLogMapper).reclaimExpired(written.capture())
        assertEquals(1, written.firstValue.status)
        assertEquals("real output", written.firstValue.response)
        verify(agentTaskLogMapper, never()).finalizeStopped(any())
    }

    @Test
    fun `a row already terminal for another reason is left alone`() {
        whenever(routerClient.chat(any(), any())).thenReturn(ChatResponse(sessionId = "s", content = "late"))
        whenever(agentTaskLogMapper.finishExecution(any())).thenReturn(0)
        whenever(agentTaskLogMapper.selectById(any())).thenReturn(log(22L, status = 1, sessionId = "sess-22"))

        service.executeTaskOnce(task().apply { id = 22L }, LocalDateTime.now())

        verify(agentTaskLogMapper, never()).finalizeStopped(any())
        verify(agentTaskLogMapper, never()).reclaimExpired(any())
    }
```

> 注：`executeTaskOnce` 里 `agentTaskLogMapper.insert(taskLog)` 是 mock，不会回填自增 id，所以 `taskLog.id` 保持 0；`selectById(any())` 的桩因此必需，且断言只验证**传给 reclaim 的对象**，不验证 id 相等。

- [ ] **步骤 2：运行，确认失败**

```bash
mvn -q -pl harnax-scheduler -Dtest=SchedulerStopStateMachineTest test
```
预期：新两条红（当前实现对 0 行一律调 `finalizeStopped`，且完全不看 `selectById`）。

- [ ] **步骤 3：实现分支**

`SchedulerServiceImpl.kt:343-362` 的 `try { if (finishExecution...) ... }` 整块替换：

```kotlin
            // Write the final status immediately so the frontend sees it without waiting for
            // clearSession. finishExecution only matches while the row is still running (3); zero
            // rows means someone else moved it, and *where* they moved it decides what is true now.
            try {
                if (agentTaskLogMapper.finishExecution(taskLog) > 0) {
                    log.info("Updated agent task log: id={}, status={}, durationMs={}", taskLog.id, taskLog.status, taskLog.durationMs)
                } else {
                    closeOutLateExecution(taskLog)
                }
            } catch (e: Exception) {
                // Left at 3 or 4 on purpose: expireStale reclaims it as a timeout rather than this
                // node guessing at a status it could not persist.
                log.error("Failed to update task log: id={}, error={}", taskLog.id, e.message, e)
            }
```

在同文件的 `stopTask` 之前加私有方法：

```kotlin
    /**
     * The row left status 3 while this execution was still in flight. Re-read it instead of assuming
     * a user stop: a row the reaper had judged `timeout` is a run that finished successfully but
     * would otherwise be remembered as a failure, and that mistake is ours to correct because we hold
     * the real result.
     */
    private fun closeOutLateExecution(taskLog: AgentTaskLog) {
        when (agentTaskLogMapper.selectById(taskLog.id)?.status) {
            4 -> {
                taskLog.status = 5 // stopped by user (final)
                taskLog.errorInfo = "Task stopped by user"
                if (agentTaskLogMapper.finalizeStopped(taskLog) > 0) {
                    log.info("Task was stopped during execution: id={}", taskLog.id)
                } else {
                    log.warn("Execution log {} changed again mid-stop; result not written", taskLog.id)
                }
            }

            2 -> {
                if (agentTaskLogMapper.reclaimExpired(taskLog) > 0) {
                    log.info(
                        "Execution log {} had been auto-expired; wrote the real result (status={}) over it",
                        taskLog.id, taskLog.status,
                    )
                } else {
                    log.warn("Execution log {} moved again before it could be reclaimed", taskLog.id)
                }
            }

            else -> log.warn(
                "Execution log {} was already terminal elsewhere; its real result (status={}) was not written",
                taskLog.id, taskLog.status,
            )
        }
    }
```

- [ ] **步骤 4：运行，确认通过**

```bash
mvn -q -pl harnax-scheduler test
```
预期：全 PASS。原 `a stopped execution reports stopped instead of its own result` 用例（`selectById` 未打桩 → 返回 null）会落进 `else` 分支而变红。**正确处理**：给该用例补一行桩，让它保持被测语义：

```kotlin
        whenever(agentTaskLogMapper.selectById(any())).thenReturn(log(1L, status = 4, sessionId = "sess-1"))
```

- [ ] **步骤 5：Commit**

```bash
git add harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt \
        harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerStopStateMachineTest.kt
git commit -m "fix(调度): 区分定态失败的三种原因，被误判超时的真实结果写回"
```

---

## 任务 7：超时基准统一读配置

**背景：** 执行侧读超时是 `scheduler.timeout-seconds`（`application.yml:103`，env `SCHEDULER_TIMEOUT`），而 `expireStale` 的兜底是 `SchedulerServiceImpl` 里的常量 300。两者今天数值巧合相同，改一处即静默错位：把执行超时配到 900s 时，行会在 450s（×1.5 后是 675s）被回收。

**文件：**
- 修改：`SchedulerServiceImpl.kt:29-38`（构造参数）、`:428-446`（companion 常量与 `expireStaleExecutions`）
- 测试：`SchedulerStopStateMachineTest.kt:67-76`、`SchedulerStartupLoadTest.kt`（构造调用）

- [ ] **步骤 1：写失败的测试**

`SchedulerStopStateMachineTest` 新增（放在 stop 用例之后）：

```kotlin
    @Test
    fun `the expiry baseline follows the configured execution timeout, not a constant`() {
        whenever(agentTaskLogMapper.selectRunningByTaskId(1L)).thenReturn(listOf(log(31L, status = 3, sessionId = "sess-31")))
        whenever(agentTaskLogMapper.expireStale(any())).thenReturn(0)

        // 本用例的 service 用 900s 构造（见 setUpWithTimeout）
        serviceWith(900).triggerManually(1L)

        val captor = argumentCaptor<Int>()
        verify(agentTaskLogMapper).expireStale(captor.capture())
        assertEquals(900, captor.firstValue)
    }
```

并在同一个类里加一个构造助手（放在 `log(...)` 助手之前）：

```kotlin
    private fun serviceWith(timeoutSeconds: Int) = SchedulerServiceImpl(
        schedulerFactory,
        agentTaskMapper,
        agentTaskLogMapper,
        routerClient,
        executionGuard,
        SchedulerStatus(schedulerEnabled = true),
        SchedulerMetrics(SimpleMeterRegistry(), SchedulerStatus(schedulerEnabled = true)),
        schedulerEnabled = true,
        executionTimeoutSeconds = timeoutSeconds,
    )
```

- [ ] **步骤 2：运行，确认失败**

```bash
mvn -q -pl harnax-scheduler -Dtest=SchedulerStopStateMachineTest test
```
预期：编译失败——`executionTimeoutSeconds` 参数不存在。

- [ ] **步骤 3：实现**

`SchedulerServiceImpl.kt` 构造函数（:29-38）在 `schedulerEnabled` 之前加一个参数：

```kotlin
    @Value("\${scheduler.timeout-seconds:300}") private val executionTimeoutSeconds: Int,
    @Value("\${scheduler.enabled:true}") private val schedulerEnabled: Boolean,
```

`expireStaleExecutions`（:428-438）改用注入值，并在日志里带上来源：

```kotlin
    /** Reclaim executions that outran their own timeout, judged per row in SQL. */
    private fun expireStaleExecutions() {
        try {
            val expired = agentTaskLogMapper.expireStale(executionTimeoutSeconds)
            if (expired > 0) {
                log.info("Expired {} stale running task log(s) (baseline {}s)", expired, executionTimeoutSeconds)
            }
        } catch (e: Exception) {
            log.warn("Failed to expire stale running task logs: {}", e.message)
        }
    }
```

删掉 companion 里的 `DEFAULT_TIMEOUT_SECONDS`（:441-442 那两行，含注释）。

**RouterClient 不动**：它已经读同一个键。两者现在共享一个配置项是刻意的——一次执行的读超时与"多久算僵尸"必须同源。

- [ ] **步骤 4：修所有构造调用**

```bash
grep -rn "SchedulerServiceImpl(" harnax-scheduler/src --include=*.kt
```
给每处调用补 `executionTimeoutSeconds = 300`（当前只有两个测试文件构造它：`SchedulerStopStateMachineTest:67-76`、`SchedulerStartupLoadTest`）。

- [ ] **步骤 5：运行，确认通过**

```bash
mvn -q -pl harnax-scheduler test
```
预期：全 PASS。

- [ ] **步骤 6：Commit**

```bash
git add harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt \
        harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/service/impl/
git commit -m "refactor(调度): 僵尸判定的超时基准改读 scheduler.timeout-seconds"
```

---

## 任务 8：AgentTaskJob 在 Quartz 线程内同步执行

**为什么是本轮最结构性的改动：** 现状起 daemon 线程后立刻返回，Quartz 于是认为 job 瞬间完成、`QRTZ_FIRED_TRIGGERS` 不留行。三个后果同时成立——故障接管接不到正在跑的执行、优雅停机会掐死正在跑的线程、`@DisallowConcurrentExecution` 无对象可互斥。**S2 换 JDBC 集群存储之前这一步必须完成**，否则集群只是名义上的。

`triggerManually` 的裸线程**本任务不动**（那是 S4 的 one-shot 合并），所以本任务之后"cron 路径同步、手动路径仍异步"是预期中间态，不要去顺手统一。

**文件：**
- 创建：`harnax-scheduler/.../job/AbstractAgentTaskJob.kt`
- 重写：`harnax-scheduler/.../job/AgentTaskJob.kt`
- 创建：`harnax-scheduler/.../job/AgentTaskNonConcurrentJob.kt`
- 修改：`SchedulerServiceImpl.kt:131-164`（`scheduleTask` 选 job 类）
- 测试：创建 `AgentTaskJobExecutionTest.kt`、`SchedulerScheduleTaskTest.kt`

- [ ] **步骤 1：写失败的测试**

创建 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/job/AgentTaskJobExecutionTest.kt`：

```kotlin
package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.SchedulerService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.quartz.JobExecutionContext
import org.quartz.JobDetail
import org.quartz.JobDataMap
import org.quartz.Scheduler
import java.time.LocalDateTime
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The job must finish the execution before it returns: that is what puts a row in
 * QRTZ_FIRED_TRIGGERS, and a row there is the only way the cluster can know an execution is live —
 * which is what fail-over, graceful shutdown and @DisallowConcurrentExecution all read.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskJobExecutionTest {

    @Mock
    private lateinit var context: JobExecutionContext

    @Mock
    private lateinit var scheduler: Scheduler

    @Mock
    private lateinit var jobDetail: JobDetail

    @Mock
    private lateinit var schedulerService: SchedulerService

    @Mock
    private lateinit var executionGuard: AgentTaskExecutionGuard

    private fun setUpJob() {
        val task = AgentTask().apply {
            id = 3L
            name = "Nightly"
            agentId = 9L
            prompt = "go"
            concurrent = 0
        }
        whenever(context.jobDetail).thenReturn(jobDetail)
        whenever(jobDetail.jobDataMap).thenReturn(JobDataMap().apply { put("agentTask", task) })
        whenever(context.scheduler).thenReturn(scheduler)
        whenever(scheduler.context).thenReturn(org.quartz.SchedulerContext().apply {
            put("schedulerService", schedulerService)
            put("executionGuard", executionGuard)
        })
        whenever(context.scheduledFireTime).thenReturn(Date.from(LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()).toInstant()))
        whenever(executionGuard.tryAcquireLock(any(), any())).thenReturn(true)
    }

    @Test
    fun `execute runs the task to completion on the calling thread`() {
        setUpJob()
        val finished = AtomicBoolean(false)
        whenever(schedulerService.executeTaskOnce(any(), any())).thenAnswer {
            Thread.sleep(50)
            finished.set(true)
            Unit
        }

        AgentTaskJob().execute(context)

        assertTrue(finished.get(), "execute() returned before the task had run — Quartz would consider the job finished")
        verify(schedulerService).executeTaskOnce(any(), any())
    }

    @Test
    fun `a lock already held by another node skips the execution`() {
        setUpJob()
        whenever(executionGuard.tryAcquireLock(any(), any())).thenReturn(false)

        AgentTaskJob().execute(context)

        verify(schedulerService, org.mockito.kotlin.never()).executeTaskOnce(any(), any())
    }
}
```

创建 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerScheduleTaskTest.kt`：

```kotlin
package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.AgentTaskJob
import com.agnetix.harnax.scheduler.job.AgentTaskNonConcurrentJob
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.quartz.JobDetail
import org.quartz.Scheduler
import org.quartz.Trigger
import org.quartz.impl.matchers.GroupMatcher
import org.springframework.scheduling.quartz.SchedulerFactoryBean

/**
 * `concurrent` is only honoured if the registered job class carries @DisallowConcurrentExecution —
 * a misfire instruction alone cannot stop two runs of the same task overlapping.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerScheduleTaskTest {

    @Mock private lateinit var schedulerFactory: SchedulerFactoryBean
    @Mock private lateinit var quartz: Scheduler
    @Mock private lateinit var agentTaskMapper: AgentTaskMapper
    @Mock private lateinit var agentTaskLogMapper: AgentTaskLogMapper
    @Mock private lateinit var routerClient: RouterClient
    @Mock private lateinit var executionGuard: AgentTaskExecutionGuard

    private lateinit var service: SchedulerServiceImpl

    @BeforeEach
    fun setUp() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.checkExists(any<org.quartz.JobKey>())).thenReturn(false)
        val status = SchedulerStatus(schedulerEnabled = true)
        service = SchedulerServiceImpl(
            schedulerFactory,
            agentTaskMapper,
            agentTaskLogMapper,
            routerClient,
            executionGuard,
            status,
            SchedulerMetrics(SimpleMeterRegistry(), status),
            executionTimeoutSeconds = 300,
            schedulerEnabled = true,
        )
    }

    @Test
    fun `a non-concurrent task registers the disallowed-concurrency job class`() {
        service.scheduleTask(task(concurrent = 0))

        val captor = ArgumentCaptor.forClass(JobDetail::class.java)
        verify(quartz).scheduleJob(captor.capture(), any<Trigger>())
        assertEquals(AgentTaskNonConcurrentJob::class.java, captor.value.jobClass)
    }

    @Test
    fun `a concurrent task registers the plain job class`() {
        service.scheduleTask(task(concurrent = 1))

        val captor = ArgumentCaptor.forClass(JobDetail::class.java)
        verify(quartz).scheduleJob(captor.capture(), any<Trigger>())
        assertEquals(AgentTaskJob::class.java, captor.value.jobClass)
    }

    private fun task(concurrent: Int) = AgentTask().apply {
        id = 5L
        name = "Ticker"
        agentId = 1L
        prompt = "p"
        cronExpression = "0 0/5 * * * ?"
        this.concurrent = concurrent
    }
}
```

夹具只需 `quartz.checkExists(jobKey)` 返回 false；`scheduleTask` 不调用 `getJobKeys`，因此无需为组匹配器打桩。

- [ ] **步骤 2：运行，确认失败**

```bash
mvn -q -pl harnax-scheduler -Dtest=AgentTaskJobExecutionTest,SchedulerScheduleTaskTest test
```
预期：编译失败——`AgentTaskNonConcurrentJob` 不存在；`AgentTaskJob().execute` 因它是 `InterruptableJob` 且异步而不满足断言。

- [ ] **步骤 3：创建共享执行体**

创建 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AbstractAgentTaskJob.kt`：

```kotlin
package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.SchedulerService
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One fire of one agent task. Quartz instantiates jobs itself, so the collaborators come out of the
 * scheduler context rather than injection.
 *
 * Execution is deliberately synchronous: the job class is the only thing that tells Quartz "this
 * execution is live", and a thread handed off to a background executor is invisible to fail-over,
 * graceful shutdown and the non-concurrent guarantee.
 */
abstract class AbstractAgentTaskJob {

    protected val log = LoggerFactory.getLogger(javaClass)

    private fun schedulerService(context: JobExecutionContext): SchedulerService =
        context.scheduler.context["schedulerService"] as SchedulerService

    private fun executionGuard(context: JobExecutionContext): AgentTaskExecutionGuard =
        context.scheduler.context["executionGuard"] as AgentTaskExecutionGuard

    protected fun run(context: JobExecutionContext) {
        val task = context.jobDetail.jobDataMap["agentTask"] as? AgentTask
        if (task == null) {
            log.error("Agent task not found in job data map")
            return
        }

        // Scheduled fire time, not wall clock: every node must derive the same trigger identity from
        // one fire, or the guard's unique key stops being a key.
        val triggerTime = LocalDateTime.ofInstant(context.scheduledFireTime.toInstant(), ZoneId.systemDefault())
        val guard = executionGuard(context)

        if (!guard.tryAcquireLock(task.id, triggerTime)) {
            log.info("Task {} already being executed by another instance, skipping", task.id)
            return
        }

        log.info("Starting agent task execution: id={}, name={}, agentId={}", task.id, task.name, task.agentId)
        schedulerService(context).executeTaskOnce(task, triggerTime)
        log.info("Agent task execution finished: id={}, name={}", task.id, task.name)
    }
}
```

- [ ] **步骤 4：两个具体 job 类**

`AgentTaskJob.kt` **整体替换**（摘掉 `InterruptableJob` 与那段空 `interrupt()`）：

```kotlin
package com.agnetix.harnax.scheduler.job

import org.quartz.Job
import org.quartz.JobExecutionContext

/**
 * Job for a task whose `concurrent` allows overlapping executions. Stopping a run is not this class's
 * business: the INTERRUPT command goes to the router (see SchedulerService.stopTask), which is why it
 * is a plain Job and not an InterruptableJob.
 */
class AgentTaskJob : AbstractAgentTaskJob(), Job {
    override fun execute(context: JobExecutionContext) = run(context)
}
```

创建 `AgentTaskNonConcurrentJob.kt`：

```kotlin
package com.agnetix.harnax.scheduler.job

import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext

/**
 * Job for a task with `concurrent = 0`: Quartz will not fire it again while a previous fire is still
 * running. The annotation has to sit on the registered class — it is not @Inherited, so putting it on
 * the base class would silently do nothing.
 */
@DisallowConcurrentExecution
class AgentTaskNonConcurrentJob : AbstractAgentTaskJob(), Job {
    override fun execute(context: JobExecutionContext) = run(context)
}
```

- [ ] **步骤 5：注册时选类**

`SchedulerServiceImpl.kt:131-141`：

```kotlin
    override fun scheduleTask(task: AgentTask) {
        val jobKey = JobKey("AgentTask_${task.id}", "AgentTaskGroup")
        val triggerKey = TriggerKey("AgentTask_${task.id}_trigger", "AgentTaskGroup")

        val jobClass = if (task.concurrent == 0) AgentTaskNonConcurrentJob::class.java else AgentTaskJob::class.java
        val jobDataMap = JobDataMap()
        jobDataMap.put("agentTask", task)
        val jobDetail = JobBuilder.newJob(jobClass)
            .withIdentity(jobKey)
            .usingJobData(jobDataMap)
            .storeDurably()
            .build()
```

文件头 import 补 `com.agnetix.harnax.scheduler.job.AgentTaskNonConcurrentJob`。

- [ ] **步骤 6：运行，确认通过**

```bash
mvn -q -pl harnax-scheduler test
```
预期：全 PASS。

- [ ] **步骤 7：确认没有残留引用**

```bash
grep -rn "AgentTaskJob\b\|InterruptableJob" harnax-scheduler/src --include=*.kt
```
预期：只剩 `AbstractAgentTaskJob`/`AgentTaskJob`/`AgentTaskNonConcurrentJob` 的正常引用与 `runTaskOnce`、`scheduleTask` 里的 `JobBuilder.newJob(...)`。**不应再出现 `InterruptableJob`**；若 `runTaskOnce`（:254）还写着 `JobBuilder.newJob(AgentTaskJob::class.java)`，把它也换成同一个按 `concurrent` 选类的表达式（抽一个 `private fun jobClassFor(task: AgentTask): Class<out Job>` 供两处调用，避免复制）。

- [ ] **步骤 8：Commit**

```bash
git add harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/ \
        harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt \
        harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/job/ \
        harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerScheduleTaskTest.kt
git commit -m "fix(调度): job 在 Quartz 线程内同步执行，concurrent=0 走互斥 job 类"
```

---

## 任务 9：housekeeping job（guard 保留期清理 + 泄漏锁清理）

**文件：**
- 修改：`harnax-scheduler/.../service/AgentTaskExecutionGuard.kt`（注入超时基准、加 `cleanupLeakedLocks`）
- 创建：`harnax-scheduler/.../job/SchedulerHousekeepingJob.kt`
- 修改：`SchedulerServiceImpl.kt:56-75`（注册）
- 测试：`SchedulerHousekeepingJobTest.kt`（新建）

- [ ] **步骤 1：写失败的测试**

创建 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/job/SchedulerHousekeepingJobTest.kt`：

```kotlin
package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.quartz.JobExecutionContext
import org.quartz.Scheduler
import org.quartz.SchedulerContext

/** Housekeeping is the only thing that bounds agent_task_execution; it had no caller at all before. */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerHousekeepingJobTest {

    @Mock
    private lateinit var context: JobExecutionContext

    @Mock
    private lateinit var scheduler: Scheduler

    @Mock
    private lateinit var guard: AgentTaskExecutionGuard

    @BeforeEach
    fun setUp() {
        whenever(context.scheduler).thenReturn(scheduler)
        whenever(scheduler.context).thenReturn(SchedulerContext().apply { put("executionGuard", guard) })
    }

    @Test
    fun `housekeeping sweeps both the retention rows and the leaked locks`() {
        SchedulerHousekeepingJob().execute(context)

        verify(guard).cleanupOldExecutions(7)
        verify(guard).cleanupLeakedLocks()
    }
}
```

- [ ] **步骤 2：运行，确认失败**

```bash
mvn -q -pl harnax-scheduler -Dtest=SchedulerHousekeepingJobTest test
```
预期：编译失败——`SchedulerHousekeepingJob` 与 `cleanupLeakedLocks` 不存在。

- [ ] **步骤 3：guard 侧的两个清理入口**

`AgentTaskExecutionGuard.kt` 构造函数加超时基准（与 scheduler 主服务同一个配置键）：

```kotlin
class AgentTaskExecutionGuard(
    private val executionMapper: AgentTaskExecutionMapper,
    @Value("\${scheduler.instance-id:#{null}}") private val configuredInstanceId: String?,
    @Value("\${scheduler.timeout-seconds:300}") private val executionTimeoutSeconds: Int,
) {
```

在 `cleanupOldExecutions` 之后加：

```kotlin
    /**
     * Release locks whose holder is gone. A row still at status 0 after twice the execution timeout
     * cannot have a live owner — the execution would have been reaped by then — and leaving it in
     * place blocks that (task_id, trigger_time) from ever being delivered again.
     */
    fun cleanupLeakedLocks(): Int = try {
        executionMapper.deleteStaleRunning(LocalDateTime.now().minusSeconds((executionTimeoutSeconds * 2L)))
    } catch (e: Exception) {
        log.warn("Failed to sweep leaked execution locks: {}", e.message)
        0
    }
```

- [ ] **步骤 4：job 与注册**

创建 `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/SchedulerHousekeepingJob.kt`：

```kotlin
package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import org.quartz.Job
import org.quartz.JobExecutionContext

/**
 * Periodic sweep of the guard table. Runs on every node while the job store is in memory and becomes
 * cluster-singleton the moment the JDBC store lands, which is why it is a Quartz job and not a
 * Spring @Scheduled method: the operations are idempotent, but the churn is not free.
 */
class SchedulerHousekeepingJob : Job {

    override fun execute(context: JobExecutionContext) {
        val guard = context.scheduler.context["executionGuard"] as? AgentTaskExecutionGuard ?: return
        guard.cleanupOldExecutions(RETENTION_DAYS)
        guard.cleanupLeakedLocks()
    }

    companion object {
        const val GROUP = "SchedulerSystemGroup"
        const val JOB_NAME = "AgentTaskExecutionHousekeeping"
        private const val RETENTION_DAYS = 7
    }
}
```

`SchedulerServiceImpl.kt` 的 `onApplicationReady`（:69-75）改为：

```kotlin
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (!schedulerEnabled) {
            return
        }
        registerHousekeepingJob()
        loadExecutor.execute { loadTasksWithRetry() }
    }
```

并在 `init()` 之后加私有方法：

```kotlin
    /**
     * The guard table has no other writer that ever removes a row, so without this registration it
     * only grows. A repeating trigger, not a cron: the sweep has no relationship to any user's schedule.
     */
    private fun registerHousekeepingJob() {
        try {
            val jobKey = JobKey(SchedulerHousekeepingJob.JOB_NAME, SchedulerHousekeepingJob.GROUP)
            if (scheduler.checkExists(jobKey)) {
                return
            }
            val jobDetail = JobBuilder.newJob(SchedulerHousekeepingJob::class.java)
                .withIdentity(jobKey)
                .storeDurably()
                .build()
            val trigger = TriggerBuilder.newTrigger()
                .withIdentity(TriggerKey(SchedulerHousekeepingJob.JOB_NAME, SchedulerHousekeepingJob.GROUP))
                .forJob(jobKey)
                .startNow()
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInMinutes(5).repeatForever())
                .build()
            scheduler.scheduleJob(jobDetail, trigger)
            log.info("Registered scheduler housekeeping job (every 5 minutes)")
        } catch (e: Exception) {
            log.warn("Housekeeping job could not be registered: {}", e.message)
        }
    }
```

文件头补 import：`com.agnetix.harnax.scheduler.job.SchedulerHousekeepingJob`。`org.quartz.*` 已整包 import（:16），`SimpleScheduleBuilder` 在其中。

- [ ] **步骤 5：运行，确认通过**

```bash
mvn -q -pl harnax-scheduler test
```
预期：全 PASS。若 `SchedulerStartupLoadTest` 因为 `onApplicationReady` 多了 `registerHousekeepingJob()` 而红（mock 的 `scheduler.checkExists` 返回 null），在它的 setUp 里补 `whenever(quartz.checkExists(any<JobKey>())).thenReturn(false)`，属测试夹具跟进，不是回归。

- [ ] **步骤 6：Commit**

```bash
git add harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/AgentTaskExecutionGuard.kt \
        harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/SchedulerHousekeepingJob.kt \
        harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt \
        harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/job/
git commit -m "feat(调度): 新增 housekeeping job，抢锁表不再只进不出"
```

---

## 任务 10：删除本域死代码

**背景：** 三处确认无用的东西会在 S3 搬迁时被当"在用的代码"一起搬过去，所以本轮清掉。受工作区在途改动约束（见开头"开始前的工作区注意"）：若 `InternalApiController.kt` 仍是未提交状态，**跳过步骤 2 与步骤 3**，其余照做。

**文件：**
- 修改：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/AgentTaskLogService.kt`（删 `save`）
- 修改：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentTaskLogServiceImpl.kt:19-22`（删实现）
- 修改：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt:35`（假注释）
- 修改：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt`（legacy `/agent-tasks/{taskId}/spec` 端点，约 :658-680）

- [ ] **步骤 1：先确认这三处真的无人调用**

```bash
cd /Users/heqingsong/code/my_project/harnax
grep -rn "agentTaskLogService.save\|AgentTaskLogService" --include=*.kt harnax-admin/src
grep -rn "agent-tasks/{taskId}/spec\|getAgentTaskSpec\|TaskAgentSpecResponse" --include=*.kt --include=*.ts --include=*.tsx --include=*.go harnax-admin/src/main harnax-webui/src harnax-cli harnax-agent 2>/dev/null
```
预期：第一条只在 `AgentTaskLogService.kt`/`AgentTaskLogServiceImpl.kt` 自身与 `AgentTaskController` 的 `page`/`logs` 调用处出现（**没有** `.save(` 的调用点）；第二条命中仅限 `InternalApiController` 自身、其测试、以及 `TaskAgentSpecResponse` 的定义文件（`harnax-entity/.../dto/TaskAgentSpecResponse.kt`）。

**任一 grep 出现了别处的调用点，就停下并在执行报告里写明——不要为了完成计划而删掉在用代码。**

- [ ] **步骤 2：删 admin 的 legacy spec 端点**

`InternalApiController.kt`：删掉这一段（从注释分节标题到函数结束）：

```kotlin
    // ========================================
    // Agent Task Spec (legacy, kept for backward compat)
    // ========================================

    @GetMapping("/agent-tasks/{taskId}/spec")
    fun getAgentTaskSpec(@PathVariable taskId: Long): ResultVo<TaskAgentSpecResponse> = try {
        ...整段到该函数的最后一个右花括号...
    }
```

同时删掉文件头不再被使用的两个 import（`grep -c "TaskAgentSpecResponse" 该文件` 结果为 1 时才可删）：

```kotlin
import com.agnetix.harnax.entity.dto.TaskAgentSpecResponse
```

- [ ] **步骤 3：删对应测试用例**

`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/controller/InternalApiControllerTest.kt` 里删掉针对 `getAgentTaskSpec` 的用例（`grep -n "getAgentTaskSpec\|agent-tasks/" 该文件` 定位）。若 `InternalApiIT.kt` 也有命中，一并删。

- [ ] **步骤 4：删 AgentTaskLogService.save**

`AgentTaskLogService.kt` 删掉：

```kotlin
    fun save(log: AgentTaskLog): Boolean
```

`AgentTaskLogServiceImpl.kt` 删掉对应 `override fun save(...)` 整个方法。若该接口因此只剩两个方法，`AgentTaskLog` 的 import 仍被 `page` 的返回类型使用——**先 `grep -n "AgentTaskLog" 两个文件` 再决定删哪些 import**。

- [ ] **步骤 5：修正 SchedulerClientImpl 的注释**

`SchedulerClientImpl.kt:35` 那句 `// The scheduler runs UnifiedAuthFilter: every call needs a fresh typ=internal bearer.` 与事实相反（scheduler 侧 `harnax.auth.enabled: false`，`UnifiedAuthFilter` 根本没装配）。替换为：

```kotlin
            // The scheduler does NOT run our auth filter (harnax.auth.enabled=false there); this
            // bearer is forward-compat only. S3 adds an internal-token interceptor that validates it.
```

- [ ] **步骤 6：编译与测试**

```bash
mvn -q -pl harnax-admin -am test
```
预期：BUILD SUCCESS。若报 `unresolved reference: TaskAgentSpecResponse`，说明还有调用点没清干净，回到步骤 1 的 grep 复核。

- [ ] **步骤 7：Commit**

```bash
git add harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/AgentTaskLogService.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentTaskLogServiceImpl.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt \
        harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/controller/InternalApiControllerTest.kt
git commit -m "chore(admin): 删除定时任务域的 legacy spec 端点与无人调用的日志 save"
```

> `TaskAgentSpecResponse` 这个 DTO 本身**本轮不删**：它在 `harnax-entity`，删除会牵动别的模块，且 S3 搬迁时本来就要重做一轮引用面核查。

---

## 任务 11：全量回归与状态同步

- [ ] **步骤 1：三模块全量测试**

```bash
cd /Users/heqingsong/code/my_project/harnax
mvn -q -pl harnax-protocol,harnax-entity,harnax-scheduler,harnax-admin,harnax-agent/harnax-agent-service -am test
```
预期：BUILD SUCCESS，且**没有 skip**。若 admin 红在 `InternalApiControllerTest`，那是工作区里那条在途工作（见"开始前的工作区注意"），与本计划无关——用 `git stash list`/`git diff` 确认后再判断，不要改别人的测试。

- [ ] **步骤 2：CLI 回归**

```bash
cd harnax-cli && go test ./... && go build ./... && cd ..
```
预期：PASS 与无输出。

- [ ] **步骤 3：手工链路验证（必须做，测试覆盖不到真实中断语义）**

```bash
cd docker-new && docker-compose up -d --build admin router agent-service scheduler
```
1. 建一个 cron 为每分钟、prompt 会让 agent 跑数十秒的任务并启用。
2. `curl -s localhost:28080/api/admin/agent-tasks/<id>/trigger -X POST -H "Authorization: Bearer <jwt>"` → 立即执行。
3. 在执行进行中 `docker-compose restart agent-service`，再点停止。
4. 预期：日志行**秒级**变成"已停止"（5），而不是停留在"停止中"再变"超时"。查 `mysql> select id,status,error_info from harnax_admin.agent_task_log order by id desc limit 3;`
5. 若拿不到 JWT 或环境不可用，**明确说明未做人工验证**，不要把这条划成通过。

- [ ] **步骤 4：更新进度真相源**

`prod_doc/agent-task-scheduler.zh-CN.md` 第 11 节：把 0.3、4.1(部分)、4.3、4.4(仅 cron 路径)、4.5、5.2 之外的相关行改为已完成并标注日期；同时注明"one-shot 合并与 JobDataMap 只存 taskId 属 S4/S2，本轮未做"。

规格 [2026-09-11-scheduler-cluster-design.md](../specs/2026-09-11-scheduler-cluster-design.md) 第 7 节的 S0/S1 行加"✅ 已完成（YYYY-MM-DD）"，并在验收标准第 3、4 条后面补一句实测结论。

- [ ] **步骤 5：Commit**

```bash
git add prod_doc/agent-task-scheduler.zh-CN.md docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md
git commit -m "docs(调度): 记录 S0+S1 完成情况与人工验证结论"
```

---

## 遗留：本计划刻意不做的事

- `triggerManually` 的裸线程与 `runTaskOnce` 两套逻辑合并 → S4（4.2 one-shot）。做完 S2 之前它不构成正确性问题，只构成"手动执行不受 Quartz 掌控"。
- JobDataMap 只存 `taskId` → S2（`useProperties: true` 换 JDBC store 时才强制要求；本计划里 `AbstractAgentTaskJob` 仍读实体）。
- reconcile 取代"全删重建" → S2，必须与 JDBC store 同期。
- 独立 `harnax_scheduler` 库、`agent_task` 表迁移 → S2。
- 域搬迁（admin 薄代理、实体迁移、sessionId 编 agentId、C5 owner 端点）→ S3。
