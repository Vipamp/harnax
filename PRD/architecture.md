# VIPClaw 架构设计文档

## 1. 系统架构总览

### 1.1 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        前端层 (Web UI)                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ React 18     │  │ Ant Design 5 │  │ Umi 4        │      │
│  │ TypeScript 5 │  │ Pro Components│  │ Router       │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP/REST API
┌──────────────────────────▼──────────────────────────────────┐
│                      后端层 (Admin API)                       │
│  ┌──────────────────────────────────────────────────────┐  │
│  │           Spring Boot 3.5.8 + Kotlin 2.2.20           │  │
│  ├────────────┬────────────┬────────────┬──────────────┤  │
│  │ Controller │  Service   │  Mapper    │   Entity     │  │
│  │   Layer    │   Layer    │   Layer    │   Layer      │  │
│  └────────────┴────────────┴────────────┴──────────────┘  │
│  ┌────────────┬────────────┬────────────┬──────────────┐  │
│  │   Auth     │   JWT      │  Validation│  Exception   │  │
│  │  Filter    │   Util     │  Config    │  Handler     │  │
│  └────────────┴────────────┴────────────┴──────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                 │
┌────────▼──────┐ ┌───────▼───────┐ ┌───────▼───────┐
│  Agent Core   │ │  Channel      │ │  Common       │
│  (AgentScope) │ │  Adaptors     │ │  Utils        │
└────────┬──────┘ └───────┬───────┘ └───────┬───────┘
         │                 │                 │
┌────────▼─────────────────▼─────────────────▼───────┐
│                  数据访问层                          │
│  ┌──────────────┐  ┌──────────────┐                │
│  │  MyBatis     │  │  HikariCP    │                │
│  │  Mappers     │  │  Pool        │                │
│  └──────────────┘  └──────────────┘                │
└────────────────────────┬────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────┐
│                  数据库层                             │
│              MySQL 8.0 (vipclaw)                     │
└─────────────────────────────────────────────────────┘
```

### 1.2 模块依赖关系

```
vipclaw (Parent POM)
├── vipclaw-common          # 公共模块（分页、异常、工具类）
├── vipclaw-agent           # Agent 核心模块
│   └── vipclaw-agent-core  # AgentScope 集成
├── vipclaw-channel         # 渠道接入模块
│   ├── WeCom Adaptor       # 企业微信
│   ├── Feishu Adaptor      # 飞书
│   ├── DingTalk Adaptor    # 钉钉
│   └── HTTP Adaptor        # 通用 Webhook
└── vipclaw-admin           # 管理后台（主应用）
    ├── Controller Layer    # REST API 端点
    ├── Service Layer       # 业务逻辑
    ├── Mapper Layer        # 数据访问
    ├── Entity Layer        # 实体类
    ├── DTO Layer           # 数据传输对象
    ├── Config              # 配置类
    ├── Job                 # 定时任务
    └── Util                # 工具类
```

---

## 2. 核心模块设计

### 2.1 Agent 模块设计

#### 2.1.1 Agent 生命周期

```
┌─────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│  创建    │────▶│  配置    │────▶│  启用    │────▶│  运行    │
│  Create  │     │  Config  │     │  Enable  │     │  Running │
└─────────┘     └──────────┘     └──────────┘     └──────────┘
                                                           │
              ┌─────────┐     ┌──────────┐                │
              │  删除    │◀────│  禁用    │◀───────────────┘
              │  Delete  │     │  Disable │
              └─────────┘     └──────────┘
```

#### 2.1.2 Agent 配置结构

```kotlin
data class AgentSpec(
    val id: Long,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val modelId: Long,
    val mcpServices: List<McpConfig>,      // MCP 服务配置
    val skills: List<SkillConfig>,          // 技能配置
    val enableMetaTool: Boolean?,           // 是否启用元工具
    val contextForTools: List<Any>,         // 工具上下文
    val maxIterNum: Int = 10               // 最大迭代次数
)

data class McpConfig(
    val mcpId: Long,
    val isAsync: Boolean = false,
    val skipIfMissing: Boolean = false
)

data class SkillConfig(
    val skillId: Long,
    val skipIfMissing: Boolean = false
)
```

#### 2.1.3 Agent 执行流程

```
用户消息
  │
  ▼
┌─────────────────────┐
│  ReActAgent         │
│  (AgentScope)       │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│  ChatModel          │
│  (LLM API)          │
└─────────┬───────────┘
          │
          ▼
    {需要工具?}
     /       \
   Yes       No
    │         │
    ▼         ▼
┌───────┐  ┌────────┐
│Toolkit│  │直接回复│
└───┬───┘  └────────┘
    │
    ▼
┌─────────────────────┐
│  MCP Client / Skill │
└─────────┬───────────┘
          │
          ▼
    工具执行结果
          │
          ▼
    循环直到完成
```

### 2.2 数据权限控制设计

#### 2.2.1 数据可见性规则

```sql
-- 所有实体的查询都遵循以下规则
SELECT * FROM entity_table
WHERE active = 1 
  AND (is_public = 1 OR creator = #{currentUsername})
```

**权限矩阵**:

| 场景 | 创建人 | 其他用户 | 说明 |
|------|--------|---------|------|
| is_public=1, creator=A | ✅ 可见 | ✅ 可见 | 公开资源，所有人可见 |
| is_public=0, creator=A | ✅ 可见 | ❌ 不可见 | 私有资源，仅创建人可见 |
| active=0 | ❌ 不可见 | ❌ 不可见 | 已删除资源，任何人不可见 |

#### 2.2.2 实现方式

**后端 Service 层**:
```kotlin
fun getEntityPage(currentUsername: String, ...): Page<Entity> {
    return mapper.selectList(
        currentUsername = currentUsername,
        ...
    )
}
```

**Mapper XML**:
```xml
<select id="selectList" resultType="Entity">
    SELECT * FROM entity_table
    WHERE active = 1
    AND (is_public = 1 OR creator = #{currentUsername})
    <if test="name != null">
        AND name LIKE CONCAT('%', #{name}, '%')
    </if>
</select>
```

### 2.3 Token 统计设计

#### 2.3.1 统计维度

```
Token 统计
├── 按时间
│   ├── 按天统计
│   ├── 按周统计
│   └── 按月统计
├── 按实体
│   ├── 按 Agent
│   ├── 按模型
│   ├── 按用户
│   └── 按会话
└── 按类型
    ├── Prompt Tokens（输入）
    ├── Completion Tokens（输出）
    └── Total Tokens（总计）
```

#### 2.3.2 数据模型

```kotlin
data class TokenStats(
    val id: Long,
    val sessionId: String,
    val agentId: Long,
    val modelId: Long,
    val userId: Long,
    val promptTokens: Int,          // 输入 Token 数
    val completionTokens: Int,      // 输出 Token 数
    val totalTokens: Int,           // 总 Token 数
    val cost: BigDecimal,           // 费用（元）
    val createTime: LocalDateTime
)
```

#### 2.3.3 费用计算

```kotlin
// 费用 = (总 Token 数 / 1,000,000) × 价格
fun calculateCost(totalTokens: Int, pricePerMillion: Double): BigDecimal {
    return BigDecimal(totalTokens)
        .divide(BigDecimal(1000000))
        .multiply(BigDecimal(pricePerMillion))
        .setScale(4, RoundingMode.HALF_UP)
}
```

### 2.4 Channel 适配器设计

#### 2.4.1 适配器模式

```kotlin
interface ChannelAdaptor {
    fun receive(message: ChannelMessage): SessionMessage
    fun send(message: SessionMessage): ChannelMessage
    fun verify(token: String): Boolean
}

class WeComAdaptor : ChannelAdaptor {
    override fun receive(message: ChannelMessage): SessionMessage {
        // 企业微信消息解析
    }
    
    override fun send(message: SessionMessage): ChannelMessage {
        // 企业微信消息发送
    }
}

class FeishuAdaptor : ChannelAdaptor { ... }
class DingTalkAdaptor : ChannelAdaptor { ... }
class HttpAdaptor : ChannelAdaptor { ... }
```

#### 2.4.2 消息流转

```
外部平台消息
  │
  ▼
┌──────────────┐
│ Channel      │
│ Adaptor      │
└──────┬───────┘
       │ 转换为统一格式
       ▼
┌──────────────┐
│ Session      │
│ Message      │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ Agent        │
│ Processing   │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ Channel      │
│ Adaptor      │
└──────┬───────┘
       │ 转换为平台格式
       ▼
   回复到外部平台
```

---

## 3. 数据库设计

### 3.1 表关系图

详见 PRD.md 中的 ER 图

### 3.2 核心表结构

#### 3.2.1 智能体表 (agent)

```sql
CREATE TABLE `agent` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `description` TEXT,
    `system_prompt` TEXT,
    `model_id` BIGINT(20),
    `mcp_list` TEXT,              -- JSON 格式：[{"id":1,"enable_skip":"false"}]
    `skill_list` TEXT,            -- 逗号分隔：1,2,3
    `owner` VARCHAR(100),
    `status` TINYINT(1) DEFAULT 1,
    `is_public` TINYINT(1) DEFAULT 0,
    `creator` VARCHAR(100),
    `active` TINYINT(1) DEFAULT 1,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_creator` (`creator`)
);
```

#### 3.2.2 Token 统计表 (token_stats)

```sql
CREATE TABLE `token_stats` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT,
    `session_id` VARCHAR(100),
    `agent_id` BIGINT(20),
    `model_id` BIGINT(20),
    `user_id` BIGINT(20),
    `prompt_tokens` INT DEFAULT 0,
    `completion_tokens` INT DEFAULT 0,
    `total_tokens` INT DEFAULT 0,
    `cost` DECIMAL(10, 4) DEFAULT 0.0000,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`),
    KEY `idx_agent_id` (`agent_id`),
    KEY `idx_create_time` (`create_time`)
);
```

### 3.3 索引策略

| 表名 | 索引字段 | 索引类型 | 用途 |
|-----|---------|---------|------|
| agent | name | UNIQUE | 防止重名 |
| agent | creator | INDEX | 按创建人查询 |
| model | provider_id | INDEX | 按供应商查询 |
| session | session_id | UNIQUE | 会话标识 |
| session | agent_id | INDEX | 查询 Agent 的会话 |
| token_stats | create_time | INDEX | 按日期范围查询 |
| token_stats | agent_id | INDEX | 按 Agent 统计 |
| channel | callback_key | UNIQUE | 回调标识 |

---

## 4. 安全设计

### 4.1 认证流程

```
┌─────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│  用户    │────▶│  输入    │────▶│  验证    │────▶│  生成    │
│  登录    │     │  账号    │     │  验证码  │     │  JWT     │
│  请求    │     │  密码    │     │  密码    │     │  Token   │
└─────────┘     └──────────┘     └──────────┘     └──────────┘
                                                            │
                                                            ▼
                                                    ┌──────────────┐
                                                    │  返回 Token  │
                                                    │  给前端      │
                                                    └──────────────┘
```

### 4.2 JWT Token 结构

```json
{
  "sub": "admin",
  "iat": 1682000000,
  "exp": 1682086400,
  "isAdmin": true,
  "username": "admin"
}
```

### 4.3 Token 黑名单机制

```sql
CREATE TABLE `sys_token_blacklist` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT,
    `token` VARCHAR(500) NOT NULL,
    `expire_time` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token` (`token`),
    KEY `idx_expire_time` (`expire_time`)
);
```

**失效流程**:
1. 用户登出，Token 加入黑名单
2. 请求拦截器检查 Token 是否在黑名单
3. 定期清理已过期的黑名单记录

### 4.4 密码加密

```kotlin
// 使用 BCrypt 加密
fun encodePassword(rawPassword: String): String {
    return BCrypt.hashpw(rawPassword, BCrypt.gensalt())
}

fun verifyPassword(rawPassword: String, encodedPassword: String): Boolean {
    return BCrypt.checkpw(rawPassword, encodedPassword)
}
```

---

## 5. 性能优化

### 5.1 数据库优化

**分页查询优化**:
```xml
<!-- 使用 PageHelper 自动优化分页 -->
<select id="selectList" resultType="Entity">
    SELECT * FROM entity_table
    WHERE ...
    ORDER BY create_time DESC
</select>
```

**慢查询优化**:
- 所有 WHERE 条件字段建立索引
- 避免 SELECT *，只查询需要的字段
- 大表查询增加 LIMIT 限制

### 5.2 缓存策略

**可缓存的数据**:
- 模型列表（变更频率低）
- 模型供应商列表（变更频率低）
- 公开的 Agent 列表（变更频率低）

**不适合缓存的数据**:
- 会话消息（实时性要求高）
- Token 统计（实时累加）
- 用户权限（安全性要求高）

### 5.3 SSE 连接管理

```kotlin
// 连接池管理
val sseConnections: ConcurrentHashMap<String, SseEmitter> = ConcurrentHashMap()

// 心跳机制
fun sendHeartbeat() {
    timer.scheduleAtFixedRate(0, 30000) {
        sseConnections.values.forEach { emitter ->
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"))
            } catch (e: Exception) {
                // 连接已断开，移除
                sseConnections.values.remove(emitter)
            }
        }
    }
}
```

---

## 6. 部署架构

### 6.1 开发环境

```
┌──────────────┐
│  前端开发    │  localhost:8000
│  (Umi Dev)   │
└──────┬───────┘
       │ 代理 /api
       ▼
┌──────────────┐
│  后端开发    │  localhost:8080
│  (Spring     │
│   Boot)      │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│  MySQL 8.0   │  localhost:3306
│  (本地)      │
└──────────────┘
```

### 6.2 生产环境（建议）

```
┌──────────────────────────────────────┐
│           Nginx 反向代理              │
│         (HTTPS + 负载均衡)            │
└──────────┬───────────────────────────┘
           │
    ┌──────┴──────┐
    │             │
    ▼             ▼
┌────────┐  ┌────────┐
│ 前端 1 │  │ 前端 2 │  Cloudflare Pages
│ (静态) │  │ (静态) │  或 CDN
└────────┘  └────────┘
    
┌──────────────────────────────────────┐
│         后端应用服务器                │
│   Spring Boot (Jar) × 2 实例         │
└──────────┬───────────────────────────┘
           │
    ┌──────┴──────┐
    │             │
    ▼             ▼
┌────────┐  ┌────────┐
│ MySQL  │  │ Redis  │  (可选缓存)
│ 主从   │  │        │
└────────┘  └────────┘
```

### 6.3 Docker 部署（可选）

```dockerfile
# Dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/vipclaw-admin.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```yaml
# docker-compose.yml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: vipclaw
    ports:
      - "3306:3306"
    
  backend:
    build: .
    ports:
      - "8080:8080"
    depends_on:
      - mysql
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/vipclaw
```

---

## 7. 测试策略

### 7.1 测试金字塔

```
        /\
       /  \
      / E2E \         端到端测试 (10%)
     /______\
    /        \
   / Integration\   集成测试 (20%)
  /______________\
 /                \
/    Unit Tests    \  单元测试 (70%)
--------------------
```

### 7.2 集成测试（Testcontainers）

```kotlin
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AgentServiceImplIntegrationTest {
    
    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("vipclaw_test")
            .withInitScript("schema-test.sql")
    }
    
    @Test
    fun `createAgent should create successfully`() {
        // 测试逻辑
    }
}
```

### 7.3 测试覆盖率目标

| 模块 | 行覆盖率 | 分支覆盖率 |
|-----|---------|-----------|
| Service 层 | ≥ 80% | ≥ 70% |
| Controller 层 | ≥ 70% | ≥ 60% |
| Mapper 层 | ≥ 90% | ≥ 80% |
| Util 层 | ≥ 90% | ≥ 85% |

---

## 8. 监控与日志

### 8.1 日志级别

| 级别 | 使用场景 | 示例 |
|-----|---------|------|
| ERROR | 系统错误、异常 | 数据库连接失败、Agent 执行异常 |
| WARN | 警告信息、降级 | MCP 服务不可用、Token 即将过期 |
| INFO | 关键业务操作 | 用户登录、Agent 创建、Token 消耗 |
| DEBUG | 调试信息 | SQL 执行、API 调用详情 |

### 8.2 关键指标监控

- **业务指标**:
  - 活跃 Agent 数
  - 会话总数
  - Token 消耗量
  - 费用统计
  
- **技术指标**:
  - 接口响应时间（P50/P95/P99）
  - 错误率
  - 并发连接数
  - JVM 内存使用

---

## 9. 未来演进方向

### 9.1 短期（V1.2-V2.0）

1. **性能优化**: 引入 Redis 缓存、数据库读写分离
2. **功能增强**: Agent 模板市场、批量操作、费用预警
3. **体验优化**: 移动端适配、多语言支持

### 9.2 中期（V2.0-V3.0）

1. **高可用**: 集群部署、负载均衡、故障自动转移
2. **AI 能力增强**: 多 Agent 协作、Agent 自学习
3. **生态建设**: 插件市场、API 开放平台

### 9.3 长期（V3.0+）

1. **云原生**: Kubernetes 部署、自动扩缩容
2. **智能化**: AI 辅助配置、智能推荐模型和技能
3. **企业级**: SSO 集成、审计合规、多租户

---

*文档结束 - VIPClaw 架构设计文档 V1.0*
