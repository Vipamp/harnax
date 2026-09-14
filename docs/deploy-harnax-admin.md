# harnax-admin 部署文档

## 服务概述

harnax-admin 是管理后台服务，提供用户管理、API Key 管理、会话管理、Agent 配置等功能。

- **端口**: 8080
- **数据库**: harnax_admin (MySQL)
- **认证方式**: 独立 JWT (JwtAuthenticationFilter)，不使用 UnifiedAuth
- **Flyway**: 自动建表 (classpath:db/migration)

---

## 系统部署总览

| 服务 | 本地模式外部依赖 | 集群模式外部依赖 | 多实例就绪 |
|------|----------------|----------------|-----------|
| harnax-admin | MySQL | MySQL | 否（有状态组件） |
| harnax-session-router | 无（SQLite + 内存） | MySQL + Redis | 是 |
| harnax-agent-service | MySQL + Docker | MySQL + Docker + MinIO | 是 |
| harnax-webui | 无（静态文件） | 无 | 是 |

---

## 环境依赖

| 组件 | 本地模式 | 集群模式 | 说明 |
|------|---------|---------|------|
| JDK 21 | 必须 | 必须 | 运行时 |
| MySQL 8.0 | 必须 | 必须 | 主数据库 |
| Redis | 不需要 | 不需要 | admin 不使用 Redis |
| nginx | 不需要 | 必须 | 多实例负载均衡 |

---

## 数据库初始化

首次部署前需确保数据库已创建：

```sql
CREATE DATABASE IF NOT EXISTS harnax_admin
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

Flyway 会在服务启动时自动执行 `db/migration` 下的建表脚本，无需手动导入 SQL。

---

## 环境变量说明

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/harnax_admin?...` | MySQL 连接串 |
| `SPRING_DATASOURCE_USERNAME` | `root` | 数据库用户名 |
| `SPRING_DATASOURCE_PASSWORD` | `123456` | 数据库密码 |
| `SESSION_JDBC_URL` | 同 SPRING_DATASOURCE_URL | 会话数据库连接串 |
| `SESSION_DATABASE_NAME` | `harnax_admin` | 会话数据库名 |
| `SESSION_USERNAME` | `root` | 会话数据库用户名 |
| `SESSION_PASSWORD` | `123456` | 会话数据库密码 |
| `LOCAL_TMP_DIR` | `/home/harnax/skills` | 本地技能 / 上传工作目录（compose 把它挂成 `admin-skills` 卷） |
| `ADMIN_INTERNAL_API_SECRET` | `change-me-in-production-min-32-chars!!` | 内部 API 认证密钥 (>=32字符) |
| `HARNAX_AES_SECRET_KEY` | `change-me-32-chars-secret-key!!` | 所有凭据的落库加密密钥（配置项 `harnax.aes.secret-key`），**必须正好 32 字符**：`openssl rand -base64 48 \| tr -d '/+=' \| cut -c1-32`。默认值就写在仓库里，留着它等于任何读得到仓库的人都能解开这些密文。**先设再填凭据**：换密钥不会重新加密任何历史行，旧密文一律解不开（MCP headers / envParams、工具 HTTP headers、OAuth client_secret 与每个用户的 access / refresh token 都在这条范围内）。第十八轮之前这一项在 `docker-new/docker-compose.yml` 里根本不存在，只能靠代码默认值 |
| `HARNAX_MCP_STDIO_ENABLED` | `false` | 是否允许 stdio 类型的 MCP 服务（配置项 `harnax.mcp.stdio-enabled`；同一个变量也决定 agent 侧的 `harness.mcp-stdio-enabled`）。关闭时新建 stdio 与从网络型切进 stdio 都被拒，存量行仍可编辑但永不下发；开启意味着允许在 agent-service 容器里按表单填的命令起进程，而那个容器挂着宿主 Docker socket |
| `FLYWAY_ENABLED` | `true` | 是否启用 Flyway 建表 |
| `SWAGGER_ENABLED` | `true` | 是否启用 Swagger 文档 (生产建议关闭) |
| `HARNAX_ROUTER_URL` | `http://localhost:8081` | Router 服务地址 |
| `APP_BASE_URL` | `http://localhost:8080` | 本部署对外的访问 origin（对应配置项 `app.base-url`）。用于生成通道回调 URL。生产环境**必须改成上游真正能访问到的地址**（有 nginx 就填 nginx 的地址），否则回调会指向 localhost |
| `APP_FRONTEND_BASE_URL` | 空（回退到 `APP_BASE_URL`） | 浏览器所在的 origin（对应配置项 `app.frontend-base-url`）。MCP OAuth 的 `redirect_uri` 由它拼成，指向的是前端路由 `/mcp/oauth/callback`：走 nginx 时与 `APP_BASE_URL` 相同，本地开发要填 `http://localhost:8000`（SPA 的端口，不是 admin 的 8080）。AS 按精确字符串比对 `redirect_uri`，填错就是第一次授权被拒。**改了这一项，已存在的客户端登记不会自动改**——`mcp_oauth_client.callback_url` 存的是登记那一刻的值，要在「登记客户端」表单里把新的回跳地址填进去重存一次 |

> **安全提醒**: `ADMIN_INTERNAL_API_SECRET` 是其他服务 (channel/router/agent-service) 调用 admin 内部 API 的凭证，生产环境必须修改，且长度不少于 32 字符。此密钥需与 router 的配置保持一致。
>
> 第十八轮起这一项还有第二个后果：`JwtAuthenticationFilter` 不再接受**仍是占位默认值**的这串密钥作为 `/api/admin/**` 的业务接口凭据（那串字符在本仓库公开着）。沙箱里的 `harnax-cli` 正是用它访问这些接口的，所以留着默认值时它的调用会一律 401——admin 启动时有一条 WARN 说明这件事。换成真值即可，两边（admin 与 agent-service）读的是同一个 `.env`，重启后一致。

### 其余可覆盖但表里没列的变量

下面这些都在 `application.yml` 里写了 `${VAR:default}` 占位，因此**都可用环境变量覆盖**（此前本节把它们标成「不支持环境变量覆盖」是错的）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ADMIN_PORT` | `8080` | HTTP 监听端口（下文「端口与防火墙」按固定值写的，改这里要同步改） |
| `JWT_SECRET` | `harnax-secret-key-2026-...` | JWT 签名密钥，**必须与 agent-service 一致**；compose 已经在传这个变量 |
| `JWT_EXPIRATION` | `7200000` | token 有效期（毫秒） |
| `DB_POOL_SIZE` / `DB_POOL_MIN_IDLE` | `20` / 见 yml | Hikari 连接池 |
| `MYBATIS_LOG_IMPL` | 代码默认 `StdOutImpl`；**compose 已改为传 `Slf4jImpl`** | 默认会把每条 SQL 打到 stdout。打包部署现在默认安静（要恢复逐条 SQL：`MYBATIS_LOG_IMPL=org.apache.ibatis.logging.stdout.StdOutImpl`），噪声级别再由 `MYBATIS_LOG_LEVEL` 控制 |
| `LOG_LEVEL_ROOT` / `LOG_LEVEL` | `INFO` / `INFO` | root 与本服务包级别 |
| `FLYWAY_CLEAN_DISABLED` | `true` | 禁止 `flyway clean`；compose 里是**字面量**，`.env` 改不动 |
| `SKILL_UPLOAD_MAX_FILE_SIZE` / `SKILL_UPLOAD_MAX_REQUEST_SIZE` | `200MB` / `205MB` | 技能 ZIP 上传的 multipart 上限；nginx 的 `client_max_body_size` 要一起放，否则表现为 413 |
| `MINIO_ENABLED` / `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_OUTPUT_BUCKET` | `false` / `http://localhost:9000` / `minioadmin` / `minioadmin` / `harnax-output` | 输出文件的下载代理。**admin 与 agent 两侧都要配同一个桶**，否则用户在网页上看不到 agent 产出的文件 |
| `HARNAX_AUTH_SECRET` | 占位串 | **出向**服务间 token 的签名密钥（scheduler 调用等），与 `ADMIN_INTERNAL_API_SECRET` 是两条不同的东西，别混用 |
| `HARNAX_ROUTER_EXTERNAL_URL` | 空 | 返给前端 / 小程序的路由地址 |
| `HARNAX_SCHEDULER_URL` | `http://localhost:8084`；compose 侧是 `${HARNAX_SCHEDULER_URL:-http://scheduler:8084}`，**可在 `.env` 覆盖** | admin → scheduler 的任务控制地址。**逗号分隔时只用第一个**：共享 Quartz store 之后转发塌缩成一次调用，不再逐实例广播。落到的那台必须是开着调度的实例——同名 service 下挂一台 `SCHEDULER_ENABLED=false` 的副本，就会按负载均衡的运气偶发 40903（reload 路径到用户那边表现为 40902），约束正文在 `docs/deploy-harnax-scheduler.md` |
| `CAPTCHA_TEST_MASTER_CODE` | 空 | **仅供 UI 测试的登录验证码万能码**。生产留空；一旦设了值，任何人用这串码即可过验证码——这是认证绕过，不是便利开关 |

真正仍为硬编码、需要改 `application.yml` 的只剩少量项：`springdoc` 的分组配置、`spring.jackson` 的时间格式与时区、`management` 端点暴露列表，以及 `mybatis` 的 `mapper-locations` / `type-aliases-package`。这些一般不需要动。

### compose 侧的一个坑

compose 的 admin 块里只剩两项仍是**字面量**，在 `.env` 里设它们不生效：`MINIO_ENABLED` 与 `SPRING_FLYWAY_LOCATIONS`（迁移脚本位置，本来也不该动）。
其余原先写死的项已改成 `${VAR:-原值}`，可在 `.env` 覆盖：`DB_URL` / `SESSION_JDBC_URL` / `FLYWAY_ENABLED` / `FLYWAY_CLEAN_DISABLED` / `HARNAX_ROUTER_URL` / `HARNAX_SCHEDULER_URL` / `MINIO_ENDPOINT` / `MYBATIS_LOG_IMPL` / `LOCAL_TMP_DIR`。
另外 `SPRING_PROFILES_ACTIVE=prod` 指向一个并**不存在**的 `application-prod.yml`——这个 profile 目前不改变任何配置，只是看起来在管事情；要真有 prod 差异就该建那个文件，否则别依赖它。

---

## 本地模式部署

### 架构

```
客户端 ──► harnax-admin (×1)
                │
            MySQL (harnax_admin)
```

### 单机启动

```bash
# 构建 JAR
cd /path/to/harnax
mvn clean package -DskipTests -pl harnax-admin -am

# 启动 (修改以下环境变量)
export SPRING_DATASOURCE_URL="jdbc:mysql://YOUR_DB_HOST:3306/harnax_admin?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
export SPRING_DATASOURCE_USERNAME="your_db_user"
export SPRING_DATASOURCE_PASSWORD="your_db_password"
export ADMIN_INTERNAL_API_SECRET="your-secret-at-least-32-chars-long"
export SWAGGER_ENABLED=false  # 生产环境关闭

java -Xms256m -Xmx512m -jar harnax-admin/target/harnax-admin-*.jar
```

### Docker Compose 启动

参考 `docker-new/docker-compose.yml` 的 `admin` 服务与 `docker-new/Dockerfile.admin`（构建脚本 `docker-new/build.sh` 需在仓库根目录执行）。

---

## 集群模式部署

### 架构

```
客户端 ──► nginx (轮询/最少连接)
              │
         ┌────┴────┐
         ▼         ▼
     admin-1    admin-2
         │         │
         └────┬────┘
              ▼
        MySQL (harnax_admin)
```

admin 的业务接口本身是无状态的，但**多副本部署有两处例外**，都得靠粘滞或共享存储解决：

1. **MCP OAuth 的待授权状态**（`state` + `code_verifier`，见 `prod_doc/mcp-management` §7.13）存在单个 JVM 内存里：`authorize-url` 与随后的换票请求必须落在同一实例，否则用户看到「这个授权请求未知或已过期」。→ nginx 对 `/api/admin/mcp/**` 做会话保持。
2. **登录验证码与微信扫码登录的状态同样是进程内存**：验证码在 A 实例发出、在 B 实例校验就会失败；微信扫码的状态轮询也一样。→ `/api/admin/auth/**` 同样需要粘滞。

除此之外接口不需要粘滞。另外注意：技能上传与解压产物写在 `LOCAL_TMP_DIR` 指向的**本地卷**上，不是共享存储——多副本时同一个技能的上传只在写入它的那个实例上可见。

> **注意**：admin 内**没有 Quartz**（`harnax-admin/pom.xml` 无该依赖，也没有 `@Scheduled`），定时任务由独立服务 `harnax-scheduler` 承载，部署与容量事项见 `docs/deploy-harnax-scheduler.md`。本服务重启只会打断内存里那两处状态（OAuth 待授权、验证码），任务定义与执行历史都在库里不受影响。

> **会话数据库**：admin 的会话数据（`session.*` 配置）默认使用 `harnax_admin` 数据库，与业务数据在同一个库中。这与 agent-service 不同（agent-service 使用独立的 `agentscope` 数据库）。

### nginx 配置

```nginx
upstream admin_cluster {
    least_conn;
    server admin-1:8080;
    server admin-2:8080;
}

server {
    listen 80;
    server_name admin.your-domain.com;

    # API 接口
    location /api/ {
        proxy_pass http://admin_cluster;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_http_version 1.1;
        proxy_set_header Connection "";

        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 300s;
    }

    # AI/SSE 接口 (需要关闭 buffering)
    location /ai/ {
        proxy_pass http://admin_cluster;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_request_buffering off;
        proxy_set_header Cache-Control "no-cache";
        proxy_set_header X-Accel-Buffering "no";
        gzip off;

        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 300s;
        proxy_next_upstream error timeout;
        proxy_next_upstream_tries 1;
    }

    # 前端静态文件
    location / {
        root /usr/share/nginx/html;
        index index.html;
        try_files $uri $uri/ /index.html;
    }
}
```

### 多实例启动

每个 admin 实例使用相同的配置，指向同一个 MySQL：

```bash
# admin-1 (机器 A)
export SPRING_DATASOURCE_URL="jdbc:mysql://DB_HOST:3306/harnax_admin?..."
export ADMIN_INTERNAL_API_SECRET="your-secret-at-least-32-chars-long"
java -jar harnax-admin-*.jar

# admin-2 (机器 B)
# 配置完全相同
export SPRING_DATASOURCE_URL="jdbc:mysql://DB_HOST:3306/harnax_admin?..."
export ADMIN_INTERNAL_API_SECRET="your-secret-at-least-32-chars-long"  # 必须与 admin-1 一致
java -jar harnax-admin-*.jar
```

> **Flyway 注意**: 多个实例同时启动时，Flyway 会自动加锁串行执行迁移，不会出现并发建表冲突。但建议第一次部署时先启动一个实例等待建表完成，再启动其他实例。

---

## 健康检查

```bash
curl -i http://localhost:8080/api/admin/health
```

返回 HTTP 200 表示服务正常；**401 也算活着**（进程起来了，只是这个接口要鉴权）——compose 的健康检查正是按「200 或 401」判定的。（原先写的 `/api/health` 这条路由不存在。）

---

## 端口与防火墙

| 端口 | 用途 | 对外暴露 |
|------|------|---------|
| 8080 | HTTP API | 集群模式下仅 nginx 可访问，不直接对外 |
| 8084 | scheduler（admin 转发任务调度指令的下游） | 不对外暴露：nginx 无 `/api/scheduler/` 路由、`docker-compose.yml` 也不发布宿主端口，仅容器网络 `http://scheduler:8084` 可达 |

---

## 与其他服务的关联

| 调用方 | 关系 | 配置项 |
|--------|------|--------|
| harnax-session-router | Router 调用 admin 的内部 API 校验 API Key 和获取会话信息 | Router: `admin.service.url` + `admin.internal-api.secret` |
| harnax-agent-service | Agent 通过 admin 的 JWT secret 签发用户 Token | Agent: `jwt.secret` 需与 admin 一致 |
| harnax-channel-service | Channel 调用 Router，Router 再调用 admin | 间接依赖 |
| admin 自身 | 调用 Router 的 URL 获取路由状态 | `harnax.router.url` |

> **JWT Secret 一致性**: admin 和 agent-service 的 `jwt.secret` 必须配置为相同值，否则用户在 admin 登录后请求 agent-service 会被拒绝。
