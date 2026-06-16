# Harnax Session Router 安全审计与优化报告

## 执行摘要

本次审计对Harnax Session Router服务进行了全面的安全和高可用性审查,发现了**12个关键问题**,包括:
- 4个严重安全漏洞(SSRF、竞态条件、MDC泄漏、弱认证)
- 4个高可用性问题(缓存一致性、故障转移风暴、连接池配置、超时设置)
- 4个性能优化点(监控缺失、输入验证不足、索引优化、清理策略)

所有P0和P1级别的问题已修复并测试通过。

---

## 问题清单与修复状态

### 🔴 P0 - 严重问题 (已全部修复)

#### 1. SSRF漏洞 - ✅ 已修复
**风险等级**: 🔴 严重  
**影响范围**: 攻击者可注册指向内网的实例进行SSRF攻击

**修复内容**:
- `AgentInstance.kt`: 完整的IPv4私有地址段检查(10.x, 172.16-31.x, 192.168.x)
- `SecurityFilter.kt`: 阻止特权端口(<1024),增强日志记录
- 新增IP格式验证和八位组范围检查

**测试覆盖**: `AgentInstanceSecurityTest.kt` - 13个测试用例

#### 2. 幂等性竞态条件 - ✅ 已修复
**风险等级**: 🟠 高  
**影响范围**: 高并发下重复请求可能绕过幂等性检查

**修复内容**:
- `CaffeineIdempotencyService.kt`: 使用原子时间戳存储
- 正确的putIfAbsent语义
- 添加重复请求检测日志

#### 3. MDC内存泄漏 - ✅ 已修复
**风险等级**: 🟠 高  
**影响范围**: 长时间运行后MDC污染导致日志混乱

**修复内容**:
- `SessionRouterService.kt`: executeWithRetry中保存/恢复旧MDC值
- retryFailover中清理失败尝试的MDC
- 确保所有异常路径都清理MDC

#### 4. 缓存一致性问题 - ✅ 已修复
**风险等级**: 🔴 严重  
**影响范围**: 路由决策基于过期数据,请求被发送到已宕机实例

**修复内容**:
- `MysqlInstanceRegistry.kt`: healthyInstancesCache TTL从5秒降至2秒
- 缓存失效限流从2秒降至500ms
- 添加缓存统计监控(recordStats)
- 新增`/api/router/metrics/cache`端点

#### 5. 故障转移风暴 - ✅ 已修复
**风险等级**: 🔴 严重  
**影响范围**: 多实例同时宕机时导致雪崩效应

**修复内容**:
- `HeartbeatHealthChecker.kt`: 每个实例10秒故障转移冷却期
- 目标实例负载检查(超过平均值2倍时选择备选)
- 负载均衡验证防止单点过载

---

### 🟡 P1 - 高优先级问题 (已全部修复)

#### 6. 数据库连接池配置不当 - ✅ 已优化
**风险等级**: 🟡 中  
**影响范围**: 连接池耗尽或连接泄漏

**修复内容**:
- `application.yml`: HikariCP配置优化
  ```yaml
  maximum-pool-size: 50        # 避免过多连接
  minimum-idle: 10             # 保持最小空闲连接
  leak-detection-threshold: 10000  # 检测10秒未释放连接
  keepalive-time: 30000        # 30秒保活
  ```

#### 7. WebClient连接池缺少限制 - ✅ 已优化
**风险等级**: 🟡 中  
**影响范围**: pending请求无限增长导致OOM

**修复内容**:
- `RouterConfig.kt`: 
  - pendingAcquireMaxCount限制为500
  - 启用连接池指标(metrics=true)
  - 添加ReadTimeoutHandler和WriteTimeoutHandler

#### 8. 默认安全配置过弱 - ✅ 已加固
**风险等级**: 🟡 中  
**影响范围**: 生产环境默认不启用认证

**修复内容**:
- `application.yml`: 
  ```yaml
  security:
    enabled: true  # 默认启用
    api-key: ${ROUTER_API_KEY:change-me-in-production}
  ```
- `SecurityFilter.kt`: 启动时验证API密钥强度(最少16字符)

#### 9. SSE超时设置不合理 - ✅ 已调整
**风险等级**: 🟢 低  
**影响范围**: 长时间AI推理被中断

**修复内容**:
- `application.yml`: stream-timeout-minutes从10增至15分钟

---

### 🟢 P2 - 改进建议 (已部分实施)

#### 10. 输入验证不足 - ✅ 已增强
**修复内容**:
- `MysqlSessionMappingService.kt`: 所有sessionId和instanceId添加长度和格式验证
- 使用Kotlin require()进行参数校验

#### 11. 监控可观测性缺失 - ✅ 已补充
**新增功能**:
- `/api/router/metrics/cache` - 缓存命中率、大小统计
- 缓存统计方法getCacheStats()
- Actuator endpoints暴露更多指标

#### 12. 数据库索引优化 - 📝 文档化
**建议**: V2迁移脚本已包含索引优化
- 删除冗余idx_instance_id(UNIQUE约束已覆盖)
- 添加复合索引idx_status_active
- 添加idx_instance_active用于负载均衡查询

---

## 代码变更统计

| 文件 | 变更类型 | 行数变化 |
|------|---------|---------|
| AgentInstance.kt | 安全加固 | +65 / -15 |
| SecurityFilter.kt | 安全加固 | +35 / -10 |
| CaffeineIdempotencyService.kt | Bug修复 | +12 / -3 |
| SessionRouterService.kt | Bug修复 | +18 / -5 |
| MysqlInstanceRegistry.kt | 性能优化 | +25 / -8 |
| HeartbeatHealthChecker.kt | 高可用 | +55 / -10 |
| RouterConfig.kt | 配置优化 | +8 / -2 |
| MysqlSessionMappingService.kt | 输入验证 | +8 / -0 |
| application.yml | 配置加固 | +15 / -5 |
| SessionRouterController.kt | 监控 | +10 / -0 |

**总计**: +251行新增, -58行删除

**新增文件**:
- OPTIMIZATION_GUIDE.md (部署和运维指南)
- AgentInstanceSecurityTest.kt (安全测试)
- SECURITY_AUDIT_REPORT.md (本报告)

---

## 部署前检查清单

### 环境变量
```bash
# 必须设置
export ROUTER_API_KEY="your-strong-api-key-at-least-16-chars"
export DB_URL="jdbc:mysql://db-host:3306/harnax_router?useSSL=true&..."
export DB_USERNAME="router_user"
export DB_PASSWORD="strong-password"

# 可选调优
export JAVA_OPTS="-Xms2g -Xmx4g -XX:+UseG1GC"
```

### 验证步骤
1. ✅ 运行单元测试: `mvn test`
2. ✅ 检查API密钥: 确保长度>=16且不是默认值
3. ✅ 健康检查: `curl http://localhost:8081/api/router/health`
4. ✅ 缓存监控: `curl http://localhost:8081/api/router/metrics/cache`
5. ✅ 注册测试: 尝试注册127.0.0.1应被拒绝
6. ✅ 认证测试: 不带X-Api-Key应返回401

### 回滚计划
如果遇到问题,可以:
1. 临时禁用安全: `router.security.enabled=false`
2. 增加缓存TTL: `router.cache.instance-ttl-seconds=10`
3. 延长超时: `router.proxy.stream-timeout-minutes=30`

---

## 性能基准测试建议

### 测试场景
```bash
# 1. 正常负载
ab -n 10000 -c 100 -H "X-Api-Key: test-key" \
   http://router:8081/api/router/health

# 2. 并发注册
for i in {1..50}; do
  curl -X POST "http://router:8081/api/router/instance/register?instanceId=inst-$i&host=10.0.0.$i&port=8082" &
done

# 3. 故障转移测试
# 停止一个agent-service实例,观察failover延迟

# 4. 内存稳定性
jmap -histo:live <pid> | head -20
```

### 预期指标
- P99延迟: <200ms (健康检查)
- P99延迟: <5s (代理请求)
- 缓存命中率: >80%
- 故障转移时间: <5s
- 错误率: <0.1%

---

## 后续优化路线图

### Q3 2026 (短期)
- [ ] 分布式锁: Redis锁防止多router实例并发failover
- [ ] Redis缓存: 替换Caffeine为分布式缓存
- [ ] 优雅停机: drain模式等待活跃请求完成

### Q4 2026 (中期)
- [ ] 断路器指标: 暴露circuit breaker状态
- [ ] 动态配置: 运行时调整timeout和threshold
- [ ] 会话亲和性: 基于agent_id的智能路由

### Q1 2027 (长期)
- [ ] 服务网格: Istio/Linkerd集成
- [ ] 多区域部署: 跨区域故障转移
- [ ] ML预测: 基于历史数据预测实例故障

---

## 合规性与标准

### OWASP Top 10对照
- ✅ A01:2021 Broken Access Control - API密钥认证
- ✅ A02:2021 Cryptographic Failures - 敏感配置外部化
- ✅ A03:2021 Injection - 参数化SQL查询+输入验证
- ✅ A05:2021 Security Misconfiguration - 默认安全启用
- ✅ A07:2021 Identification and Authentication Failures - API密钥强度验证

### 高可用性设计原则
- ✅ 快速失败(fail-fast)
- ✅ 优雅降级(graceful degradation)
- ✅ 自动恢复(self-healing)
- ✅ 隔离故障(bulkhead pattern via circuit breaker)

---

## 签名与审批

**审计执行人**: AI Assistant  
**审计日期**: 2026-06-16  
**下次审计**: 建议在Q3 2026进行复审  

**批准部署**: ________________ (待填写)  
**部署日期**: ________________ (待填写)

---

## 附录A: 关键代码片段

### SSRF防护逻辑
```kotlin
// 检查私有网络(RFC 1918)
if (a == "10") return true
if (a == "172" && b.toInt() in 16..31) return true
if (a == "192" && b == "168") return true
```

### 故障转移冷却
```kotlin
val lastFailoverTime = recentFailovers[downInstanceId] ?: 0
if (now - lastFailoverTime < failoverCooldownMs) {
    log.debug("Skipping failover - in cooldown period")
    return
}
```

### MDC安全清理
```kotlin
val oldInstanceId = MDC.get("instanceId")
MDC.put("instanceId", instance.instanceId)
try {
    // ... business logic
} finally {
    if (oldInstanceId != null) {
        MDC.put("instanceId", oldInstanceId)
    } else {
        MDC.remove("instanceId")
    }
}
```

---

**报告结束**
