# Harnax Session Router 优化指南

## 已实施的优化

### 1. 安全加固 (P0)

#### SSRF漏洞修复
- **位置**: `AgentInstance.kt`
- **改进**: 
  - 完整的IPv4私有地址段检查(10.x, 172.16-31.x, 192.168.x)
  - IPv6本地链接地址检查
  - 元数据服务端点阻止列表
  - IP格式验证和八位组范围检查

#### 输入验证增强
- **位置**: `SecurityFilter.kt`, `MysqlSessionMappingService.kt`
- **改进**:
  - 注册时阻止特权端口(<1024)
  - 所有sessionId和instanceId长度限制
  - 详细的拒绝日志记录

#### 幂等性竞态条件修复
- **位置**: `CaffeineIdempotencyService.kt`
- **改进**:
  - 使用原子时间戳存储
  - 正确的putIfAbsent语义
  - 重复请求检测日志

### 2. 高可用性提升 (P0)

#### 缓存一致性优化
- **位置**: `MysqlInstanceRegistry.kt`
- **改进**:
  - healthyInstancesCache TTL从5秒降至2秒
  - 缓存失效限流从2秒降至500ms
  - 添加缓存统计监控(recordStats)
  - 新增/metrics/cache端点

#### MDC内存泄漏修复
- **位置**: `SessionRouterService.kt`
- **改进**:
  - executeWithRetry中保存和恢复旧MDC值
  - retryFailover中清理失败尝试的MDC
  - 确保所有异常路径都清理MDC

#### 故障转移风暴防护
- **位置**: `HeartbeatHealthChecker.kt`
- **改进**:
  - 每个实例10秒故障转移冷却期
  - 目标实例负载检查(超过平均值2倍时选择备选)
  - 负载均衡验证防止雪崩

### 3. 资源配置优化 (P1)

#### 数据库连接池
- **位置**: `application.yml`
- **配置**:
  ```yaml
  hikari:
    maximum-pool-size: 50        # 从默认降低,避免过多连接
    minimum-idle: 10             # 保持最小空闲连接
    connection-timeout: 5000     # 5秒获取连接超时
    leak-detection-threshold: 10000  # 检测10秒未释放连接
    keepalive-time: 30000        # 30秒保活
  ```

#### WebClient连接池
- **位置**: `RouterConfig.kt`
- **改进**:
  - pendingAcquireMaxCount限制为500
  - 启用连接池指标(metrics=true)
  - 添加ReadTimeoutHandler和WriteTimeoutHandler
  - 后台连接清理(30秒)

#### SSE超时调整
- **位置**: `application.yml`
- **改进**: stream-timeout-minutes从10增至15分钟

### 4. 监控和可观测性

#### 新增监控端点
- `GET /api/router/metrics/cache` - 缓存命中率、大小统计
- `GET /api/router/health` - 健康实例数量
- Actuator endpoints: `/actuator/prometheus`, `/actuator/metrics`

#### 关键指标
- router.proxy.duration - 代理请求延迟
- router.proxy.requests - 代理请求计数(按endpoint和status)
- router.failover.count - 故障转移次数
- Cache hitRate/missRate - 缓存性能

## 部署建议

### 环境变量配置

生产环境必须设置:
```bash
export ROUTER_API_KEY="your-strong-api-key-at-least-16-chars"
export DB_URL="jdbc:mysql://db-host:3306/harnax_router?useSSL=true&..."
export DB_USERNAME="router_user"
export DB_PASSWORD="strong-password"
```

### JVM参数推荐
```bash
-Xms2g -Xmx4g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/harnax/heapdump.hprof
```

### Kubernetes资源限制
```yaml
resources:
  requests:
    memory: "2Gi"
    cpu: "1000m"
  limits:
    memory: "4Gi"
    cpu: "2000m"
livenessProbe:
  httpGet:
    path: /api/router/health
    port: 8081
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /api/router/health
    port: 8081
  initialDelaySeconds: 10
  periodSeconds: 5
```

## 后续优化建议

### 短期(P1)
1. **分布式锁**: 多router实例时使用Redis锁防止并发failover
2. **会话亲和性优化**: 基于agent_id的智能路由
3. **优雅停机**: drain模式下等待活跃请求完成

### 中期(P2)
1. **Redis缓存**: 替换Caffeine为分布式缓存
2. **断路器指标**: 暴露circuit breaker状态到metrics
3. **动态配置**: 支持运行时调整timeout和threshold

### 长期(P3)
1. **服务网格**: 考虑Istio/Linkerd处理mTLS和流量管理
2. **多区域部署**: 跨区域故障转移
3. **机器学习预测**: 基于历史数据预测实例故障

## 测试建议

### 压力测试场景
```bash
# 1. 正常负载测试
ab -n 10000 -c 100 http://router:8081/api/router/health

# 2. 故障转移测试
# 停止一个agent-service实例,观察failover行为

# 3. 缓存一致性测试
# 快速注册/注销实例,验证路由决策更新速度

# 4. 内存泄漏测试
jmap -histo:live <pid> | head -20
```

### 混沌工程
- 随机杀死agent-service实例
- 网络分区模拟
- 数据库连接延迟注入
- CPU/内存压力测试

## 告警规则

Prometheus告警示例:
```yaml
groups:
- name: router-alerts
  rules:
  - alert: HighErrorRate
    expr: rate(router_proxy_requests_total{status="error"}[5m]) > 0.1
    for: 5m
    annotations:
      summary: "Router error rate > 10%"
  
  - alert: LowHealthyInstances
    expr: router_healthy_instances < 2
    for: 2m
    annotations:
      summary: "Less than 2 healthy instances"
  
  - alert: HighCacheMissRate
    expr: rate(cache_misses_total[5m]) / rate(cache_requests_total[5m]) > 0.5
    for: 10m
    annotations:
      summary: "Cache miss rate > 50%"
  
  - alert: CircuitBreakerOpen
    expr: circuit_breaker_state{state="OPEN"} == 1
    for: 1m
    annotations:
      summary: "Circuit breaker is OPEN"
```

## 故障排查

### 常见问题

**问题1**: 请求被路由到已宕机的实例
- 检查: `/api/router/metrics/cache` 的healthyCacheSize
- 原因: 缓存TTL内实例宕机
- 解决: 等待最多2秒缓存过期,或手动触发实例注销

**问题2**: 故障转移后大量超时
- 检查: 目标实例的session count是否过载
- 原因: failover storm导致单实例压力过大
- 解决: 已实施负载检查和冷却机制

**问题3**: API密钥认证失败
- 检查: 环境变量ROUTER_API_KEY是否正确设置
- 检查: 客户端是否在header中包含`X-Api-Key`
- 日志: 查看SecurityFilter的警告日志

**问题4**: 数据库连接池耗尽
- 检查: HikariCP metrics (`/actuator/metrics/hikaricp.connections`)
- 原因: 连接泄漏或查询过慢
- 解决: 检查leak detection日志,优化慢查询

## 变更日志

### 2026-06-16
- ✅ 修复SSRF漏洞 - 完整IP验证
- ✅ 修复竞态条件 - 原子幂等性操作
- ✅ 修复MDC内存泄漏
- ✅ 优化缓存TTL和失效策略
- ✅ 添加故障转移速率限制
- ✅ 优化数据库和WebClient连接池
- ✅ 启用默认安全认证
- ✅ 添加缓存监控端点
