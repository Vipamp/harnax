# 定时任务域搬迁与 sessionId 编 agentId（发布 2）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** 把定时任务域（`agent_task` / `agent_task_log` / `agent_task_execution`）从 `harnax-admin` 整体搬进 `harnax-scheduler`，scheduler 改用自己的库 `harnax_scheduler`；admin 只剩"用户 JWT 鉴权 + 带身份转发"，对这四张表发零条 SQL；定时任务 sessionId 改为四段以编入 agentId（契约 C1、C5、C4）。

**Architecture：** 绞杀式搬迁，不做"一次编译断裂"的大提交。先在 `com.agnetix.harnax.scheduler.{entity,mapper,dto,service}` 建**自己的**一套（与 `harnax-entity` 的旧套并存，包名不同故不冲突），scheduler 的 `@MapperScan` 改指自己；再在 scheduler 上开出 11 个 CRUD/日志端点 + 内部鉴权拦截器；然后把 admin 换成转发；最后删掉 `harnax-entity` 里的旧套。数据源切换与迁数据放在**最后一个**代码提交之后的切口文档里，因为它是唯一需要停服的动作。

**Tech Stack：** Kotlin 2.2.21、Spring Boot 4.0.1、MyBatis XML、MySQL 8、Flyway（scheduler 自有历史表）、PageHelper 2.1.0、JJWT（经 `harnax-auth`）、Testcontainers 1.21.4（复用发布 1 的 failsafe 机制）。

## 与 spec 的偏差（先读，实现时不要再论证）

- spec §5 C1 的"F3 第三刀 C"——**在 admin 比对 sessionId 的 agentId 与 `agent_task.agent_id`**——域搬迁后 admin 读不到那张表，**不可执行**。替代物见 Task 3/4（单源生成 + 消费者严格格式校验 + C5 带 agentId 供冷路径核对），并把残留面登记为 F14（Task 10）。
- spec §7 的 S3"必须单 PR，因为 admin 编译断裂"由本方案的包名分离消解：每个提交都能编译、能跑测试。
- spec §10 的"迁 `agent_task`、不迁历史日志"照做，但**新库必须建 `agent_task_log`**：`AgentTaskMapper.xml` 的 `selectTaskList` 自联这张表取 `lastRunStatus/lastRunTime`，表不存在则列表接口 500。因此第二条用户可见后果是"列表页的最近一次运行两列也会空"（发布公告必须写，spec 只写了第一条）。
- 验收线 `grep -rn "agentTaskMapper|agentTaskLogMapper|AgentTaskExecution" harnax-admin/src/main` 的基线实测是 **19 处**（spec 写 24 已过时），且编译边界比这条 grep 更宽：**7 个** admin 文件 import `com.agnetix.harnax.entity.AgentTask*`（含两个 DTO、两个 service 接口），必须一起改。

## Global Constraints

- 注释语言：scheduler / admin / router 模块**英文**（含测试的失败消息文本）；`docs/`、`prod_doc/` 中文。
- **对客户端零变化**：`/api/admin/agent-tasks/**` 的路径、方法、`ResultVo` 外壳、`Page` 的 7 个键（`pageNum/pageSize/total/records/pages/hasPrevious/hasNext`）、`records[*]` 的 18 个字段名全部不变；`40901/40902/40903` 语义不变。搬迁后的校验**错误文案**必须与 admin 今天的一字不差（webui 直接展示 `.message`）。
- 属主/可见性规则逐字保留，不得在搬迁中"顺手收紧或放宽"：读用 `is_public = 1 OR creator = ?`，写用 `creator = ?`（`selectOwnedById` 是停止面的写门禁）。启停面的既有缺口登记为 F12，本发布不修。
- 不新增依赖版本；`pagehelper-spring-boot-starter:2.1.0`（带对 `mybatis-spring-boot-starter` 的 exclusion，照 `harnax-admin/pom.xml:52-62`）是 scheduler 唯一新增依赖。
- 旧库 `harnax_admin` 的四张表**本发布不 DROP**（DROP 脚本另存，运维签认后才执行）。
- 本机有全局 `mvn`（`/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn`），需 `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`；改完必 `mvn -q spotless:apply`；mvn 输出重定向到文件再 `echo $?`（管道进 tail 会吞退出码）。
- **本机无 Docker**：Testcontainers IT 只写不跑，且必须继续被 surefire 排除；提交信息里不得出现"IT 全绿"。
- 单模块编译若报兄弟模块符号缺失：`mvn -o install -pl harnax-common,harnax-auth,harnax-entity,harnax-protocol -DskipTests` 本地装一次，不为此改 pom。
- `git commit` 有 post-commit 钩子，可能跑到两分钟；shell 超时后先 `git log --oneline -1` 再重试，绝不重复提交。

---

### Task 1: 新库业务表 DDL + scheduler 的基础设施副本

**Files:**
- Create: `harnax-scheduler/src/main/resources/db/migration/V2__agent_task_domain.sql`
- Modify: `harnax-scheduler/pom.xml`
- Create: `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/Page.kt`
- Create: `.../scheduler/support/SchedulerBizException.kt`、`.../scheduler/config/SchedulerWebConfig.kt`

**Interfaces:**
- Produces: `Page<T>`（7 键，与 admin 的 `harnax-admin/.../dto/Page.kt` 同形同义）、`SchedulerBizException(val code: Int, override val message: String)`、`@RestControllerAdvice` 把它转成 `ResultVo.error(code, message)` 且对校验失败回 `"Validation failed: …"`（照 admin 现有 handler 的形状）。

- [ ] **Step 1: 写 V2 DDL**

以 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:480-536` 的三段建表为底本（**逐列照抄，不改类型、不改索引、不改列序**——Task 9 的 `INSERT ... SELECT *` 依赖列序一致），做且只做四处改动：

1. `agent_task_log.session_id` 由 `VARCHAR(64)` 改为 `VARCHAR(128)`，注释改为 `'task-{taskId}-{agentId}-{uuid} (C1); the legacy three-segment form may still appear in migrated rows'`；
2. 把发布 1 已落在 admin 的 `V27__add_agent_task_execution_sweep_indexes.sql` 的两个索引直接内联进 `agent_task_execution` 建表（`KEY idx_status_create_time (status, create_time)`、`KEY idx_create_time (create_time)`），不再另起 V3；
3. 删掉 `DROP`/`CREATE DATABASE` 之类语句（本仓无，但确认底本没有）；
4. 文件头加英文注释，写明：这三张表的所有权从本发布起属于 scheduler；`harnax_admin` 里的同名四张表在切口后仍是 admin 侧的只读残留，由运维签认后单独 DROP；以及**历史日志不迁**（D8），故新库这三张表在切口后从空开始。

校验：`grep -c "CREATE TABLE" V2__agent_task_domain.sql` → 3；`grep -c "DROP" ...` → 0；`grep -c "session_id\` *VARCHAR(128)" ...` → 1。

- [ ] **Step 2: 加 pagehelper 与三件基础设施**

`harnax-scheduler/pom.xml` 加（版本与 exclusion 照 admin）：

```xml
        <!-- PageHelper: the task list endpoints page the same way admin's did -->
        <dependency>
            <groupId>com.github.pagehelper</groupId>
            <artifactId>pagehelper-spring-boot-starter</artifactId>
            <version>2.1.0</version>
            <exclusions>
                <exclusion>
                    <groupId>org.mybatis.spring.boot</groupId>
                    <artifactId>mybatis-spring-boot-starter</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
```

`dto/Page.kt` 与 `support/SchedulerBizException.kt`：从 admin 的 `dto/Page.kt`（`com.github.pagehelper.PageInfo` + `fromPageInfo` + `mapRecords`）**逐字搬**，仅改包名与注释语言为英文，**7 个键名一个不许动**；异常与 advice 的形状照 `harnax-admin/.../config/GlobalExceptionHandler.kt`（先读它再写）。

- [ ] **Step 3: 写契约钉住测试**

新建 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/dto/PageContractTest.kt`，断言序列化后的键集合恰为 7 个且名字一致（用 `tools.jackson` 的 `ObjectMapper`，注解 `@JsonProperty` 如 admin 有用则一并搬），注释写明"这是三客户端共同依赖的外壳形状，改名即断契约"。

- [ ] **Step 4: 编译 + 跑测试 + Commit**

```bash
$MVN -q spotless:apply -pl harnax-scheduler
$MVN -o -pl harnax-scheduler test -Dsurefire.failIfNoSpecifiedTests=false > t1.log 2>&1; echo EXIT=$?
git add harnax-scheduler
git commit -m "feat(调度): 新库定时任务域 DDL 与分页/异常基础设施"
```

---

### Task 2: 三实体 + 三 mapper + 三 XML 迁入 scheduler 包

**Files:**
- Create（从 `harnax-entity` 搬）: `.../scheduler/entity/{AgentTask,AgentTaskLog,AgentTaskExecution}.kt`、`.../scheduler/mapper/{AgentTask,AgentTaskLog,AgentTaskExecution}Mapper.kt`、`harnax-scheduler/src/main/resources/mapper/AgentTask{,Log,Execution}Mapper.xml`
- Modify: `.../scheduler/SchedulerApplication.kt:10`、`harnax-scheduler/src/main/resources/application.yml`（mybatis 段）
- 不动：`harnax-entity` 的原件（Task 8 删）

**Interfaces:**
- Produces: `com.agnetix.harnax.scheduler.mapper.{AgentTaskMapper,AgentTaskLogMapper,AgentTaskExecutionMapper}` —— **方法名、参数名、`@Param` 与 `harnax-entity` 原件逐字节相同**（Task 6 的 service 代码要按名字直接用）。

- [ ] **Step 1: 搬迁清单（穷举，不得遗漏）**

对 9 个文件各做一次，改动只允许以下几类：

1. 实体：`package com.agnetix.harnax.entity` → `com.agnetix.harnax.scheduler.entity`；其余逐字节相同（含 `lastRunStatus`/`lastRunTime` 两个 JOIN-only 字段与它们的 `@Schema`）。
2. mapper：`package com.agnetix.harnax.mapper` → `com.agnetix.harnax.scheduler.mapper`；`import com.agnetix.harnax.entity.*` → `…scheduler.entity.*`；**类注释里的状态转移集合（`3->{0,1,4,2}`、`4->{5,2}`、`2->{0,1,5}`）逐字保留**。
3. XML：`namespace="com.agnetix.harnax.mapper.X"` → `…scheduler.mapper.X`；`resultMap type="com.agnetix.harnax.entity.X"` → `…scheduler.entity.X`；`parameterType="com.agnetix.harnax.entity.AgentTaskLog"`（`AgentTaskLogMapper.xml` 的 `reclaimExpired` 上）同步改。SQL 正文、JOIN、MySQL 专有语法一律不动。
4. `application.yml`：`mybatis.type-aliases-package: com.agnetix.harnax.entity` → `com.agnetix.harnax.scheduler.entity`；`mapper-locations` 保持 `classpath*:mapper/*.xml`。
5. `SchedulerApplication.kt`：`@MapperScan("com.agnetix.harnax.mapper")` → `@MapperScan("com.agnetix.harnax.scheduler.mapper")`。

**已知中间态（必须写进 `application.yml` 注释）**：`classpath*:mapper/*.xml` 此刻仍会同时加载 `harnax-entity` jar 里的旧 XML（namespace 指向已不再扫描的接口）。它不会造成 bean 冲突（MyBatis 的 resultMap 按 namespace 隔离），但会让两套语句都进 Configuration，Task 8 删除原件后消失。

- [ ] **Step 2: 写证明搬迁完整的测试**

新建 `.../scheduler/mapper/SchedulerMapperWiringTest.kt`（纯反射/解析，不起容器）：

```kotlin
    @Test
    fun `every scheduler mapper statement resolves to a scheduler-side interface`() {
        // A namespace that still points at harnax-entity's interface would bind a mapper nobody scans,
        // and the failure would surface at the first SQL call rather than at boot.
        val namespaces = listOf("AgentTaskMapper", "AgentTaskLogMapper", "AgentTaskExecutionMapper")
            .map { readNamespace("mapper/$it.xml") }
        namespaces.forEach {
            assertTrue(it.startsWith("com.agnetix.harnax.scheduler.mapper."), "namespace $it did not move")
            Class.forName(it)  // the interface must exist where the XML says it does
        }
    }
```

- [ ] **Step 3: 编译 + 跑测试 + Commit**

```bash
$MVN -q spotless:apply -pl harnax-scheduler
$MVN -o -pl harnax-scheduler test -Dsurefire.failIfNoSpecifiedTests=false > t2.log 2>&1; echo EXIT=$?
git add harnax-scheduler
git commit -m "refactor(调度): 三实体与三 mapper 迁入 scheduler 自有包"
```

---

### Task 3: C1 — sessionId 编入 agentId，四段格式两侧同批改

**Files:**
- Create: `.../scheduler/support/TaskSessionId.kt`
- Modify: `.../scheduler/service/impl/SchedulerServiceImpl.kt`（`insertRunningLog`）
- Modify: `harnax-admin/.../controller/InternalApiController.kt`（`resolveFromTask`，`:285-305`）
- Test: 两侧各新建/改一处

**Interfaces:**
- Produces: `object TaskSessionId { fun of(taskId: Long, agentId: Long): String; fun parse(sessionId: String): Parsed?; data class Parsed(val taskId: Long, val agentId: Long, val random: String) }`，格式 `task-{taskId}-{agentId}-{uuid}`。
- Consumes: 生成侧的 `task.id` 与 `task.agentId` 出自**同一次** `selectAnyById`（这就是替代 spec 交叉校验的那一半——两个段同源，不可能互相矛盾）。

- [ ] **Step 1: 写两侧都失败的红测试**

`harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/support/TaskSessionIdTest.kt`：

```kotlin
    @Test
    fun `a session id carries both ids and survives a round trip`() {
        val id = TaskSessionId.of(12L, 34L)
        assertEquals(4, id.split('-').size)
        assertTrue(id.startsWith("task-12-34-"))
        val parsed = TaskSessionId.parse(id)
        assertEquals(12L, parsed!!.taskId)
        assertEquals(34L, parsed.agentId)
        assertTrue(parsed.random.isNotBlank())
    }

    @Test
    fun `the legacy three-segment form parses but names no agent`() {
        // Migration window: rows written before the cut still carry `task-{id}-{uuid}`. A consumer must be
        // able to read the task id out of one without pretending it knows the agent.
        val parsed = TaskSessionId.parse("task-7-abcdef")
        assertEquals(7L, parsed!!.taskId)
        assertEquals(0L, parsed.agentId)
    }

    @Test
    fun `anything that is not a task session is refused rather than guessed at`() {
        assertNull(TaskSessionId.parse("web-1"))
        assertNull(TaskSessionId.parse("task-abc-1-2"))
        assertNull(TaskSessionId.parse("task-0-1-2"))
        assertNull(TaskSessionId.parse("task--1-2-3"))
    }
```

`harnax-admin/src/test/.../InternalApiTaskSpecTest.kt`（新建，standalone）：断言四段 id 能装配出 spec 且用的是 **sessionId 里的 agentId**（stub agent 域），断言三段 id **被拒**并给出明确文案（`Invalid task sessionId: expected task-{taskId}-{agentId}-{uuid}`），断言非数字段、`agentId <= 0` 同样被拒。

- [ ] **Step 2: 实现生成侧**

`insertRunningLog` 内 `"task-${task.id}-${UUID.randomUUID()}"` → `TaskSessionId.of(task.id, task.agentId)`，并在方法注释里写明：这个 id 是 scheduler 与 admin 之间的隐式契约，`of` 是唯一的生成处。

- [ ] **Step 3: 实现消费侧（同批，格式与校验不能分开发布）**

`resolveFromTask` 改为：先 `TaskSessionId.parse`（admin 侧建一份同名私有解析或直接把 `TaskSessionId` 放进 `harnax-common` 的 `session` 包——**决定：放 `harnax-common`**，因为两侧都要用且 admin 不能依赖 scheduler），拿不到即抛上面那条文案；再按 `parsed.agentId` 装配 spec，**不再查 `agent_task`**（这一步同时消掉 admin 对这张表的两处引用）。

- [ ] **Step 4: 回归三处 `startsWith("task-")` 依赖**

`harnax-agent-service/.../DefaultAgentRunner.kt:782,850` 与 `harnax-harness-core/.../OutputFileStore.kt:128-132` 只用前缀判定，不受段数影响；为它们各加一条"四段 id 仍被识别为任务会话"的断言（各在其现有测试文件里），把"C1 对它们无感"从口头结论变成测试。

- [ ] **Step 5: 跑测试 + Commit**

```bash
$MVN -q spotless:apply
$MVN -o -pl harnax-scheduler test -Dsurefire.failIfNoSpecifiedTests=false > t3a.log 2>&1; echo SCHED=$?
$MVN -o -pl harnax-admin test -Dtest='!com.agnetix.harnax.admin.it.**' -Dsurefire.failIfNoSpecifiedTests=false > t3b.log 2>&1; echo ADMIN=$?
$MVN -o -pl harnax-agent/harnax-agent-service test -Dtest='DefaultAgentRunnerTest' -Dsurefire.failIfNoSpecifiedTests=false > t3c.log 2>&1; echo AGENT=$?
git add -A
git commit -m "feat(契约): 定时任务 sessionId 编入 agentId（C1），两侧同批严格校验"
```

---

### Task 4: C5 — scheduler 的 owner 端点，admin 的 MCP 属主解析改走 HTTP

**Files:**
- Create: `.../scheduler/controller/AgentTaskOwnerController.kt`（或先并入 Task 6 的 controller，二选一并说明）
- Modify: `harnax-admin/.../util/McpSessionOwnerResolver.kt`（`:57-64` 的 `fromTask`）
- Create: `harnax-admin/.../client/SchedulerOwnerClient.kt`
- Test: 两侧

**Interfaces:**
- Produces: `GET /api/scheduler/agent-tasks/{id}/owner` → `ResultVo<AgentTaskOwner?>`，`data class AgentTaskOwner(val creator: String, val tenantId: Long, val agentId: Long)`（Jackson Kotlin 注解按 `[[kotlin-data-class-jackson]]` 的既有约定）。
- Consumes: `AgentTaskMapper.selectAnyById(id)`（scheduler 侧）。

- [ ] **Step 1: 红测试**

scheduler 侧：给定 `creator="bob", tenant_id=5, agent_id=9` 的行，端点返回三值；行不存在返回 `data:null` 且 code 200。
admin 侧：`McpSessionOwnerResolverTest`（若无则新建）——`task-7-9-x` 的属主解析改为断言"打了一次 owner 端点并用它的返回"，且**端点抛异常时 resolver 返回 null 并 WARN**（不是静默 debug），因为它的故障形式是"OAuth 工具静默不可用"。

- [ ] **Step 2: 实现**

`SchedulerOwnerClient`：`RestClient` + connect 5s / read 10s，认证复用 admin 现有的 `InternalTokenProvider`（`authHeaders()`）。**失败语义要写清**：owner 查询失败 ⇒ MCP OAuth token 解析为 null ⇒ 该次执行的 OAuth 工具不可用，但任务本身仍完成。这是冷路径，不得为它给 spec 热路径加一跳。

- [ ] **Step 3: 跑测试 + Commit**

```bash
$MVN -q spotless:apply -pl harnax-scheduler,harnax-admin
$MVN -o -pl harnax-admin test -Dtest='!com.agnetix.harnax.admin.it.**' -Dsurefire.failIfNoSpecifiedTests=false > t4.log 2>&1; echo EXIT=$?
git add -A
git commit -m "feat(契约): scheduler 提供 task 属主查询（C5），admin 不再查 agent_task"
```

---

### Task 5: C4 — scheduler 写面的内部鉴权与身份注入

**Files:**
- Create: `.../scheduler/support/InternalCallerInterceptor.kt`、`.../scheduler/support/CallerContext.kt`
- Modify: `.../scheduler/config/SchedulerWebConfig.kt`、`harnax-scheduler/src/main/resources/application.yml`
- Test: `.../scheduler/support/InternalCallerInterceptorTest.kt`

**Interfaces:**
- Produces: `CallerContext.username: String?`（ThreadLocal，`postHandle`/`afterCompletion` 清）；拦截器只放行 `InternalTokenProvider.verifyToken` 通过且 `callerType == INTERNAL_SERVICE` 的请求；缺/错凭证 → `401`。
- Consumes: admin 的 `SchedulerClientImpl`（已发 `Authorization: Bearer <internal JWT>` + `X-Caller-Id`）。

- [ ] **Step 1: 决定并声明 bean 来源**

scheduler 里 `harnax.auth.enabled` 保持 `false`（完整自鉴权属 F1）。因此**不能**依赖 `harnax-auth` 的自动装配拿 provider——先读 `AuthAutoConfiguration` 的条件，若它不产出 bean，就在 `SchedulerWebConfig` 里自己 `@Bean InternalTokenProvider(@Value serviceId, @Value sharedSecret, @Value ttl)`。**这一步必须先验证再写代码**：把结论写进报告（哪种情况成立、依据的 `file:line`）。

- [ ] **Step 2: 红测试**

拦截器测试四条：无 `Authorization` → 拒；用户 JWT（带 `userId`、无 `typ=internal`）→ 拒；内部 JWT → 放行且 `CallerContext.username` 取自 `X-Forwarded-User`；**请求自带 `X-Forwarded-Tenant` 必须被忽略**（浏览器可伪造，spec §2.3 明写）。

- [ ] **Step 3: 实现 + 覆盖范围**

拦截 `/api/scheduler/**` 的**写面**（`/tasks/**`、`/reload`、以及 Task 6 新增的 `/agent-tasks/**`），放行 `/actuator/health/**`。`/tasks/logs/{id}/stop` 也在写面内——它由 admin 转发，必须带身份。

- [ ] **Step 4: admin 侧同时发头**

`SchedulerClientImpl.postToInstance` 加 `X-Forwarded-User`（admin 自己从 JWT 解出的 username）与 `X-Tenant-Id`（`TenantContext`），并加测试断言两头存在、`X-Forwarded-Tenant` 从不出现。

- [ ] **Step 5: 部署后果必须写在提交信息正文与 Task 10 文档**

拦截器一上，**旧版 admin 转发的调用会全部 401**：admin 与 scheduler 必须同窗口升级，且 `HARNAX_AUTH_SECRET` 两边同值（compose 同源，手工部署路径是唯一的坑）。

- [ ] **Step 6: 跑测试 + Commit**

```bash
$MVN -q spotless:apply -pl harnax-scheduler,harnax-admin
$MVN -o -pl harnax-scheduler test -Dsurefire.failIfNoSpecifiedTests=false > t5a.log 2>&1; echo SCHED=$?
$MVN -o -pl harnax-admin test -Dtest='!com.agnetix.harnax.admin.it.**' -Dsurefire.failIfNoSpecifiedTests=false > t5b.log 2>&1; echo ADMIN=$?
git add -A
git commit -m "feat(调度): scheduler 写面只接受内部 JWT，身份经转发头注入（C4）"
```

---

### Task 6: scheduler 侧的 11 个 CRUD/日志端点与属主规则

**Files:**
- Create: `.../scheduler/service/AgentTaskCrudService.kt` + `impl`、`.../scheduler/service/AgentTaskLogQueryService.kt` + `impl`、`.../scheduler/controller/AgentTaskController.kt`、`.../scheduler/dto/{AgentTaskCreateRequest,AgentTaskUpdateRequest,AgentTaskResponse,AgentTaskLogResponse}.kt`
- Test: `.../scheduler/service/AgentTaskCrudServiceImplTest.kt`、`.../scheduler/it/AgentTaskOwnerScopeIT.kt`（IT-3）

**Interfaces:**
- Consumes: Task 2 的三个 mapper、Task 1 的 `Page`/`SchedulerBizException`、Task 5 的 `CallerContext`。
- Produces: `/api/scheduler/agent-tasks/**` 的 11 个端点，响应 `ResultVo<Page<AgentTaskResponse>>` 等，**字段与 admin 今天的一致**。

- [ ] **Step 1: 搬迁规则（先读原文再搬，逐条对照）**

源：`harnax-admin/.../service/impl/AgentTaskServiceImpl.kt`、`AgentTaskLogServiceImpl.kt`。必须逐字保留的行为（读代码确认后再动）：
- `create`：`taskStatus = 0`（默认暂停）、`active = 1`、`creator = <当前用户>`、`tenantId = <租户 ?: 1>`、`agentName` 取自 agent（**这是 admin 域的数据，scheduler 只能反向拿：见 Step 2**）、名称重复 → 与今天一字不差的错误文案；
- `update`：校验 cron 字段数 `6..7`（admin 今天就是这么宽的，不要顺手改严）、`creator` 写门禁、以及它重置 `taskStatus` 的那一行；
- `delete`：软删（`active = 0`）+ 提交后触发对账；
- 日志分页：`selectLogList` 的 7 个参数与 `currentUsername` 门禁、`selectOwnedById` 作为停止前的写门禁；
- `afterCommit` 语义：回滚不外发对账、非 200 抛 `40902`（scheduler 本地跑对账时，把 `schedulerClient.reloadTasks()` 换成 `reconciler.reconcile()` + `report.converged` 判断，语义等价）。
- **启停/触发面无写侧属主门禁**（`updateStatus` 不带 creator、`/trigger` 无门禁）→ 原样搬，登记 F12（Task 10），本发布不修。

- [ ] **Step 2: `agentName` 的跨域取数**

`agent_task.agent_name` 是 admin 域的快照字段。搬迁后 scheduler 有两个选择：让 admin 在转发 create/update 时**把已解析的 agentName 一起送过来**（在请求 DTO 里加一个 `agentName`，admin 填充，scheduler 校验非空）；或 scheduler 新增一次对 admin 的读。**决定：前者**——不新增热路径跳转，符合 D4 的初衷。写进两个 DTO 的注释，并让 scheduler 在缺失时回 400（而非静默存空串）。

- [ ] **Step 3: IT-3 越权真库测试**

`AgentTaskOwnerScopeIT`：非属主读/改/删拿不到且数据未变；`is_public=1` 可读；`selectOwnedById` 挡住他人的停止。断言要打在**数据未变**上，不只是响应码。

- [ ] **Step 4: 跑测试 + Commit**

```bash
$MVN -q spotless:apply -pl harnax-scheduler
$MVN -o -pl harnax-scheduler test -Dsurefire.failIfNoSpecifiedTests=false > t6.log 2>&1; echo EXIT=$?
git add harnax-scheduler
git commit -m "feat(调度): scheduler 承接定时任务 CRUD 与日志查询端点"
```

---

### Task 7: admin 瘦身为鉴权 + 转发

**Files:**
- Modify: `harnax-admin/.../controller/AgentTaskController.kt`、`.../service/SchedulerClient.kt` + `impl/SchedulerClientImpl.kt`
- Delete: `.../service/AgentTaskService.kt`、`AgentTaskLogService.kt` 及其两个 `impl`、`.../dto/{AgentTaskResponse,AgentTaskLogResponse,AgentTaskCreateRequest,AgentTaskUpdateRequest}.kt`
- Test: 删/改上述对应的 admin 测试文件（处置表见 Step 3）

**Interfaces:**
- Produces: admin 的 11 个端点行为不变，内部实现为"取 `CallerContext`/`JwtUtil` 的用户与租户 → 转发 → 把 scheduler 的 `ResultVo` 原样回传"。
- Consumes: Task 6 的 11 个 scheduler 端点、Task 5 的转发头约定。

- [ ] **Step 1: 先写转发契约测试（红）**

`harnax-admin/src/test/.../it/AgentTaskForwardingContractTest.kt`（MockWebServer 假装 scheduler）：逐个端点断言出站 method+path 正确、`X-Forwarded-User`/`X-Tenant-Id` 存在、响应体逐字段回传（含 scheduler 返回 `data:null`、`records` 里 `lastRunStatus` 键存在、非 200 时 message 原样透传）。

- [ ] **Step 2: 实现转发**

`SchedulerClient` 扩出泛化方法（避免 11 个各写一遍）：

```kotlin
    /**
     * Forward a task-domain request to the scheduler and hand the caller back exactly what it answered.
     *
     * The body is carried as a JsonNode on purpose: admin no longer owns these types, and a mirror DTO
     * here would be a second definition of a contract the scheduler owns — the drift would be invisible
     * until a client read a null.
     */
    fun forward(method: HttpMethod, path: String, query: Map<String, String?>, body: Any?): ResultVo<JsonNode>
```

`AgentTaskController` 每个端点变成一次 `forward` 调用（`/agents` 例外——它是 admin 自己的域，保持不动）。错误映射：scheduler 的非 200 一律转成 `BizException(code, message)`，让 webui 今天依赖的 catch 分支照旧命中。

- [ ] **Step 3: 测试处置表（禁止靠删用例变绿）**

| 原文件 | 处置 |
|---|---|
| `AgentTaskServiceImplTest.kt` | 属主/校验/文案断言 → 迁为 scheduler 的 `AgentTaskCrudServiceImplTest`；`afterCommit`/40902 四条 → 迁为 scheduler 侧同名四条（stub 换 `reconciler.reconcile()`） |
| `AgentTaskLogServiceImplTest.kt` | 参数透传四条 → scheduler；"读契约不带 tenant 形参"→ mapper 接口反射断言 |
| `AgentTaskControllerTest.kt` | → `AgentTaskForwardingContractTest`（参数表驱动，7 个 query 参数原样出站） |
| `it/AgentTaskCrudIT.kt` | 保留，被测对象改为转发契约（MockWebServer 答真实 scheduler JSON 样本） |
| `it/AgentTaskSchedulerIT.kt` | 保留 8 条；`toggle unknown task fails without calling scheduler` **改方向**为"答 not found 来自 scheduler"（门禁随数据走了），理由写进用例注释 |
| `SchedulerClientImplTest.kt` | 加 7 条转发 + 1 条身份头断言 |

- [ ] **Step 4: 主验收线**

```bash
$MVN -q spotless:apply -pl harnax-admin
$MVN -o -pl harnax-admin test -Dtest='!com.agnetix.harnax.admin.it.**' -Dsurefire.failIfNoSpecifiedTests=false > t7.log 2>&1; echo EXIT=$?
grep -rn "agentTaskMapper\|agentTaskLogMapper\|AgentTaskExecution" harnax-admin/src/main --include="*.kt" || echo ZERO
grep -rn "import com.agnetix.harnax.entity.AgentTask" harnax-admin/src/main --include="*.kt" || echo ZERO_IMPORTS
```
期望：EXIT=0 且两个 ZERO（基线 19 处 / 7 个 import 文件）。

- [ ] **Step 5: Commit（一次）**

```bash
git add -A harnax-admin
git commit -m "refactor(调度): admin 的定时任务域退化为鉴权与带身份转发"
```

---

### Task 8: `harnax-entity` 清零，mapper 测试随迁

**Files:**
- Delete: `harnax-entity/src/main/.../entity/{AgentTask,AgentTaskLog,AgentTaskExecution}.kt`、`.../mapper/{AgentTask,AgentTaskLog,AgentTaskExecution}Mapper.kt`、`.../resources/mapper/AgentTask{,Log,Execution}Mapper.xml`
- Delete: `harnax-entity/src/test/.../mapper/AgentTask{,Log,Execution}MapperTest.kt`、`.../AgentTaskLogStopGateSqlTest.kt`
- Create: `harnax-scheduler/src/test/.../it/AgentTaskMapperSemanticsIT.kt`（三合一）、`.../scheduler/AgentTaskLogStopGateSqlTest.kt`
- Modify: `harnax-entity/src/test/resources/schema-test.sql`

- [ ] **Step 1**：SQL 门禁测试搬到 scheduler，改 4 处（包名、`NAMESPACE` 常量、`import`、失败消息改英文），并加 `assertFalse(methods.any { it.name == "selectByTaskId" })`。
- [ ] **Step 2**：三个 mapper 测试逐条搬进 `AgentTaskMapperSemanticsIT`（`extends BaseSchedulerIT()`，Flyway 建表，**不再靠 `schema-it.sql` 建**）；原先依赖 `schema-test.sql` 种子的用例改为**用例内自插自删**——种子不能留在 `schema-it.sql`，那会打破发布 1 对账用例的精确计数。类注释写明"本文件在本机从未执行"。
- [ ] **Step 3**：删 `schema-test.sql` 的三段 DDL（`:461-548`）与两处 `-- Test data for agent_task*` 的 6 条 INSERT；`grep -rn "agent_task" harnax-entity/src/test || echo CLEAN`。
- [ ] **Step 4**：`git rm` 上面 13 个文件。三个仍 `@MapperScan("com.agnetix.harnax.mapper")` 的服务（admin、agent-service、channel-service）不动——少三个接口不影响装配。
- [ ] **Step 5**：全量回归 + 两个 ZERO + Commit `refactor(调度): 三表定义从 harnax-entity 退场，mapper 测试改挂 scheduler 真库`。

---

### Task 9: 数据源切换、一次性迁移与切口顺序

**Files:**
- Modify: `harnax-scheduler/src/main/resources/application.yml`（datasource 默认库）、`docker-new/docker-compose.yml`
- Create: `docker-new/sql/release-2-agent-task-migration.sql`、`docker-new/sql/release-2-drop-legacy-tables.sql`
- Modify: `docs/deploy-harnax-scheduler.md`（新增「发布 2 切口」章）、`prod_doc/agent-task-scheduler.zh-CN.md`（发布公告）

- [ ] **Step 1**：datasource 默认改 `harnax_scheduler`；compose 侧用**独立的** `SCHEDULER_DB_URL`（不能复用四个服务共享的 `DB_URL`，否则一改变动带错三个服务）。
- [ ] **Step 2**：迁移脚本 = 幂等守卫（目标表已有行则跳过并打印原因）+ `INSERT INTO harnax_scheduler.agent_task SELECT * FROM harnax_admin.agent_task`（**连 `active=0` 一起搬**：可见性读 `active`，丢了软删行会让 `uk_name` 仍然占用却查不到占用者）+ 末尾一条自校验 SELECT（`copied_rows / live_rows_old / total_rows_old / max_id_new`）。不是 Flyway 迁移：跑两遍不无害，而数据副本不该进 schema 台账。
- [ ] **Step 3：切口顺序（文档一等公民，逐步可照敲）**
  1. 发布公告 + 冻结写入；**admin 与 scheduler 必须同时下线**（C1 双向不兼容：旧 scheduler 的三段 id 新 admin 会拒，新 scheduler 的四段 id 旧 admin 会解析错）。
  2. 排空在途：`SELECT COUNT(*) FROM harnax_admin.agent_task_log WHERE status IN (3,4)` 必须为 0。**理由必须写进文档**：跨切口活着的执行永远无法定态——它的日志行在旧库，切口后 scheduler 只在 `harnax_scheduler` 找它，`finishExecution`/`markStopping`/`expireStale` 全部 0 行，那一行停在 3 且再没有回收扫描看得见。
  3. 停两副本 + admin（窗口内堆积的 cron 按 misfire 处理，`concurrent=0` 是 `DoNothing` → **被跳过而不是延后跑**，公告要这么写）。
  4. 起新 scheduler（Flyway 建 V1+V2）→ 5. 跑迁移并核对四个数字 → 6. 触发一次对账，核 `scheduledTaskCount` == 新库里 `task_status=1 AND active=1` 的行数 → 7. 起新 admin + 三客户端主链路各一遍 → 8. 起第二副本，核 `QRTZ_SCHEDULER_STATE` 两个 `INSTANCE_NAME` 的 `LAST_CHECKIN_TIME` 在 15s 前进（**别数行数**，发布 1 已记：优雅停机的节点不删自己那行）。
- [ ] **Step 4：回滚**：`SCHEDULER_DB_URL` 指回 `harnax_admin` + `SCHEDULER_FLYWAY_ENABLED=false`（旧库 V2 未应用，开着会去改旧库列宽）+ 起旧版 admin。**分界线要写死**：切口后任何人改过任务定义，回滚就不再干净，得手工把 `harnax_scheduler.agent_task` 的新行搬回去；窗口内新产生的执行日志分属两库，无合并路径（D8 的代价）。
- [ ] **Step 5：发布公告四条**（历史空 + 最近运行列空、错过的 cron 被跳过、sessionId 形态变更影响外部存储方、admin HTTP 契约不变客户端无需升级）。
- [ ] **Step 6**：校验 + Commit `feat(部署): 调度数据源切 harnax_scheduler，含一次性迁移脚本与切口顺序`。

---

### Task 10: 文档、状态与三条新登记

- [ ] **Step 1**：spec §9 追加 **F12**（启停/触发面无写侧属主门禁：`updateStatus` 无 creator 条件、`/trigger` 无任何门禁）、**F13**（`uk_name` 与软删互斥：`deleteById` 只置 `active=0` 且 `name` 上有全局唯一键，故已删任务名永久不可复用，而 `selectByName` 带 `active=1` 会判"可用"，最终 INSERT 撞键以 500 收场）、**F14**（C1 之后 admin 无法做 spec 原定的 agentId 交叉校验；热路径上 spec 装配现在**信任**字符串里的 agentId，可达集合从"有任务的 agent"扩大到"任意 agent"，仍需内部调用方身份——不粉饰，F3-A 的归属扩展才是正解）。
- [ ] **Step 2**：spec §7 S3 行改状态，表下补 **修正 D**（域搬迁与数据源切换同切口；C1 双向不兼容 ⇒ 两服务同时下线，这是本改造唯一不能滚动做的部分）；§5 的 C1/C4/C5 行标完成；§11 第 6 条基线 24 → 19/4 文件并补"另 7 个 admin 文件 import 这三个类型"。
- [ ] **Step 3**：`docs/deploy-harnax-scheduler.md` 的「认证边界」（`HARNAX_AUTH_SECRET` 现在必须与 admin 同值）、「数据源」表（新库、`SCHEDULER_DB_URL`）、「与 MCP 用户身份的关系」（改走 C5 端点 + 三条运维后果）三节改到与代码一致；`docs/agent-task-design.md` 的 admin 拥有 Quartz 的段落纠正。
- [ ] **Step 4**：Commit `docs(调度): S3 完成状态、切口顺序与 F12-F14 登记`。

---

## 发布 2 的验收标准

1. `mvn -o clean test -Dtest='!com.agnetix.harnax.mapper.**,!com.agnetix.harnax.admin.it.**,!com.agnetix.harnax.channel.service.it.**' -Dsurefire.failIfNoSpecifiedTests=false > verify-r2.log 2>&1; echo EXIT=$?` → **0**（交付机自验）。
2. `grep -rn "agentTaskMapper\|agentTaskLogMapper\|AgentTaskExecution" harnax-admin/src/main` → **零命中**（spec §11.6）；`grep -rn "AgentTask\|agent_task" harnax-entity/src` → **零命中**。
3. 有 Docker 的机器：`mvn -o -pl harnax-scheduler verify -Pintegration-test` → 发布 1 的 IT-1/IT-2/IT-5 + 本发布新增 IT-3、`AgentTaskMapperSemanticsIT` 全绿。**本机从未执行，任何记录里不得出现"IT 全绿"。**
4. 切口后数据校验：`SELECT (SELECT COUNT(*) FROM harnax_scheduler.agent_task) = (SELECT COUNT(*) FROM harnax_admin.agent_task) AS copied_ok, (SELECT COUNT(*) FROM harnax_scheduler.agent_task WHERE task_status=1 AND active=1) AS scheduled_expected;` → `copied_ok=1` 且 `scheduled_expected` == `GET /api/scheduler/tasks/status` 的 `scheduledTaskCount`；`harnax_scheduler.agent_task_log` 行数为 0（**预期，不是事故**）。
5. 未签名的 `/api/scheduler/**` 一律 401；带 admin 一枚真实内部 JWT 的同一调用 200。
6. 三客户端契约：抓一次 `/api/admin/agent-tasks/page` 响应，`data` 仍是 7 键、`records[*]` 仍是 18 键且 `lastRunStatus` **键存在**（值可为 null）；webui 列表/创建/编辑/启停/删除/立即执行/日志轮询、CLI `task list|get|create|trigger|stop`、小程序任务页各跑一遍。
7. **C5 回归单列**（spec §11.7）：绑定 OAuth MCP 的 agent 被定时任务调用时仍解析出正确 `sys_user.id` 与 `tenantId`；再故意停掉 scheduler 跑一次，确认任务本身仍完成、只有该 OAuth 工具不可用、admin 日志是 WARN 而非静默 debug。
