# Harnax Scheduled Tasks and the Scheduler Service

> This is the complete design document for the "Agent Task" business domain: service boundaries, data model
> and state machine, Quartz clustering semantics, the task lifecycle (create → register → fire → execute →
> stop → reap), the cross-service authentication contract, the API surface, observability and deployment
> constraints. Every statement has been verified against the current code.

## 1. Conclusions first

1. **Scheduled tasks are a standalone service, not a feature module inside admin.** `harnax-scheduler`
   (Kotlin, port 8084) exclusively owns the Quartz engine plus the `agent_task` / `agent_task_log` /
   `agent_task_execution` tables: entities, mappers, XML, CRUD rules, scheduling, execution, reaping and
   reconciliation all live in this module (40 `.kt` files under
   `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/`), Flyway is self-managed, and the
   history table is `flyway_schema_history_scheduler`.
2. **The datasource is this service's own `harnax_scheduler` database**, named literally in the default URL
   of `application.yml`. The 11 `QRTZ_*` tables and the three business tables live there, created by this
   module's only migration script,
   `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql`, and that database has only this one
   migration tool. Admin's `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` neither creates
   these three tables nor drops them, so a freshly deployed `harnax_admin` holds none of this domain's
   same-named tables; an `harnax_admin` created before the split may still hold the three empty ones, and
   removing them is operator work (see item 10 of the deployment checklist).
3. **Quartz runs in JDBC cluster mode**: a shared JobStore plus `isClustered = true`, with the `QRTZ_*`
   tables as the single scheduling truth. One cron fire is delivered exactly once across the cluster and
   executed by exactly one node. The failover window is 22.5 to 52.5 seconds, derived from
   `JobStoreSupport.calcFailedIfAfter` and `clusterCheckinInterval = 15000` (the formula is expanded in the
   "Quartz cluster configuration" section).
4. **The business-layer lock is a backstop.** `agent_task_execution`'s
   `uk_task_trigger (task_id, trigger_time)` is still contended on every fire, but a single-delivery engine
   layer now sits behind it.
5. **All that remains of this domain in admin is "validate the user JWT and forward with identity".** The
   three clients (webui / CLI / mini-program) keep calling `/api/admin/agent-tasks/**`; the paths, methods,
   `ResultVo` envelope, the keys of `Page`, the field names and the business codes are passed through
   verbatim by `AgentTaskController` (admin), which returns the `JsonNode` unchanged. The scheduler's HTTP
   surface is not open to browsers.
6. **CRUD and its scheduling notification live in the same process.** Writing `agent_task` and running that
   round of reconcile both happen inside the scheduler; all `/reload` does now is "let an outside caller
   request one round of convergence". The semantics of reconcile are **run one reconciliation round**: the
   write to `agent_task` and the write to the store are not one transaction, so the notification can still
   be lost after commit, and a lost one is caught by `SchedulerReconcileJob`'s cluster-wide sweep every 60
   seconds.
7. **Reconciliation converges by diff, not by delete-and-rebuild.** Under shared storage, "delete every job
   in `AgentTaskGroup` and rebuild them" would blow away every task in the cluster whenever any node
   restarts. `TaskScheduleReconciler` computes the difference between desired and actual state, and
   `SchedulerReconcileJob` runs a safety round every 60 seconds.
8. **Manual execution is one Quartz fire.** Both `/trigger` and `/run-once` only deposit a one-shot job into
   the shared store, so they run on the same pool of workers as cron and
   `wait-for-jobs-to-complete-on-shutdown` holds for both paths at once.
9. **The inbound gate covers every endpoint under `/api/scheduler/**`, reads included.** Only an
   `typ=internal` HMAC JWT is accepted; the end-user identity arrives through the two forwarded headers
   `X-Forwarded-User` + `X-Tenant-Id`, and `InternalCallerInterceptor.preHandle`
   **verifies the signature before reading the headers**.
10. **`harnax.auth.enabled` stays `false` in this service** — that switch simultaneously turns on external
    API Key acceptance, rate limiting and the `@InternalOnly` model, which is a different design; this
    service installs only its own interceptor that accepts service tokens.

## 2. Service boundary and module layout

```
                    ┌──────────────────────────────────────────────┐
  browser / CLI /   │            nginx (the single entry point)     │
  mini-program      └───────┬──────────────────────────┬───────────┘
                            │ /api/admin/agent-tasks/** │ /api/router/**
                            ▼                           ▼
                    ┌───────────────┐           ┌──────────────┐
                    │  harnax-admin │           │     router   │
                    │ validates the  │           └──────┬───────┘
                    │ user JWT;      │                  │
                    │ injects ident. │                  │
                    └───────┬───────┘                  │
        internal JWT + the  │ HTTP (container network, not open to browsers)
        two forwarded       ▼                          │
        headers + X-Caller-Id                          │
        ╔═══════════════════════════════════════════════════════════╗
        ║  harnax-scheduler  (N stateless instances, scale freely)  ║
        ║  ┌────────────────┐ ┌──────────────┐ ┌──────────────────┐ ║
        ║  │ CRUD Service   │ │ Quartz engine │ │ AgentTaskJob     │ ║
        ║  │ + reconcile    │ │ JobStoreTX   │ │ (re-reads the row│ ║
        ║  └───────┬────────┘ │ isClustered  │ │  at fire time)   │ ║
        ╚══════════╪══════════╧══════╤═══════╧══════════╪═══════════╝
                   ▼                 ▼                  ▼
        ┌─────────────────────────────────────┐   POST /api/router/agent/chat
        │  harnax_scheduler db (Flyway-owned)  │   session task-{taskId}-{agentId}-{uuid}
        │  agent_task / agent_task_log /       │
        │  agent_task_execution / QRTZ_* ×11   │
        └─────────────────────────────────────┘
```

### 2.1 Division of responsibility

| Service | Owns | Does not own |
|---|---|---|
| **harnax-admin** | Validating the user JWT, resolving `tenantId` / `username`, forwarding to the scheduler with an internal token + `X-Forwarded-User` + `X-Tenant-Id`, `agent-spec` assembly (parsing `task-{taskId}-{agentId}-{uuid}` to obtain the agentId — pure string work, no query and no round trip), and on the cold path calling the owner endpoint to fetch the task's owner | Any SQL against this domain's four tables, Quartz, CRUD business rules, reload broadcast |
| **harnax-scheduler** | Task definition storage, CRUD validation and owner rules, Quartz cluster scheduling, execution and its state machine, execution logs, zombie reaping, reconciliation, guard cleanup, the inbound internal-JWT gate | Reading anyone else's database — this service has exactly one datasource, `harnax_scheduler` |

### 2.2 Package layout

| Package | Contents |
|---|---|
| `scheduler/controller` | `AgentTaskController` (CRUD + logs + stop), `SchedulerController` (scheduling actions and `/reload`), `AgentTaskOwnerController` (the owner read endpoint `GET /api/scheduler/agent-tasks/{id}/owner`) |
| `scheduler/service` (+ `impl`) | `AgentTaskCrudService`, `SchedulerService`, `AgentTaskLogQueryService`, `TaskScheduleReconciler`, `AgentTaskExecutionGuard` |
| `scheduler/job` | `AbstractAgentTaskJob` and its two subclasses, `TaskQuartzRegistrar`, `SchedulerReconcileJob`, `SchedulerHousekeepingJob` |
| `scheduler/entity` + `scheduler/mapper` + `resources/mapper/*.xml` | Three entities, three mappers, three XML files |
| `scheduler/support` | `InternalCallerInterceptor`, `CallerContext`, `SchedulerBizException` |
| `scheduler/health` | `SchedulerHealthIndicator`, `SchedulerStatus`, `QuartzJobInventory` |
| `scheduler/client` | `RouterClient`, `CommandDelivery` |
| `scheduler/dto` | `Page`, four task DTOs |
| `scheduler/config` | `SchedulerConfig` (bean wiring and the internal token provider), `SchedulerWebConfig` (interceptor registration + exception advice) |
| `common/session/TaskSessionId` in `harnax-common` | The grammar of the task session id, one copy shared by the minting side and the parsing side |

## 3. Data ownership

The `harnax_scheduler` database is owned exclusively by the scheduler's Flyway
(`harnax-scheduler/src/main/resources/db/migration/`); admin's Flyway does not touch it.

| Table | Nature | Written by | Read by |
|---|---|---|---|
| `agent_task` | Business truth (task definitions) | scheduler CRUD | scheduler (job registration, re-read at fire time) |
| `agent_task_log` | Execution history + runtime state machine | scheduler | scheduler (concurrency gating, reaping), admin via forwarding |
| `agent_task_execution` | Cluster lock records (an engine implementation detail) | scheduler | scheduler |
| `QRTZ_*` × 11 | Engine state | Quartz only | Quartz only |

The three business tables live in the same database as `QRTZ_*`, so the two statements that cross a
responsibility boundary (the list page self-joining `agent_task_log` for the most recent execution, and the
reap statement JOINing `agent_task` to obtain `timeout_seconds`) execute in one service against one
database and need no cross-service call.

### 3.1 Notable points of the business tables

`harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql` is the schema source of truth for the
three business tables, and the only definition of them in this repository — admin's baseline does not describe
these tables, so there is no second copy to keep comparable with. The three facts this file states about the
domain:

- `agent_task_log.session_id` is `VARCHAR(128)` (the four-segment task session id is longer);
- the two sweep indexes on `agent_task_execution`, `(status, create_time)` and `(create_time)`, are inlined
  into the `CREATE TABLE`;
- the file contains no removal statements; what happens to leftover same-named tables on the `harnax_admin`
  side is not its concern.

Constraints that determine read behaviour:

| Constraint | Behaviour |
|---|---|
| `UNIQUE KEY uk_name (name)` on `agent_task` | Does not include `active`. `deleteById` only sets `active = 0`, so the row keeps holding the name; the duplicate-name pre-check uses `selectByName`, which filters `active = 1` and cannot see the holder, so the INSERT ends up hitting the key |
| `agent_task.idx_tenant_id` | Present. `tenant_id` is written from `CallerContext.tenantId` (1 when absent); the read-side visibility predicate is `creator` plus `is_public`, not `tenant_id` |
| `agent_task_execution.uk_task_trigger (task_id, trigger_time)` | How the lock works: a successful insert wins the right to execute, a unique-key conflict gives up. `trigger_time` is `JobExecutionContext.scheduledFireTime`, not wall clock — the nodes must compute the same key for "the same fire" |
| `agent_task.cron_expression` | A 6-field Quartz-style expression, validated before the write by `AgentTaskCrudServiceImpl.isValidCron()` |
| `agent_task.task_status` | 0 paused / 1 running |
| `agent_task.concurrent` | 0 forbid overlap / 1 allow; decides both the job class and the misfire instruction (`TaskQuartzRegistrar.jobClassFor`) |
| `agent_task.timeout_seconds` | Drives two things: the read timeout ceiling of the router call, and the baseline of the reap decision (`expireStaleExecutions`) |

## 4. The execution state machine

`agent_task_log.status`: `3` running, `1` success, `0` failed, `4` stopping, `5` stopped, `2` timeout.

```
  3 running ──normal end: finishExecution──> 1 success
      │        └───exception──────────────> 0 failed
      │
      ├── stop requested on any node: markStopping  3 ──> 4 stopping (the front end gets feedback at once)
      │                                     │
      │        execution thread wraps up: finalizeStopped        4 ──> 5 stopped
      │        stopper receives "no in-flight execution" (Missed) 4 ──> 5 stopped
      │
      └── timed out without a finalizer: expireStale   3|4 ──> 2 timeout
                                              │
                        the real result arrives afterwards: reclaimExpired  2 ──> {0,1} (marker kept in error_info)
                        a stop was definitely read:         reclaimExpired  2 ──> 5
```

`3 → {0,1,4,2}`, `4 → {5,2}`, `2 → {0,1,5}` — the same source as the class comment on `AgentTaskLogMapper`.

All four write statements are **UPDATEs with a status condition**, and "zero rows affected" is how a node
learns another node already settled the row:

| Statement | WHERE condition | Written by |
|---|---|---|
| `finishExecution` | `id = #{id} AND status = 3` | The execution thread's normal wrap-up. The guard takes 3 but not 4: once a row has moved to 4 a user stopped it, and the execution thread must report through `finalizeStopped` rather than overwrite that stop with its own result |
| `finalizeStopped` | `id = #{id} AND status = 4` | The execution thread after it has read a 4 |
| `markStopping` | `id = #{id} AND status = 3` | The node accepting the stop |
| `reclaimExpired` | `id = #{id} AND status = 2` | The late result's overwrite. It can never undo a user stop (4/5) or a real failure already written (0/1); the `(completed after auto-expiry)` marker inside `error_info` distinguishes the two flavours of 0/1 |
| `expireStale` | `status IN (3, 4)` and `COALESCE(start_time, create_time)` older than `CEIL(timeout × 1.5)` seconds ago | The reap sweep (`expireStaleExecutions`) |

This CAS semantics requires the log table to be in the database the executing node can reach with direct
SQL — business tables, guard table and `QRTZ_*` in one database is exactly that arrangement.

**One irreversible edge**: `expireStale` also scans 4, and 4 is the only place the user's stop intent is
stored, so the statement replaces `error_info` with the expiry text. When the execution thread later re-reads
that row it is indistinguishable from a row that was never stopped (both sit at 2 with a different marker),
the thread writes back its real 0/1, and the stop disappears from the record. Closing it needs a separate
stop-flag column, not a cleverer UPDATE.

## 5. Quartz cluster configuration

`harnax-scheduler/src/main/resources/application.yml` is the source of truth; the shape is:

```yaml
spring:
  quartz:
    job-store-type: ${QUARTZ_JOB_STORE:jdbc}
    auto-startup: ${SCHEDULER_QUARTZ_AUTO_STARTUP:${scheduler.enabled:true}}
    jdbc:
      initialize-schema: never
    wait-for-jobs-to-complete-on-shutdown: ${QUARTZ_WAIT_FOR_JOBS:true}
    properties:
      org:
        quartz:
          scheduler: { instanceName: HarnaxScheduler, instanceId: AUTO }
          threadPool: { class: org.quartz.simpl.SimpleThreadPool, threadCount: ${QUARTZ_THREAD_COUNT:10}, threadPriority: 5 }
          jobStore:
            driverDelegateClass: org.quartz.impl.jdbcjobstore.StdJDBCDelegate
            tablePrefix: QRTZ_
            isClustered: "true"
            clusterCheckinInterval: 15000
            misfireThreshold: 60000
            acquireTriggersWithinLock: "true"
            useProperties: "true"
```

Item by item:

- **`org.quartz.jobStore.class` is deliberately not written.** Spring's `SchedulerFactoryBean` overwrites the
  store class with `LocalDataSourceJobStore` once it injects a DataSource, so the store uses the
  Spring-managed pool. Hand-writing `JobStoreTX` gets overwritten anyway and fails startup, because Quartz
  would not recognise the logical name `quartzDataSource`. The `storeType` detail on the health indicator
  exists precisely to publish the store class name actually in use so this can be checked.
- **No `@QuartzDataSource` is needed.** With `job-store-type: jdbc`, Boot wires the single DataSource
  automatically. This service does have exactly one datasource, which makes the Spring-managed pool and the
  cluster store the same object.
- **`initialize-schema: never`.** Boot's `always` runs its bundled script on every start and that script
  begins with `DROP TABLE IF EXISTS` — in a cluster that means every node restart deletes the scheduling
  state shared by the whole cluster. Boot's default for this key is `embedded`, which never creates tables
  under MySQL; pinning `never` turns "the schema belongs to Flyway alone" into a convention, and guards
  against someone changing it to `always`.
- **`instanceName` must be identical cluster-wide.** It becomes the `SCHED_NAME` column of `QRTZ_*`, and
  cluster members recognise each other through it.
- **`instanceId: AUTO` is safe under containerisation** (hostname + timestamp + thread). The precondition is
  that compose contains no `container_name: harnax-scheduler` — a fixed container name is unique, so Docker
  rejects `--scale` outright, and it can also duplicate hostnames and collide instanceIds. The comment in
  compose's scheduler section states that reason and also why you enter the container with
  `docker compose … exec scheduler` rather than `docker exec harnax-scheduler`.
- **`clusterCheckinInterval: 15000` and `misfireThreshold: 60000`**: the misfire decision must sit outside the
  takeover window.
- **`acquireTriggersWithinLock: true`**: in cluster mode no local lock protects the acquire step, so without
  this two nodes can each claim the same fire and one of them discovers it lost only after doing the work.
- **`useProperties: true`**: the JobDataMap is stored as text key/value pairs in `QRTZ_JOB_DETAILS`, so the
  engine tables hold no Java-serialized BLOB and an entity field change cannot make a registered task
  unreadable. Non-string values are then a hard error, which pairs with the rule that "the single registration
  entry point stores only the `taskId` string".
- **`threadCount` moves together with the pool**: `QUARTZ_THREAD_COUNT` (default 10) is a **per-node** gate, so
  2 instances × 10 = at most 20 concurrent executions cluster-wide. The Hikari `maximum-pool-size` default is
  30 and its floor comes from 10 workers each holding one connection (a job runs synchronously inside its
  worker thread, so it holds it for the whole execution) plus business queries plus cluster check-ins, all on
  the same pool. Adding threads without adding pool creates no capacity; it only moves the wait onto the
  30-second connection-timeout.
- **Clock requirements.** The cluster decides whether a node is alive by comparing
  `QRTZ_SCHEDULER_STATE.LAST_CHECKIN_TIME`; clock skew between nodes larger than the check-in interval causes
  false death detection and false takeover, so every node must be NTP synchronised. Cron expressions are
  interpreted in the JVM default time zone, so TZ must agree across nodes (compose mounts `/etc/localtime`
  uniformly and the image sets `TZ=Asia/Shanghai`).
- **An instance with scheduling off does not join the cluster.** `auto-startup` follows `scheduler.enabled`:
  Boot starts the scheduler by default, and such a node checks in, acquires triggers, then refuses to
  execute — a fire is consumed rather than handed over, and a lost one-shot is lost forever. Leaving the
  cluster is the only form of refusal that wastes no work.

### 5.1 The failover window

`JobStoreSupport.calcFailedIfAfter` decides a state row is expired from: that row's own last check-in
\+ `max(CHECKIN_INTERVAL, how long since the evaluating node last checked in)` \+ a hardcoded 7500 ms.
Substituting `clusterCheckinInterval = 15000` gives **22.5 seconds**; and a live node only evaluates this
expression when its own ClusterManager thread wakes up (the thread sleeps exactly one check-in interval), so
**add a 15-second polling granularity**; when the peer's round was delayed by a slow database, `max()` yields
30000 instead of 15000 and the line moves out a further 15 seconds. **Floor 22.5 s, ceiling 52.5 s; it is
never 15 seconds.**

### 5.2 Shutdown waiting and the grace period

`wait-for-jobs-to-complete-on-shutdown: true` (Boot's metadata default is `false`, so it must be written
explicitly) makes SIGTERM wait for running jobs. Because `AbstractAgentTaskJob.run()` returns only after
finishing inside the Quartz worker thread, this wait holds for the cron and manual paths alike; without it a
SIGTERM leaves a `status = 3` row in `agent_task_log` and a `status = 0` lock row in `agent_task_execution`,
both read as "still running", and they are only settled to `2 timeout` after one `expireStale`
(`timeout × 1.5`).

It must be configured together with the container's `stop_grace_period`: compose says `400s` and the
arithmetic is in the comments of `harnax-deploy/docker-compose.yml` and `application.yml` — execution timeout 300
\+ session-clear ceiling `min(clear-session-timeout, timeout)` 60 \+ the two calls' connect allowance 20 \+
settling the row and releasing the lock 12 = 392, rounded up. **Changing `SCHEDULER_TIMEOUT` or
`SCHEDULER_CLEAR_SESSION_TIMEOUT` means changing `stop_grace_period` in the same edit.**

Capacity is the other face of the same coin: one execution occupies one of `threadCount` workers until it
finishes, `SimpleThreadPool` has no queue, and a due cron can only wait while all workers are busy; past
`misfireThreshold` it becomes a misfire, and `concurrent = 0` uses exactly
`withMisfireHandlingInstructionDoNothing` — that fire is skipped, not deferred. The floor rule:
**do not run a scheduler on 1 or 2 workers.**

## 6. The full task lifecycle

### 6.1 Create / update / delete

```
user creates a task in the webui
  → POST /api/admin/agent-tasks      (admin: JwtAuthenticationFilter validates the user JWT
                                       → SchedulerClient.forward with internal JWT + the two forwarded headers)
  → POST /api/scheduler/agent-tasks  (scheduler: InternalCallerInterceptor verifies → reads headers into CallerContext
                                       → owner rules and cron validation → write harnax_scheduler.agent_task
                                       → one reconcile round after the transaction commits)
  → reconcile: the diff lands in the QRTZ_* store that every node reads
```

`AgentTaskCrudServiceImpl` is the only place this domain's business rules live; there is none on the admin
side:

| Operation | Predicate |
|---|---|
| Read one | `selectById(id, currentUsername)`: `active = 1` and `(is_public = 1 OR creator = current user)` |
| List | `selectTaskList`, the same visibility predicate, self-joining `agent_task_log` for `lastRunStatus` / `lastRunTime` |
| Write (update) | `selectById` first lets public tasks through, then checks `creator`; not the owner raises `Only the task creator can modify this task`. The WHERE of `updateById` also carries `creator = #{currentUsername}` |
| Delete | The WHERE of `deleteById` carries `creator`; the action is `active = 0` |
| Create | `creator = requireUsername()` (`CallerContext.username`, absence is refused), `tenantId = CallerContext.tenantId ?: 1` |
| Log reads | Always through `selectOwnedById` / `selectLogList`, where visibility is JOINed from the **owning task**, not from a log column: a log row duplicates `prompt` / `response` / `error_info` verbatim, so filtering by log columns alone would let anyone who can guess a task id read someone else's conversation content. This predicate carries no tenant condition — `tenant_id` is a snapshot taken at create time and the task list read takes no tenant parameter either, so narrowing by tenant would produce "the task is in the list but its execution log is empty" |

**The `afterCommit` rule**: the write to `agent_task` and the write to the store are not one transaction. If
the notification went out inside the transaction, reconciliation would read pre-commit rows and register a
definition that has not taken effect yet (in the delete case it is worse — it re-registers a task the UI
already believes is gone). Hooking it to `TransactionSynchronization.afterCommit` means a rolled-back
transaction notifies nobody.

That one notification can still be lost (there is no distributed transaction between processes) and the
consequence is "the definition is in the database, no scheduler is scheduling it", reported as
**40902 `CODE_SCHEDULER_SYNC_FAILED`** — emitted by the scheduler itself, passed through unchanged by admin
— rather than a rollback: the former needs only a scheduling retry, the latter a redo of the whole save.
**The boundary is explicit**: 40902 only asserts "this notification did not land", not that the task stays
wrong; the 60-second cluster sweep is the last backstop behind it. On an instance with
`SCHEDULER_ENABLED = false` that instance answers 40903, and the reload path reclassifies it as 40902 for the
caller (the 40903 text is kept in the message); start / pause / trigger pass 40903 through verbatim.

`/reload` is served by `SchedulerController.reload()` and its semantics are **run one reconciliation round**
(`reconcileTasks()` → `TaskScheduleReconciler.reconcile()`): success is returned only when
`ReconcileReport.converged` is true, so a round that did not finish cleaning returns non-200, and the answer
points the caller directly at `/actuator/health` (detail in `lastReconcileError`). Because it changes the
store every node reads, **one node converging = the cluster converging**, which is why admin collapses the
per-node broadcast into **a single forward** (`SchedulerClientImpl`'s `forward()` and `postToScheduler()`
both take `urls.firstOrNull()`, and an empty list answers the business error "No scheduler URL configured"
instead of throwing).

### 6.2 Registration

`TaskQuartzRegistrar` is the only entry point that writes tasks into the store; the shape:

```
job class   = concurrent == 0 ? AgentTaskNonConcurrentJob : AgentTaskJob
JobKey      = AgentTask_{id}      / group AgentTaskGroup
TriggerKey  = AgentTask_{id}_trigger / group AgentTaskGroup
JobDataMap  = { "taskId": "123" }    ← only the ID, never the entity
misfire     = concurrent == 0 ? DoNothing : FireAndProceed
```

Registration is one `scheduler.scheduleJob(jobDetail, setOf(trigger), true)`: `replace = true` swaps job and
trigger atomically and can also rescue an orphan job row that has no trigger. `checkExists → deleteJob →
scheduleJob` contains a window where `task_status = 1` in the table while the store holds nothing;
`rescheduleJob` is not a usable alternative because it returns a boxed `null` rather than `false` when the
trigger key is unknown. The cron is already validated by Quartz at build time.

The meaning of `concurrent` is **not** carried by the misfire instruction — misfire only decides how a
*late* fire is treated and never decides whether two live fires may overlap. The real switch is
`@DisallowConcurrentExecution`, which is a class-level annotation and cannot be varied per job instance, so
there are two job classes and `TaskQuartzRegistrar.jobClassFor(task)` picks between them; that function sits
on the companion object because `register`, `runTaskOnce` constructing the one-shot, and
`TaskScheduleReconciler` (comparing it against the class already in the store) — three call sites — must give
the same answer, otherwise the diff sees a change that does not exist and rewrites every job on every round.

**The manual fire**: `/tasks/{id}/trigger` and `/tasks/{id}/run-once` both call the same
`SchedulerServiceImpl.runTaskOnce`, which deposits

```
JobKey      = AgentTask_{id}_ONCE_{unique}   / group AgentTaskGroup_ONCE
TriggerKey  = same name + "_trigger"         / group AgentTaskGroup_ONCE
startNow(), no storeDurably()                ← after firing, Quartz discards it; it never leaves a row reconcile cannot read
```

It lands in this group rather than `AgentTaskGroup` because reconcile deletes "jobs whose expected entry no
longer exists in `agent_task`" — a click that just happened and is still waiting to fire is nobody's stale
schedule. The side effect must be stated plainly: this group is **outside reconcile's field of view** and
outside the `scheduledJobCount` figure (both read only `AgentTaskGroup`).

### 6.3 Triggering and execution

```
QRTZ_TRIGGERS.NEXT_FIRE_TIME comes due (cron and manual one-shot are the same kind of object)
  → one node moves the row to ACQUIRED inside the TRIGGER_ACCESS lock (exactly one node in the cluster succeeds)
  → that node's Quartz worker runs the job; AbstractAgentTaskJob.run() in order:
      ├─ fireTaskId: read taskId from the JobDataMap; without it this is not our job, refuse outright
      ├─ is scheduling enabled locally? if not, hand off to another node (the shared store hands jobs to nodes that did not register them)
      ├─ taskToRun: re-read agent_task by taskId; if the row is gone, delete the orphan job from the store
      ├─ guard: active != 1 always refuses; taskStatus != 1 refuses cron only (a one-shot is an intent the user expressed seconds ago)
      ├─ gate one: concurrent == 0 and this task already has a live execution → skip this fire
      ├─ gate two: guard.tryAcquireLock(taskId, scheduledFireTime) lost → another instance is running
      ├─ executeTaskOnce → insertRunningLog: insert agent_task_log(status = 3,
      │     session_id = task-{id}-{agentId}-{uuid})
      └─ POST router /api/router/agent/chat → session-router → agent-service
         end: finishExecution settles 1/0; if a 4 was read, finalizeStopped 4→5; clear the session
```

The ordering is deliberate in three places:

- **The enabled check precedes the re-read.** The re-read is both a read and a write (a missing row causes
  `deleteJob`), so a node that should not be scheduling would be performing scheduling writes by re-reading
  first; and the refusal itself wastes a database round trip.
- **Both gates sit before the log row is inserted**, so a blocked fire leaves no trace in `agent_task_log`.
  For manual execution that is enough to produce a user-visible surprise: `/trigger` returns 200 (the
  one-shot entered the store successfully), the fire then hits a gate and is dropped, and
  "execution succeeded → open the log list" can be an empty list. The only clue is the two scheduler log
  lines `skipping this fire` / `already being executed by another instance`. Delivery timing has a third
  gate, unlike the first two: `blocksManualRun` (= `concurrent == 0 && hasActiveRunningExecution`) refuses
  inside `runTaskOnce` and returns 40901, where the user does see an explicit conflict message.
- **The `taskStatus` guard applies to cron only** (decided from the JobDetail's group, since the JobDataMap
  holds only strings): a stored cron job is a lagging copy of `agent_task`, so at fire time you ask whether
  it is still running; registering a one-shot *is* the current intent, so a task paused "after the click,
  before the fire" still runs. Soft deletion refuses both.

**Deduplication happens in three places, each with its own job**: `blocksManualRun` at delivery time, the
per-task read at fire time (`@DisallowConcurrentExecution` excludes per JobDetail, and one task now holds a
cron JobDetail plus one per click, so it does not reach across), and `guard.tryAcquireLock` as the cluster
backstop. The per-task read is check-then-act: two fires that read the same instant both pass. Closing it
needs a per-task lock, which the `(task_id, trigger_time)` key cannot express.

**The agent-spec lookup**: agent-service asks
`GET /api/admin/internal/agent-spec/{sessionId}` back with `task-{taskId}-{agentId}-{uuid}`; admin's
`resolveFromTask()` uses **the very same** `TaskSessionId.parse` as the generating side to decode the two ids
and then **uses that agentId directly** to assemble the spec — it issues no query against `agent_task` at all
(the domain is outside this service, so it could not). The grammar accepts only the four-segment form: exactly
three `-`-separated segments after `task-`, both ids plain positive decimals, the tail segment non-empty
(`TaskSessionId.of` strips the UUID's hyphens when writing, which is why the segment count holds). Wrong
segment counts, signs and over-long input are refused outright and `resolveFromTask`'s exception message
carries the expected format plus the original string. **Nothing on this chain verifies that "this agentId is
the one the task actually uses"** — the `agent_task` table is outside admin's database, so that comparison
cannot be executed. The compensation is two things: same source on the minting side (both segments come from one row
read) plus strict four segments on the parse side. The reachable set is therefore any agent named by the
session id, not limited to "agents that have a task" (an internal caller identity is still required).

### 6.4 Stopping

Stopping is cross-node because the state lives in a database row, not in memory:

```
user clicks "stop" → admin forwards → any scheduler node's stopTask(logId)
  ├─ requireOwnedLog: fetch that row through the owning task's creator
  ├─ markStopping: 3→4 (CAS; losing means the row is already settled)
  ├─ routerClient.sendCommand(sessionId, INTERRUPT)   ← the session id is stored on the log row, so any node can send it
  │     it uses scheduler.command-timeout-seconds (10 s) rather than the execution's 300: the whole /stop must land
  │     inside admin's 30-second forward, otherwise the user sees a failure while the state is still undecided.
  │     A timeout counts as Unanswered, not Missed
  ├─ delivered (some instance answers "I have an in-flight execution") → the thread that really executes sees 4 on wrap-up and writes 5
  ├─ Missed (no instance is making progress on it) → **the node that accepted the stop** runs finalizeStopped 4→5 on the spot, without waiting for the reap sweep
  └─ Unanswered (the command never arrived) → the row stays at 4: nobody knows whether the execution is alive, so it is left to the executing node or the reap sweep
```

There are therefore **two** writers of 5: the execution thread (it obtained a real result) and the node that
accepted the stop (it obtained a "missed" answer, which is itself a conclusion). The second edge exists
because "missed" must not be folded into "delivered" — otherwise a row parked at 4 would end up written to
`2 timeout` by `expireStale`.

`/tasks/logs/{id}/stop` **is not behind the `scheduler.enabled` gate**: it writes no Quartz object, it flips a
row to 4 and asks router to interrupt a live session, which is equally correct on a node that refuses to
*schedule* new work; refusing here would trap an execution that is already running. A node with scheduling
off can therefore leave a row parked at 4 — that is picked up by the reap sweep, since
`SchedulerHousekeepingJob` is a cluster singleton fired by whichever node has scheduling on.

admin's forward **goes to one concrete address** (the first entry of the comma-separated
`HARNAX_SCHEDULER_URL` list; which replica receives it is Docker's round-robin DNS for the `scheduler` service
name). The converse also holds: when the node accepting the stop is not the executing node, its only basis for
writing 5 is that "missed" answer, so if one stop were broadcast to two nodes the second would answer "I have
no in-flight execution" and settle the row 4→5 on the spot, taking the real result out of the execution
thread's hands. A single forward is therefore not merely convenient; it is a correctness requirement.

### 6.5 Reaping

`expireStaleExecutions()` settles rows with `status IN (3, 4)` that exceed **1.5 times** the owning task's
`timeout_seconds` (0 or NULL falls back to `scheduler.timeout-seconds`) to `2 timeout`. The criterion is 1.5×
rather than 1×: an execution merely queued behind others inside agent-service is still alive, and a 1×
criterion would declare it a zombie at the very moment of the user's own timeout. What is written into
`error_info` is still the value the task itself configures, because that is the number the user can act on.
It is the last line of defence against a permanent "running" zombie after a node is killed, and it uses one
criterion for both execution paths: a manual execution is also a Quartz fire, and a SIGKILL leaves the same
`status = 3` row plus `status = 0` lock row, settled to 2 by the same rule. Both paths run on worker threads,
so inside the 400-second grace period reaping is never reached at all.

Three call sites: startup load, the housekeeping round every 5 minutes, and the pre-fire concurrency check
(`hasActiveRunningExecution`). The last one is throttled to **at most once per node per 30 seconds**
(`MIN_STALE_SWEEP_INTERVAL_MS`): it is an UPDATE scanning the active end of `idx_status`, which would collide
under MySQL with a same-instant insert's lock contention, and the loser drops a real execution; a fire that
sees no live row does not launch it at all.

"Last line of defence" is the operative phrase: `2` is not irreversible. When the execution thread comes back
afterwards with a real result it goes through `reclaimExpired` and overwrites to `0/1`, or to `5` if it had
read a 4. The reap sweep can only be a guess, never a verdict.

**One housekeeping round does four things** (`SchedulerHousekeepingJob`, constants on the class):

1. `expireStaleExecutions()`;
2. `cleanupOldExecutionLogs(LOG_RETENTION_DAYS = 90)` — deleting by `create_time` (indexed) rather than
   `start_time`, because rows that never obtained a start time would otherwise remain forever;
   `status NOT IN (3, 4)` is the same guard every writer carries: a row still reading as "live" belongs to an
   execution that may yet have to be reported, and age is not an argument;
3. `guard.cleanupOldExecutions(GUARD_RETENTION_DAYS = 7)`;
4. `guard.cleanupLeakedLocks()` — a row in `agent_task_execution` still at `status = 0` after more than twice
   the execution timeout is a lock whose holder is gone; leaving it means the `(task_id, trigger_time)` unique
   key permanently blocks a re-delivery of that fire.

A sweep slower than its own period does not stack a second round: both retention DELETEs run inside a job
annotated `@DisallowConcurrentExecution`.

The store is shared by the whole cluster, so this housekeeping round is: the sweep job is a row in the store,
hence **one node fires it per 5 minutes cluster-wide**, not one sweep per node. The operational consequence
is: **as long as any node in the cluster has scheduling on, reaping still runs**; a node with
`SCHEDULER_ENABLED = false` neither has nor needs a private reap path, and the row its own `stopTask` parked
at 4 is taken by whichever node fires the shared sweep job. A single node with the switch off is the only
case where nobody reaps.

## 7. Engine-layer logic

### 7.1 Reconciliation

`TaskScheduleReconciler.reconcile()` performs one diff:

```
desired = from agent_task where task_status = 1 AND active = 1: { taskId → (cron, concurrent) }
actual  = from the QRTZ store's AgentTaskGroup:                 { jobId → (cron, job class) }

  in desired, not in actual            → register
  in actual, not in desired            → delete
  in both but cron / job class differ  → reschedule
  identical                            → untouched (NEXT_FIRE_TIME preserved, execution history not reset)
```

**The cron comparison ignores case**: `CronExpression`'s String constructor upper-cases its input, so a cron
stored in `agent_task` exactly as the user typed it comes back upper-cased from the store. A verbatim
comparison would classify every cron containing a letter as "changed" and rewrite the whole group on every
round — precisely the behaviour this class exists to eliminate permanently — and it would also dirty the
drift metric.

Three call sites reuse one function: startup convergence, `/reload` (triggered after admin forwards a CRUD
call), and the cluster sweep job every 60 seconds. Two nodes running simultaneously is safe because of three
properties rather than luck: registration uses `scheduleJob(replace = true)` (an idempotent swap),
`SchedulerReconcileJob` carries `@DisallowConcurrentExecution` (a round slower than 60 seconds does not stack
a second), and the deliberate order **read the store first, then the table** — a CRUD landing between the two
reads shows up in the table but not in the snapshot, so this round re-registers it (a benign write); the
opposite order would delete the schedule the cluster just needed and take the cron boundary that fell inside
the window with it.

A single task's registration failure is recorded as drift rather than thrown: one unusable cron must not
spoil the whole round. `ReconcileReport` carries `converged` (`failedIds` empty) plus direction-separate drift
counts.

Both system jobs (reconcile and housekeeping) are registered in `SchedulerSystemGroup`, **not** in the
`AgentTaskGroup` that reconcile converges — otherwise it deletes itself. The sweep trigger also lives in the
shared store, so **the interval is one cluster-wide value**: a node started with a different
`scheduler.reconcile-interval-seconds` rewrites the stored trigger to its own and the last node to start wins.
This key is therefore not something to vary per instance.

For the same reason the reconcile job itself lives in the shared store, so **one node executes it per 60
seconds cluster-wide**.

### 7.2 The JobDataMap stores only the taskId

The single registration entry point stores only the `taskId` string (non-strings are a hard error under
`useProperties: true`), and `AbstractAgentTaskJob` re-reads `agent_task` at fire time for the current prompt /
cron / status. This puts the sole truth of the task definition back in the business table, an entity field
change cannot make a registered task unreadable, and the path "prompt or cron changed but the job was not
re-registered" does not exist.

One operational conclusion: **a deleted task cannot "still fire"**. When the re-read finds no row, that fire
deletes the orphan job from the store itself (`deleteJob` called from inside a running job is safe:
`JobStoreSupport.triggeredJobComplete` does not write it back), and the next reconcile round does the same
thing. So the window "deletion has taken effect but the job is still in the store" closes by itself no later
than the next due fire.

### 7.3 Execution is synchronous and there is one path only

`AbstractAgentTaskJob.run()` returns only after finishing inside the Quartz worker thread. The job class is
the only thing telling Quartz "this execution is live": handing work to a background thread makes `execute()`
return immediately, Quartz archives the fire as complete and deletes its `QRTZ_FIRED_TRIGGERS` row — after
that, failover has nothing to take over, `waitForJobsToCompleteOnShutdown` has nothing to wait for, and
`@DisallowConcurrentExecution` has no live execution to block.

The two execution paths are the same kind of object: there is no manual execution channel that bypasses
Quartz, `/trigger` and `/run-once` only deposit one-shots, so the shutdown grace, failover and concurrency
exclusion hold for both at once; a process that dies before the fire is compensated by Quartz rather than
silently losing the fire. `SchedulerConfig` has no private `taskExecutor` pool — manual execution has no pool
of its own, `QUARTZ_THREAD_COUNT` is the node's gate.

No subclass implements `InterruptableJob`: the real interruption is the INTERRUPT command `stopTask` sends to
router, and advertising a Quartz-level interrupt would only mislead the reader.

### 7.4 Startup convergence and registration retry

`SchedulerServiceImpl`'s initial load retries with backoff starting at 2 seconds and capped at 60 seconds, and
from the 5th attempt the log line is elevated to warning level. The two system jobs are registered through
`registerSweep` + `keepStoredIntervalOrMoveIt`: a trigger interval already in the store is kept unchanged
unless this process's configured value differs from it.

## 8. Cross-service boundary and authentication

### 8.1 The admin → scheduler forwarding contract

| Item | Practice |
|---|---|
| Outbound signature | `InternalTokenProvider` + `AuthRestTemplateInterceptor` sign an `typ=internal` HMAC JWT keyed by `harnax.auth.internal.shared-secret` (the same `HARNAX_AUTH_SECRET` admin uses). The far side really verifies: `InternalCallerInterceptor` admits only requests whose signature validates and whose `callerType == INTERNAL_SERVICE`, everything else is 401 |
| Outbound headers | `Authorization: Bearer …`, `X-Caller-Id` (this service's `harnax.auth.service-id`, here `scheduler`; `admin` on the admin side), `X-Forwarded-User`, `X-Tenant-Id`. **`X-Forwarded-Tenant` is not sent**, in either direction — of those three it is the only one a browser can add itself |
| Address | `harnax.scheduler.url` uses only the **first** entry of the comma-separated list (the rest are parsed without error but no code path consumes them). The landing node is equivalent because the store is read by every node; **but the node reached must have scheduling on**: a replica with `SCHEDULER_ENABLED = false` registered under the same service name receives this call by DNS luck and answers 40903 (the reload path reclassifies it to 40902), and the user sees "the task was saved but did not take effect". Disabled instances must be removed from the list or moved to a different service name |
| Timeouts | 30-second read timeout for `forward` and the internal POST (`SchedulerClientImpl`). The 10-second ceiling on the `stopTask` leg is derived backwards from this 30 |
| Response | admin returns the `JsonNode` unchanged rather than mirroring DTOs in admin — that would be a second definition of one contract, whose drift only becomes visible when a client reads `null` |
| Missing identity | `forwardedUser()` yields `null` in exactly three cases: no `authentication` at all, an `AnonymousAuthenticationToken`, or a blank principal name. An internal shared-secret call (agent-service's spec query) carries the principal marker `internal-service` on the admin side, and that marker maps to `SYSTEM` — the same name `UserContextUtil` gives the identical principal — so `X-Forwarded-User` arrives as `SYSTEM` instead of spelling a name that names nobody. Both headers are optional on the scheduler side and absence is not a refusal — `InternalCallerInterceptor` decides purely from the bearer's verification result |
| Idempotency | An intermediate state exists and someone covers it: the `agent_task` write and the store's convergence are not one transaction, so if the post-commit reconcile round fails or never runs the caller gets 40902 and the store and `agent_task` sit in that gap until the 60-second cluster sweep closes it. The gap lives entirely inside the scheduler process but it does not vanish — there are no distributed transactions between processes. Reconcile is a diff and is idempotent, so retrying `/reload` or waiting for the sweep yields the same result |

### 8.2 The scheduler's inbound gate

`InternalCallerInterceptor` is registered by `SchedulerWebConfig.addInterceptors` and its path pattern comes
from `InternalCallerInterceptor.PROTECTED_PATHS = ["/api/scheduler/**"]` — one pattern, **no exclusion list**,
the read surface included. The probe endpoints under `/actuator` are naturally outside it (they live under the
`/actuator` prefix, not on a list of exemptions), so compose's health checks are unaffected.

The order is **verify first, read headers second**:

1. `CallerContext.clear()` (at the top of the method, so "no identity survives a rejection" is a property of
   this method rather than a container hygiene habit);
2. take `Authorization`; a non-empty bearer must be extractable;
3. `InternalTokenProvider.verifyToken` (signature + expiry + `typ=internal`); any exception is 401, with no
   echo of the token and no echo of the underlying exception text;
4. `callerType != INTERNAL_SERVICE` is refused separately. This step is load-bearing, not a restatement of
   step 3: present a login token carrying a `userId` to `verifyToken` and, when the same key signed it, it
   answers `EXTERNAL_API` — pointing one secret at two roles follows the deployment document, it does not
   violate it;
5. only then are `X-Forwarded-User` and `X-Tenant-Id` placed into `CallerContext` (a ThreadLocal). Both are
   nullable: an internal call with no user behind it (for instance reading the owner during execution) is a
   normal call, not a reason to refuse; a non-numeric `X-Tenant-Id` is treated as absent.
6. The single clearing point is `afterCompletion`. `postHandle` is deliberately untouched: it runs after the
   handler and is skipped when the handler throws, and clearing there is exactly the leak this object exists
   to prevent — the thread would return to the pool carrying this identity, and this module's task-owner and
   visibility predicates read precisely those two values.

The rejection body is a `ResultVo`, shaped like `InternalAuthorizationInterceptor`'s, so clients need not
learn a second one.

`harnax.auth.enabled` stays `false`: that switch also assembles `UnifiedAuthFilter` (external API Keys, rate
limiting, `@InternalOnly`), which is a different design. And even switched on, `UnifiedAuthFilter` **cannot
verify a user login JWT** — it verifies with `harnax.auth.internal.shared-secret` while admin signs user
tokens with the different key `jwt.secret`.

**The deployment layer remains part of defence in depth**: compose only does `expose: ["8084"]` and publishes
no host port; `harnax-deploy/nginx.conf` has no `/api/scheduler/` location, with a comment at that position
prohibiting its re-addition. The gate is the second layer, not the only one.

### 8.3 Outbound calls from the scheduler

| Target | Purpose | Parameters |
|---|---|---|
| router `POST /api/router/agent/chat` | Execute one task | Read timeout `scheduler.timeout-seconds` (300); authenticated by `scheduler.api-key`, and when empty a SYSTEM-type key is requested from admin on the spot |
| router `POST /api/router/agent/command` | Send INTERRUPT on stop | Read timeout `scheduler.command-timeout-seconds` (10) |
| router `DELETE /api/router/agent/session/{id}` | Clear the session after execution | Ceiling `scheduler.clear-session-timeout-seconds` (60), effective value `min(this, timeout-seconds)` |
| admin internal endpoints | Request a SYSTEM key | `scheduler.admin-url` + `scheduler.admin-secret` |

Calling router with a SYSTEM key means **the `api_call_log` rows written by a scheduled execution have
`tenant_id IS NULL`**: this credential class has no tenant, so `ApiCallLogFilter` has no tenant to stamp.
And `GET /api/router/monitor/call-logs` shows a tenant-carrying caller only its own rows (the predicate is
derived server-side from the credential; the endpoint never accepts a `tenantId` parameter). The two rules
together: **a tenant user cannot see their own tasks' call records on their own monitor page** — those rows
exist only in the view of tenant-less internal/operational callers. This is the other face of "rows that
cannot be attributed must not become visible to everyone". Investigating why a scheduled execution failed means
reading `agent_task_log` (it has the prompt, the response, error_info and the duration), not router's call
log. `GET /api/router/monitor/instances` in the same controller deliberately does not apply the same
narrowing: it answers cluster topology (host, port, heartbeat age, how many sessions this instance holds),
belongs to no tenant and is an operational view; it stops at what an instance *is*, never whose sessions, let
alone any session content.

## 9. The API surface

### 9.1 What the scheduler exposes itself (all behind the inbound `InternalCallerInterceptor` gate)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/scheduler/agent-tasks/page` | Paged list, `Page` envelope |
| GET | `/api/scheduler/agent-tasks/{id}` | Detail |
| POST | `/api/scheduler/agent-tasks` | Create (the body carries the `agentName` admin already resolved; missing → 400) |
| PUT | `/api/scheduler/agent-tasks/{id}` | Update |
| DELETE | `/api/scheduler/agent-tasks/{id}` | Delete |
| POST | `/api/scheduler/agent-tasks/toggle/{id}?status=` | Enable/disable switch (a visibility read precedes the write) |
| POST | `/api/scheduler/agent-tasks/{id}/start` | Start scheduling |
| POST | `/api/scheduler/agent-tasks/{id}/pause` | Pause scheduling |
| POST | `/api/scheduler/agent-tasks/{id}/trigger` | Run now (deposits a one-shot) |
| POST | `/api/scheduler/agent-tasks/logs/{logId}/stop` | Stop one execution (an owner read precedes it) |
| GET | `/api/scheduler/agent-tasks/{id}/logs` | Execution logs for one task, paged (`Page` envelope, filtered by `taskName` / `status` / a start-time range / `keyword`) |
| GET | `/api/scheduler/agent-tasks/{id}/owner` | Owner read: returns `AgentTaskOwner` (creator + tenantId). It is a cold path, called by admin's `McpSessionOwnerResolver` while constructing an OAuth MCP client, not on the agent-spec query that every message takes |
| POST | `/api/scheduler/tasks/{id}/trigger`, `/start`, `/pause`, `/run-once` | Scheduling actions; these four plus `/reload` below are **all five writes that begin with `SchedulerController.requireEnabled`** (`trigger` / `start` / `pause` / `run-once` / `reload` each call it as their first statement), and every one of them is refused while this node has `scheduler.enabled = false` |
| POST | `/api/scheduler/reload` | Run one reconcile pass; non-200 unless converged; also behind `requireEnabled` |
| GET | `/api/scheduler/tasks/status` | `scheduledTaskCount` + `scheduledTaskIds`, read from the store on the spot |
| POST | `/api/scheduler/tasks/logs/{logId}/stop` | Stop an execution, **not behind the `scheduler.enabled` gate**: stopping writes no Quartz object at all — it flips the log row to 4 and asks the router to interrupt the live session, both of which are exactly as correct on a node that refuses to schedule work. Refusing it would strand executions already running |

The UPDATE behind `updateStatus` (which start / pause take) has a WHERE of only `id` and `active = 1`, with no
`creator`; the `/start`, `/pause` and `/trigger` endpoints relay straight to `SchedulerController` without the
visibility pre-read that `toggle` and `stop` perform. Net result: any valid internal forward (admin signs one
whenever the caller is logged in) can start, stop or immediately run someone else's task by id; the owner
predicate on the read, modify and delete paths does not cover these.

### 9.2 The outward contract on the admin side

`AgentTaskController` (admin) is mounted at `/api/admin/agent-tasks`; 11 of its 12 endpoints go through
`SchedulerClient.forward` and `/agents` stays in admin's own domain. The scheduler side is what keeps the
paths, methods, `ResultVo` envelope, the 7 keys of `Page` (its shape pinned by `PageContractTest`), the field
names of `records[*]` and the meanings of `40901` / `40902` / `40903` identical to what clients see today. The
two forwarding cases deliberately pass through without any local decision: the visibility gate moved together
with the data, and the forwarded endpoints read `X-Forwarded-User` themselves and answer a mismatch as
"Agent task not found".

## 10. Observability

| Signal | Meaning | Where |
|---|---|---|
| The `scheduler` indicator under `/actuator/health` | Whether this process really is scheduling. Details: `quartzStarted`, `instanceId` (the real instance name under clustering), `storeType`, `scheduledJobCount` (**read from the store on the spot**), `lastReconcileAt`, optional `lastReconcileError`. There are only three verdict rules: Quartz not started → DOWN; never converged successfully → DOWN; the last round failed or left drift → DOWN. `scheduler.enabled = false` → UP carrying only `enabled: false`. `storeType` and `scheduledJobCount` are details and **take no part in the verdict**; when the store cannot be read the count is -1 rather than a plausible 0. `lastReconcileAt` is **this node's** last round, not the cluster's — the 60-second sweep is a singleton and only the node that fires it stamps this field, so on a two-node cluster the other node's value can be hours old, and no rule (alerts included) should read its age | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/health/SchedulerHealthIndicator.kt` |
| `/actuator/health/liveness` | Answers only "is the process alive", **deliberately excluding** the indicator above: a failed convergence round is a database or task-table problem, and restarting the container neither repairs it nor leaves the waiting retry loop alive. This is what compose's health check and `roll-scheduler.sh` look at | The design note is in the `SchedulerHealthIndicator` class comment |
| `scheduler.reconcile.rounds{outcome=success\|failure}` | **The verdict of one reconcile round**, one sample from the node that ran it. All three callers count: startup convergence, admin's forwarded `/reload`, the 60-second cluster sweep. It is not a restart counter | `SchedulerMetrics.recordReconcileRound` |
| `scheduler.reconcile.drift{action=add\|remove\|update}` | The drift this round corrected, bucketed by direction; an untouched bucket emits no sample (a healthy round emits nothing at all). This is the earliest place "CRUD out of sync with the store" becomes visible. **How to read it**: one node per round emits the sample (the sweep is a cluster singleton and one forward lands on one node too), so summing across `instance` yields the **cluster total**; reading a **single node's** series as a cluster figure is the misleading reading. Set the alert on "non-zero on any instance" | `SchedulerMetrics.recordReconcileDrift` |
| `scheduler.jobs.scheduled` | The number of jobs in `AgentTaskGroup` in the store, read straight from `QuartzJobInventory`. The store is shared, so this expression **is a cluster view** (both nodes report the same value) and a threshold once set as "this instance's view" needs re-reading. NaN when the store cannot be read | `SchedulerMetrics` + `QuartzJobInventory` |
| `QRTZ_SCHEDULER_STATE` | The operator's direct query: how many `INSTANCE_NAME`s there are and whether each `LAST_CHECKIN_TIME` is advancing — the fastest way to decide "did the second node actually join the cluster". **Do not read the row count as the replica count**: a node never deletes its own row (not even on graceful shutdown); a row is deleted by **the peer**, after `calcFailedIfAfter` marks it expired, as a side effect inside `clusterRecover`. Seeing 3 or 4 rows right after rolling two replicas is the normal answer | `SELECT INSTANCE_NAME, LAST_CHECKIN_TIME, CHECKIN_INTERVAL FROM harnax_scheduler.QRTZ_SCHEDULER_STATE;` (this is the statement `roll-scheduler.sh` reads back at the end) |

## 11. Configuration

The `scheduler` prefix (`harnax-scheduler/src/main/resources/application.yml`):

| Key | Default | Effect |
|----|--------|------|
| `scheduler.enabled` / `SCHEDULER_ENABLED` | `true` | Whether this node accepts scheduling work. It also determines `spring.quartz.auto-startup`, so `false` means "leave the cluster", not "warm standby". It is the sole basis on which `SchedulerController.requireEnabled` refuses writes: the `SchedulerFactoryBean` starts regardless and `init()` still populates the Quartz context (the shared store hands arbitrary fires to this host, the two system sweeps included, so the collaborators must be present); without the gate a job would be registered that both fires and runs locally while the caller already received 200 |
| `scheduler.reconcile-interval-seconds` | 60 | The cluster sweep interval. **One cluster-wide value** (the trigger is in the shared store and the last node to start decides), so do not vary it per instance |
| `scheduler.instance-id` | empty | The `instance_id` label on lock rows. compose leaves it empty and comments the reason: when every replica gets the same value, this column asserts "one node" while there are two. Quartz's own cluster identity does not read this key (`instanceId: AUTO`) |
| `scheduler.router-url` / `SCHEDULER_ROUTER_URL` | `http://localhost:8081` | The downstream for execution and stop |
| `scheduler.api-key` / `SCHEDULER_API_KEY` | empty | The SYSTEM key used against router; when empty, one is requested from admin on the spot |
| `scheduler.admin-url` / `SCHEDULER_ADMIN_URL` | `http://localhost:8080` | Key issuance and internal endpoints |
| `scheduler.admin-secret` | `SCHEDULER_ADMIN_SECRET` | Secret for admin's internal endpoints |
| `scheduler.timeout-seconds` / `SCHEDULER_TIMEOUT` | 300 | Two readers: the read timeout of router chat, and the baseline of the reap decision `timeout × 1.5`. Splitting them would have the reap sweep declare merely-slow executions zombies |
| `scheduler.clear-session-timeout-seconds` | 60 | The ceiling on clearing the session after execution, effective value `min(this, timeout-seconds)`. It is not shared with `timeout-seconds` because that would let one execution hold a worker for twice the timeout. It is part of the `stop_grace_period` arithmetic |
| `scheduler.command-timeout-seconds` | 10 | The ceiling on INTERRUPT along the `/stop` path. Its only constraint is the chain the user waits on (admin forwards `/stop` with 30 seconds); it is not in the `stop_grace_period` arithmetic — `stopTask` runs on the request thread and never on a Quartz worker. A timeout here counts as Unanswered and the row stays at 4 |
| `harnax.auth.enabled` | `false` | Turning it on also assembles `UnifiedAuthFilter` (external API Key acceptance, rate limiting, `@InternalOnly`), whereas this service installs only `InternalCallerInterceptor`'s service-token gate; and `UnifiedAuthFilter` verifies with the internal shared secret, so it cannot verify user tokens signed by admin with `jwt.secret` |
| `harnax.auth.service-id` | `scheduler` | The label on the internal token this process mints, and the outbound `X-Caller-Id`. Nothing on the inbound path is decided by it |
| `harnax.auth.internal.shared-secret` | `HARNAX_AUTH_SECRET` | Dual duty: the key that signs outbound tokens and the key that verifies admin's forwarded bearer. **Both sides must hold the same value**; compose feeds one variable to every service, and manual deployment is the only place this can be misconfigured |
| `harnax.auth.internal.token-ttl-seconds` | 300 | Internal token lifetime |

For the item-by-item reasoning behind the `spring.*` side (Quartz / Flyway / datasource / Hikari), read
`harnax-scheduler/src/main/resources/application.yml`'s `spring.quartz` block. Quartz-related environment
variables: `QUARTZ_JOB_STORE`, `QUARTZ_WAIT_FOR_JOBS`, `QUARTZ_THREAD_COUNT`, `SCHEDULER_QUARTZ_AUTO_STARTUP`,
`SCHEDULER_FLYWAY_ENABLED` (outer default `true`, nested fallback to `FLYWAY_ENABLED`), `DB_POOL_SIZE`,
`SPRING_DATASOURCE_URL` (compose uses the independent `SCHEDULER_DB_URL` and **does not reuse** the `DB_URL`
shared by admin/agent/channel, so one edit does not take down three services).
`management.endpoints.web.exposure.include: health,info,prometheus,metrics`, `probes.enabled: true`, and
`springdoc` is controlled by `SWAGGER_ENABLED`.

## 12. Deployment and operations checklist

Image and topology: `harnax-deploy/Dockerfile.scheduler` (`eclipse-temurin:21-jre-alpine`, `TZ=Asia/Shanghai`,
non-root user, `EXPOSE 8084`, a container-level healthcheck against `/actuator/health/liveness`); the
`scheduler` section of `harnax-deploy/docker-compose.yml` (`expose: ["8084"]`, no `container_name`,
`stop_grace_period: 400s`, volume `scheduler-logs`, mounted `/etc/localtime`, `depends_on` mysql healthy +
admin/router started). `harnax-deploy` is the only usable deployment entry in this repository.

Shipping one version that touches this domain:

1. Confirm the `harnax_scheduler` database exists and is granted (already in
   `harnax-deploy/sql/init-databases.sql`; that script only runs when MySQL initialises an empty data directory,
   so an existing deployment needs those two statements plus `FLUSH PRIVILEGES` applied by hand).
2. Three `.env` checks: set `SCHEDULER_DB_URL` only when the database must differ (unset means compose's
   default `harnax_scheduler`), admin and the scheduler hold the **same `HARNAX_AUTH_SECRET` value**, and
   **every scheduler node is NTP synchronised**.
3. Stop **all replicas** of admin and scheduler. Two reasons these cannot be done in batches: the scheduler's
   gate accepts only internal JWTs, so an unsigned forward is a 401; and the shape of the task session id is
   parsed strictly by `TaskSessionId` (exactly three segments after `task-`, both ids plain positive decimals,
   tail segment non-empty), minted by `SchedulerServiceImpl` and parsed by admin's `resolveFromTask`, so a
   caller minting a different shape is refused on that side directly. The drain criterion for this step is
   `status IN (3,4)` counting 0 in `agent_task_log` — an execution spanning the stop/start boundary never
   settles: its log row is in the database connected before the stop, the restarted scheduler looks for it in
   its own database, and `finishExecution` / `markStopping` / `expireStale` all affect 0 rows.
4. Start **one** scheduler replica and let Flyway apply this module's baseline,
   `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql`.
5. Verify the tables: three `agent_task*` + 11 `QRTZ_*` + one row in `flyway_schema_history_scheduler`.
   **`agent_task_log` must be present even when empty** — `selectTaskList` self-joins it for `lastRunStatus` /
   `lastRunTime`, and a missing table is a 500 on the list page.
6. Let reconciliation run one round (wait for the 60-second sweep, or create a task so admin's forward
   triggers it) and verify the number of scheduled tasks is 0: `scheduledJobCount` under `/actuator/health`,
   or `scheduledTaskCount` from `GET /api/scheduler/tasks/status` (the latter needs an internal JWT). Non-zero
   means this node is still connected to a different database.
7. Start admin and walk the main path of each of the three clients: webui list / create / edit / start-stop /
   delete / run-now / log polling, CLI `task list|get|create|trigger|stop`, and the mini-program task page.
8. Start the second replica (`--scale scheduler=2`) and read back `harnax_scheduler.QRTZ_SCHEDULER_STATE`:
   check that each of the two `INSTANCE_NAME`s advances its `LAST_CHECKIN_TIME` every 15 seconds,
   **do not count rows**.
9. For a node-by-node rolling update use `harnax-deploy/roll-scheduler.sh`: it holds one cross-process mutex for
   the whole flow (stop and start one node at a time, waiting for each new container to be healthy) and reads
   back the SQL above at the end. **The lock's key names the cluster, not the directory the script sits in**:
   it takes `COMPOSE_PROJECT_NAME`, falling back to "the basename of the directory holding the compose file",
   lower-cased and trimmed to the character set compose allows, then suffixed with the docker daemon name —
   that is compose's own project-name rule, and in this repository all three worktrees call that directory
   `harnax-deploy`, so keying on the checkout directory name would let one cluster hold several locks.
   `LOCK_BASE_DIR` defaults to `/tmp` (deliberately not `$TMPDIR`: that would give every GUI login its own
   per-user lock directory, and cron and sudo deployments land on yet other values, and different values are
   different locks and one unguarded cluster), and **every caller must pass the same value**. `GRACE` and
   `HEALTH_WAIT` correspond to `stop_grace_period` and the health wait respectively.
10. Handle the same-named tables of this domain in `harnax_admin`: admin's baseline neither creates the three
    `agent_task*` tables nor drops them, so no step converges them automatically — an installation that created
    `harnax_admin` before the split and also pointed `QUARTZ_JOB_STORE=jdbc` at it may still hold those three
    plus the 11 `QRTZ_*`. This service's scheduler does not connect to that database and not one read path of
    this domain points there, so operators drop them in place.
11. Rollback: point the scheduler's datasource back to `harnax_admin`, with `QUARTZ_JOB_STORE=memory` and
    `SCHEDULER_FLYWAY_ENABLED=false` (both `application.yml` and compose read this key), and explicitly accept
    that "tasks created or changed while the datasource pointed at `harnax_scheduler` do not come back with
    it" — once that database has been written to, a rollback is not a `revert`. Returning to the
    `harnax_admin` database also requires the three tables to exist there first (admin's baseline does not
    create them): operator DDL is the clean path, and temporarily setting `SCHEDULER_FLYWAY_ENABLED=true` so
    this module's baseline builds them there works too, provided that database's
    `flyway_schema_history_scheduler` ledger matches its actual schema — otherwise `validate-on-migrate`
    refuses the boot first.

## 13. Explicitly out of scope and boundaries

| Boundary | Current state |
|---|---|
| User tokens hitting the scheduler directly | Not supported. The scheduler's gate accepts service tokens only, and the end-user identity enters exclusively through admin's two forwarded headers. Reproducing router's route (`X-Api-Key` + remote validation + hand-written tenant decision) would require all three clients to change the credential they send |
| Full self-authentication via `harnax.auth.enabled = true` | Outside this service's scope. That suite also brings external API Key acceptance, rate limiting and the `@InternalOnly` model |
| Response masking in `agent_task_log` | Not done. Log reads pass through the owning task's visibility JOIN, but `prompt` / `response` / `error_info` are still sent in full |
| Fine-grained authorization by role | None. In this domain admin requires only "logged in" (`SecurityConfig`'s catch-all rule), with no role or permission code; `tenant_id` is written once at create time and MyBatis sets up no tenant interceptor (no automatic filtering whatsoever). The owner condition is currently the only isolation mechanism, it is weaker than tenant isolation, and it does not cover `/api/scheduler/agent-tasks/{id}/start`, `/pause` or `/trigger` |
| Cross-database reads | None. This service has one datasource |
| `SCHEDULER_ENABLED = false` as a warm standby | Not what it is for. `false` removes the whole deployment from the cluster, and compose feeding one interpolation to every replica exists precisely to prevent treating it as a per-instance switch; rolling updates are `roll-scheduler.sh`'s job |
| A per-instance private rebuild path | None. Rows left at 4 by a disabled instance are taken by whichever node fires the shared sweep job |
| Quartz-level interruption | Not done: no job class implements `InterruptableJob`, interruption goes only through router's INTERRUPT command |
| Proof that `agent_task_execution`'s two sweep indexes are actually used on a real database | Not observed: that needs `EXPLAIN` against real MySQL, and real MySQL exists only inside Testcontainers in the integration tests |
| Integration tests | Five IT classes (`ClusterSingleFireIT`, `ReconcileConvergenceIT`, `HousekeepingGuardIT`, `AgentTaskOwnerScopeIT`, `AgentTaskMapperSemanticsIT`) under `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/it/`, run by `mvn -Pintegration-test verify -pl harnax-scheduler`, kept out of the default reactor by `-Pintegration-test`, requiring a Docker daemon. Nothing automated proves "kill one, the other takes over within 22.5 to 52.5 seconds"; that is a manual `docker kill` plus a read-back of `QRTZ_SCHEDULER_STATE` |

## 14. Troubleshooting quick reference

| Symptom | Look at first | How to read the answer |
|---|---|---|
| A task did not run on time | `scheduledJobCount` under `/actuator/health` and `scheduler.jobs.scheduled` | 0 → this task never entered the store; check `lastReconcileError` and `scheduler.reconcile.drift` |
| The task was saved but did not take effect and the call returned 40902 | Whether the node the forward landed on has scheduling on | One forward lands on one node; a `SCHEDULER_ENABLED = false` instance answers 40903 which is reclassified to 40902. Wait one 60-second sweep, or remove the disabled instance |
| The second node did not join the cluster | Whether `LAST_CHECKIN_TIME` advances for both `INSTANCE_NAME`s in `QRTZ_SCHEDULER_STATE` | Only one row moving = the other node is on a different database or a different `instanceName`; more rows than replicas is the normal answer |
| Takeover after a kill is slow | `QRTZ_SCHEDULER_STATE.LAST_CHECKIN_TIME` and the `JobStoreSupport.calcFailedIfAfter` formula | 22.5 s is the best case, 52.5 s the worst when the peer's check-in is also late; "takeover in 15 seconds" is not this configuration's behaviour |
| An execution stays "running" forever | The 30-second throttle on `expireStale` and the 5-minute round | The ceiling is `timeout × 1.5` plus one sweep's wait; with a single node and `SCHEDULER_ENABLED = false` nobody reaps |
| A restart cut an execution short | The container's `stop_grace_period` and `QUARTZ_WAIT_FOR_JOBS` | 400 seconds comes from `timeout + clear + 20 + 12`; if one of the two is edited and the other does not follow, you will see rows parked at 3 |
| Manual execution reported success but the log list is empty | `skipping this fire` / `already being executed by another instance` in the scheduler log | Both fire-time gates sit before the log row is inserted, so a blocked execution leaves no trace; 200 only says the one-shot entered the store |
| Concurrency refused on run-now | `blocksManualRun` inside `runTaskOnce` | 40901 means "this task already has a live execution and overlap is forbidden"; poll, do not retry |
| The user clicked stop but the state does not move | The three branches of `stopTask`: delivered / Missed / Unanswered | Under Unanswered the row stays at 4, waiting for the executing node or for reaping; first check whether router's `/api/router/agent/command` is reachable and within 10 seconds |
| A real result appears after a stop | Whether that row's `error_info` carries `(completed after auto-expiry)` | A late execution thread overwrote the sweep's guess through `reclaimExpired`; if the stop vanished from the record, that is `expireStale` having covered 4 with 2 and `reclaimExpired` then writing back |
| Every task is rewritten after each restart | Whether `scheduler.reconcile.drift{action=update}` is non-zero on the per-minute round | First check whether `TaskScheduleReconciler`'s cron comparison ignores case, then whether `TaskQuartzRegistrar.jobClassFor`'s three call sites give the same answer |
| No 401 where the endpoint should have refused | Whether the bearer is an `typ=internal` one | A login token or a token signed with a different key is always 401; `X-Caller-Id` of `admin` with a different signing key is also 401 |
| The task changed but the owner rule had no effect | Whether that hop carried `X-Forwarded-User` | No header → `CallerContext.username` is `null` → the write path's `requireUsername()` refuses outright |
| Scheduled executions are missing from the monitor page | `api_call_log.tenant_id IS NULL` | Execution uses a SYSTEM key, that credential class has no tenant, `ApiCallLogFilter` has no tenant to stamp, and tenant-carrying callers see only their own rows — designed behaviour; for the execution itself read `agent_task_log` |
| A deleted task's name cannot be reused | `uk_name` versus `selectByName`'s `active = 1` | The soft-deleted row still holds the name, the duplicate-name pre-check cannot see the holder, and the INSERT hits the key which surfaces as a 500 |

## 15. Key file index

| Concern | File |
|---|---|
| Service entry and bean wiring | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/SchedulerApplication.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/config/SchedulerConfig.kt` |
| Web layer (gate registration + exception envelope) | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/config/SchedulerWebConfig.kt` |
| Inbound gate and identity context | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/support/InternalCallerInterceptor.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/support/CallerContext.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/support/SchedulerBizException.kt` |
| CRUD rules | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskCrudServiceImpl.kt` |
| Scheduling and execution service | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/SchedulerService.kt` |
| Reconciliation | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/TaskScheduleReconciler.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/SchedulerReconcileJob.kt` |
| The single store write entry | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/TaskQuartzRegistrar.kt` |
| Every decision inside one fire | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AbstractAgentTaskJob.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AgentTaskJob.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/AgentTaskNonConcurrentJob.kt` |
| Reaping | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/SchedulerHousekeepingJob.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/AgentTaskExecutionGuard.kt` |
| Log queries and visibility | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/AgentTaskLogQueryServiceImpl.kt` |
| HTTP endpoints | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/SchedulerController.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskOwnerController.kt` |
| Downstream calls | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/client/RouterClient.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/client/CommandDelivery.kt` |
| Health and metrics | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/health/SchedulerHealthIndicator.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/health/QuartzJobInventory.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/health/SchedulerStatus.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/metrics/SchedulerMetrics.kt` |
| Entities and statements | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTask.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskLog.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskExecution.kt`, `harnax-scheduler/src/main/resources/mapper/AgentTaskMapper.xml`, `harnax-scheduler/src/main/resources/mapper/AgentTaskLogMapper.xml`, `harnax-scheduler/src/main/resources/mapper/AgentTaskExecutionMapper.xml` |
| Response envelopes | `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/Page.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskResponse.kt`, `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/dto/AgentTaskLogResponse.kt` |
| Schema | `harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql` (the only definition of this domain's 14 tables), `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` (which holds none of this domain's tables) |
| Runtime configuration | `harnax-scheduler/src/main/resources/application.yml` |
| Task session id grammar | `harnax-common/src/main/kotlin/com/agnetix/harnax/common/session/TaskSessionId.kt` |
| Admin-side authentication + forwarding | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/SchedulerClient.kt` |
| Admin-side agent-spec lookup | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` (`resolveFromTask`) |
| MCP identity for task sessions | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolver.kt` |
| Deployment | `harnax-deploy/Dockerfile.scheduler`, `harnax-deploy/docker-compose.yml`, `harnax-deploy/roll-scheduler.sh`, `harnax-deploy/nginx.conf`, `harnax-deploy/sql/init-databases.sql` |
