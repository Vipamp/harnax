# Channel 表变更自动检测与监听热更新

> **本文是已落地的历史设计稿，实现与下文有四处差异，改动前请以代码为准。**
>
> 1. **变更检测不用 `updateTime`**。MySQL `DATETIME` 只到秒，同一秒内的两次编辑无法区分；且改名、
>    改备注这类展示字段会白白踢掉一条健康的长连接。现在比较 `configFingerprint()`，只覆盖影响监听
>    行为的列（type / communicationMode / agentId / enabled / status / sessionId / callbackKey / configJson）。
> 2. **分发不看 `communicationMode`**，改由 `ChannelAdaptorRegistry` 按 `ChannelType` 取 adaptor，
>    因此没有 `feishuAdaptor.stopChannel()` 这样的分支；`communicationMode` 只用于判断 webhook 模式免探活。
> 3. **新增监听互斥**：多副本部署时用 MySQL `GET_LOCK`（`ChannelListenerLockGuard`）保证只有一个实例
>    持有长连接，否则同一条用户消息会被消费两次、会话历史分裂在两份内存里。
> 4. **新增启动预算与退避**：`startChannelWithAgent()` 回传是否真的建立了监听，失败按指数退避重试
>    （`ReconnectBackoff`），每轮启动数量受 `channel.sync.max-starts-per-cycle` 限制，避免下游拥塞时
>    整轮对账都在重建连接。运行状态由 `ChannelRuntimeMonitor` 统一暴露（`/actuator/channels`）。
>
> 权威实现：`ChannelBootstrapRunner.reconcile()`。

## 问题分析

当前 `ChannelBootstrapRunner` 仅在 `ApplicationReadyEvent` 时从数据库加载一次 channel 配置并启动监听。之后 admin 对 channel 表的任何操作（新增、修改、删除、启停）都不会被 channel-service 感知，必须重启服务才能生效。

## 方案：定时轮询 + 差异比对

在 `ChannelBootstrapRunner` 中增加定时轮询能力，周期性地从 DB 拉取最新 channel 列表，与内存中正在运行的 channel 进行差异比对，执行 start/stop/restart 操作。

**核心设计：**
- 用 `ConcurrentHashMap<Long, RunningChannel>` 跟踪正在运行的 channel 及其 `updateTime`
- 每 10 秒轮询一次（可配置 `channel.sync.interval-ms`）
- 变更检测依据：channel 的 `updateTime` 字段变化
- 三种操作：新增启动、删除/禁用停止、配置变更重启（先停后启）

---

## 实现任务

### Task 1: 改造 ChannelBootstrapRunner 为带生命周期管理的组件

**文件:** `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/bootstrap/ChannelBootstrapRunner.kt`

改造要点：
1. 新增内部类 `RunningChannel(spec: ChannelSpec, updateTime: LocalDateTime)` 记录运行中 channel 的状态
2. 新增 `runningChannels: ConcurrentHashMap<Long, RunningChannel>` 跟踪运行中的 channel
3. 保留原有 `@EventListener(ApplicationReadyEvent::class)` 启动逻辑，但改为调用统一的 `reconcile()` 方法
4. 新增 `@Scheduled(fixedDelayString = "${channel.sync.interval-ms:10000}")` 定时方法，调用 `reconcile()`
5. `reconcile()` 方法逻辑：
   - 从 DB 加载 `selectAutoStartChannels()` 得到期望运行的 channel 列表
   - 与 `runningChannels` 比对，计算出 toStart / toStop / toRestart 三个集合
   - **toStart**: DB 中存在但内存中不存在 → 调用 `startSingle()`
   - **toStop**: 内存中存在但 DB 中不存在（或被禁用/删除）→ 调用 `stopSingle()`
   - **toRestart**: DB 中存在且内存中也在运行，但 `updateTime` 不同 → 先 stop 再 start
6. 新增 `stopSingle(entity)` 方法，根据 communicationMode 调用对应的 `feishuAdaptor.stopChannel()` 或 `wechatAdaptor.stopChannel()`
7. 所有操作需要 try-catch 保护，避免单个 channel 失败影响其他 channel

### Task 2: 启用 Spring Scheduling

**文件:** `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/ChannelServiceApplication.kt`

添加 `@EnableScheduling` 注解以支持 `@Scheduled` 定时任务。

### Task 3: 补充配置项

**文件:** `harnax-channel/harnax-channel-service/src/main/resources/application.yml`

添加可配置项：
```yaml
channel:
  sync:
    interval-ms: 10000  # 轮询间隔，默认10秒
```

---

## 关键约束

- webhook 模式的 channel 不需要主动连接，仅在 websocket / long_polling 模式下需要 start/stop
- `stopSingle` 需要在 try-catch 中执行，因为 WebSocket 断开或 WeChat 轮询停止可能抛异常
- 轮询任务需要容错：DB 查询失败时跳过本轮，不影响已运行的 channel
- 使用 `fixedDelay` 而非 `fixedRate`，确保上一轮完成后再等间隔，避免重叠执行
