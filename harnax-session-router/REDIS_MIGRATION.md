# Redis 分布式缓存迁移指南

## 📋 概述

本次改造将 router 模块从本地 Caffeine 缓存迁移到 Redis 分布式缓存，以支持多节点部署场景下的状态共享和一致性。

## 🎯 改造目标

1. **会话粘性（Session Stickiness）**：确保相同 session 始终路由到同一 agent-service 实例
2. **分布式一致性**：多个 router 节点共享实例注册表、会话映射、幂等性控制和熔断器状态
3. **高可用性**：消除单点故障，支持 router 节点的动态扩缩容
4. **安全性增强**：修复 SSRF 漏洞，防止恶意实例注册

## 🔧 技术变更

### 1. 新增依赖

在 `pom.xml` 中添加：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
</dependency>
```

### 2. Redis 配置

在 `application.yml` 中配置 Redis 连接：
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: ${REDIS_DATABASE:0}
      timeout: 3000
      lettuce:
        pool:
          max-active: 50
          max-idle: 10
          min-idle: 2
          max-wait: 5000
```

### 3. 核心服务改造

#### InstanceRegistry
- **原实现**：`MysqlInstanceRegistry` + Caffeine 本地缓存
- **新实现**：`RedisInstanceRegistry` + Redis 分布式缓存
- **改进点**：
  - 实例状态在多节点间实时同步
  - 健康检查缓存使用 Redis Set 结构
  - 实例信息使用 Hash 结构存储

#### SessionMappingService
- **原实现**：`MysqlSessionMappingService` + 无缓存
- **新实现**：`RedisSessionMappingService` + Redis 分布式锁
- **改进点**：
  - 会话映射缓存到 Redis，加速查询
  - 使用分布式锁防止并发重路由的竞态条件
  - 会话 TTL 自动过期（24小时不活动）

#### IdempotencyService
- **原实现**：`CaffeineIdempotencyService`（本地缓存）
- **新实现**：`RedisIdempotencyService`（分布式）
- **改进点**：
  - 跨节点去重，防止负载均衡导致的重复请求绕过
  - 使用 Redis SET NX 原子操作保证幂等性

#### CircuitBreaker
- **原实现**：本地 ConcurrentHashMap
- **新实现**：保留本地实现（性能考虑），可选升级为 `RedisCircuitBreaker`
- **说明**：熔断器状态本地化可避免网络延迟影响性能，如需强一致性可使用 Redis 版本

### 4. Redis Key 设计

```
# 实例注册表
router:instance:{instanceId}              -> Hash (host, port, status, lastHeartbeat)
router:instances:healthy                   -> Set (健康实例 ID 列表)
router:instances:all                       -> Set (所有活跃实例 ID 列表)

# 会话映射
router:session:{sessionId}                -> String (绑定的 instanceId)

# 幂等性控制
router:idempotency:{requestId}            -> String (时间戳，TTL 60s)

# 熔断器状态（可选）
router:circuit:{instanceId}:state         -> String (CLOSED/OPEN/HALF_OPEN)
router:circuit:{instanceId}:failures      -> Integer (失败计数)
router:circuit:{instanceId}:last_failure  -> Long (最后失败时间戳)

# 分布式锁
router:lock:session:{sessionId}           -> String (锁持有者，TTL 5s)
```

### 5. 安全加固

#### SSRF 防护
在 `SessionRouterController.registerInstance()` 中添加：
- ✅ 仅允许 IPv4 地址格式（拒绝域名，防止 DNS 重绑定攻击）
- ✅ 阻止私有网络地址（10.x.x.x, 172.16-31.x.x, 192.168.x.x）
- ✅ 阻止回环地址（127.x.x.x, 0.x.x.x）
- ✅ 阻止链路本地地址（169.254.x.x）
- ✅ 限制端口范围（8000-9999），防止访问内部服务

## 🚀 部署步骤

### 1. 准备 Redis 环境

```bash
# 安装 Redis（如果尚未安装）
docker run -d --name redis \
  -p 6379:6379 \
  -v redis-data:/data \
  redis:7-alpine \
  redis-server --appendonly yes

# 或使用 Kubernetes
kubectl apply -f redis-deployment.yaml
```

### 2. 更新配置文件

设置环境变量：
```bash
export REDIS_HOST=redis-master.default.svc.cluster.local
export REDIS_PORT=6379
export REDIS_PASSWORD=your-secure-password
export REDIS_DATABASE=0
```

### 3. 数据库迁移（可选）

如果需要优化数据库索引，执行：
```sql
-- V2 和 V3 迁移文件已包含必要的索引优化
-- Flyway 会自动执行
```

### 4. 启动 Router 服务

```bash
# 第一个节点
java -jar harnax-session-router.jar

# 第二个节点（相同配置，自动加入集群）
java -jar harnax-session-router.jar
```

### 5. 验证部署

```bash
# 检查健康状态
curl http://router-node-1:8081/api/router/health

# 查看缓存指标
curl http://router-node-1:8081/api/router/metrics/cache

# 测试实例注册（应该被拒绝 - 使用域名）
curl -X POST "http://router-node-1:8081/api/router/instance/register?instanceId=test&host=example.com&port=8082"
# 预期返回: "Only IPv4 addresses are allowed for security reasons"

# 测试实例注册（应该成功 - 使用 IP）
curl -X POST "http://router-node-1:8081/api/router/instance/register?instanceId=test&host=10.0.1.100&port=8082"
# 预期返回: {"status":"registered","instanceId":"test"}
```

## 📊 监控与告警

### 关键指标

1. **Redis 连接池**：
   - `lettuce_pool_active`：活跃连接数
   - `lettuce_pool_idle`：空闲连接数

2. **缓存命中率**：
   - 通过 `/api/router/metrics/cache` 查看

3. **会话分布**：
   ```bash
   redis-cli SMEMBERS router:instances:all
   redis-cli GET router:session:session-123
   ```

### 告警规则

- Redis 连接失败率 > 1%
- 会话重路由失败率 > 5%
- 实例健康检查失败数 > 0

## ⚠️ 注意事项

### 1. 数据持久化

- MySQL 仍是权威数据源（Source of Truth）
- Redis 作为缓存层，重启后会自动从 MySQL 重建
- 建议启用 Redis AOF 持久化以防止数据丢失

### 2. 故障恢复

- **Redis 宕机**：服务降级，直接从 MySQL 查询（性能降低但可用）
- **MySQL 宕机**：Redis 缓存仍可提供读服务，但无法注册新实例

### 3. 兼容性

- 旧的 `MysqlInstanceRegistry` 和 `CaffeineIdempotencyService` 仍保留在代码中
- 通过 Spring 的 `@Primary` 注解自动选择 Redis 实现
- 如需回滚，删除 `@Primary` 或注释掉 Redis Bean 配置

### 4. 性能调优

根据实际负载调整以下参数：

```yaml
router:
  cache:
    instance-ttl-seconds: 2  # Redis 缓存 TTL（缩短以提高一致性）
  idempotency:
    ttl-seconds: 60          # 幂等性窗口（根据网络延迟调整）
  circuit-breaker:
    failure-threshold: 3     # 熔断阈值
    open-duration-ms: 30000  # 熔断恢复时间
```

## 🧪 测试建议

### 单元测试

运行现有测试确保功能正常：
```bash
mvn test -pl harnax-session-router
```

### 集成测试

1. **多节点会话粘性测试**：
   - 启动 2 个 router 节点
   - 发送请求到节点 A，记录路由到的 agent 实例
   - 发送相同 session 的请求到节点 B
   - 验证是否路由到相同的 agent 实例

2. **故障转移测试**：
   - 注册 2 个 agent 实例
   - 停止其中一个
   - 发送请求，验证自动切换到健康实例

3. **幂等性测试**：
   - 使用相同 requestId 发送两次请求
   - 验证第二次请求被拒绝

## 📝 总结

本次改造解决了以下核心问题：

| 问题 | 解决方案 |
|------|---------|
| 分布式环境下缓存不一致 | Redis 共享缓存 |
| 会话重路由竞态条件 | Redis 分布式锁 |
| 跨节点幂等性失效 | Redis SET NX 原子操作 |
| SSRF 安全漏洞 | IP 白名单 + 端口限制 |
| 熔断器状态不同步 | （可选）Redis 熔断器 |

**下一步优化方向**：
- 引入 Redis Cluster 提高可用性
- 添加读写分离（Redis Sentinel）
- 实现基于一致性哈希的会话路由（减少重路由）
- 添加分布式追踪（Zipkin/Jaeger）
