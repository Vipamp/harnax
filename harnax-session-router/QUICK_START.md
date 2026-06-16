# Harnax Session Router - 快速启动指南

## 🚀 5分钟部署

### 1. 环境准备
```bash
# 设置必需的环境变量
export ROUTER_API_KEY=$(openssl rand -hex 32)  # 生成强密钥
export DB_URL="jdbc:mysql://localhost:3306/harnax_router?useSSL=false"
export DB_USERNAME="root"
export DB_PASSWORD="your-password"
```

### 2. 构建与运行
```bash
cd harnax-session-router
mvn clean package -DskipTests
java -jar target/harnax-session-router-1.0.0-SNAPSHOT.jar
```

### 3. 验证部署
```bash
# 健康检查(需要API密钥)
curl -H "X-Api-Key: $ROUTER_API_KEY" http://localhost:8081/api/router/health

# 查看缓存统计
curl -H "X-Api-Key: $ROUTER_API_KEY" http://localhost:8081/api/router/metrics/cache
```

---

## 📋 核心API端点

### 实例管理
```bash
# 注册实例
curl -X POST "http://localhost:8081/api/router/instance/register?instanceId=inst-1&host=10.0.0.1&port=8082" \
  -H "X-Api-Key: $ROUTER_API_KEY"

# 心跳刷新
curl -X POST "http://localhost:8081/api/router/instance/heartbeat?instanceId=inst-1" \
  -H "X-Api-Key: $ROUTER_API_KEY"

# 优雅停机(drain模式)
curl -X POST "http://localhost:8081/api/router/instance/drain?instanceId=inst-1" \
  -H "X-Api-Key: $ROUTER_API_KEY"

# 注销实例
curl -X POST "http://localhost:8081/api/router/instance/unregister?instanceId=inst-1" \
  -H "X-Api-Key: $ROUTER_API_KEY"

# 列出所有实例
curl -H "X-Api-Key: $ROUTER_API_KEY" http://localhost:8081/api/router/instance/list
```

### 会话代理
```bash
# 代理聊天请求
curl -X POST http://localhost:8081/api/router/agent/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "session-123",
    "message": "Hello",
    "requestId": "req-456"
  }'

# 代理SSE流式请求
curl -X POST http://localhost:8081/api/router/agent/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "session-123",
    "message": "Stream me"
  }'
```

---

## 🔧 配置调优

### 关键参数(application.yml)
```yaml
router:
  health:
    heartbeat-timeout-ms: 30000    # 实例超时判定时间
    check-interval-ms: 5000        # 健康检查频率
  proxy:
    connect-timeout-ms: 5000       # 连接超时
    read-timeout-ms: 60000         # 读取超时(AI推理较长)
    stream-timeout-minutes: 15     # SSE流总超时
    failover-max-retries: 2        # 故障转移重试次数
  cache:
    instance-ttl-seconds: 3        # 实例缓存TTL
  security:
    enabled: true                  # 启用认证
    api-key: ${ROUTER_API_KEY}     # API密钥
```

### JVM参数推荐
```bash
export JAVA_OPTS="-Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError"
```

---

## 🐛 故障排查

### 问题1: 401 Unauthorized
```bash
# 检查API密钥是否正确
echo $ROUTER_API_KEY | wc -c  # 应>=17(16字符+换行)

# 测试不带认证的请求(应返回401)
curl http://localhost:8081/api/router/health
```

### 问题2: 路由到已宕机实例
```bash
# 检查缓存状态
curl -H "X-Api-Key: $ROUTER_API_KEY" \
  http://localhost:8081/api/router/metrics/cache

# 手动触发实例注销
curl -X POST "http://localhost:8081/api/router/instance/unregister?instanceId=bad-inst" \
  -H "X-Api-Key: $ROUTER_API_KEY"
```

### 问题3: 数据库连接池耗尽
```bash
# 查看HikariCP指标
curl http://localhost:8081/actuator/metrics/hikaricp.connections.active

# 检查慢查询
mysql -e "SHOW PROCESSLIST;"
```

### 问题4: 故障转移频繁触发
```bash
# 检查健康实例数量
curl -H "X-Api-Key: $ROUTER_API_KEY" \
  http://localhost:8081/api/router/health

# 查看应用日志
tail -f /var/log/harnax/router.log | grep -i failover
```

---

## 📊 监控指标

### Prometheus采集
```yaml
scrape_configs:
  - job_name: 'harnax-router'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['router-host:8081']
```

### 关键指标
- `router_proxy_duration_seconds` - 代理请求延迟
- `router_proxy_requests_total` - 请求总数(按status和endpoint)
- `router_failover_count_total` - 故障转移次数
- `cache_hits_total` / `cache_misses_total` - 缓存命中率
- `hikaricp_connections_active` - 数据库连接数

---

## 🔒 安全最佳实践

### ✅ DO
- 使用强API密钥(>=32字符)
- 启用TLS/SSL for数据库连接
- 定期轮换API密钥
- 监控异常访问模式
- 限制注册的主机地址范围

### ❌ DON'T
- 不要使用默认API密钥
- 不要暴露router到公网
- 不要禁用安全检查
- 不要注册私有IP地址(除非在内网)
- 不要忽略告警

---

## 📚 更多文档

- [完整优化指南](OPTIMIZATION_GUIDE.md)
- [安全审计报告](SECURITY_AUDIT_REPORT.md)
- [测试用例](src/test/kotlin/)

---

**Happy Routing! 🎯**
