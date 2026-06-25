# harnax-admin 部署文档

## 服务概述

harnax-admin 是管理后台服务，提供用户管理、API Key 管理、会话管理、Agent 配置等功能。

- **端口**: 8080
- **数据库**: harnax_admin (MySQL)
- **认证方式**: 独立 JWT (JwtAuthenticationFilter)，不使用 UnifiedAuth
- **Flyway**: 自动建表 (classpath:db/migration)

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
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://172.20.10.5:3306/harnax_admin?...` | MySQL 连接串 |
| `SPRING_DATASOURCE_USERNAME` | `root` | 数据库用户名 |
| `SPRING_DATASOURCE_PASSWORD` | `123456` | 数据库密码 |
| `SESSION_JDBC_URL` | 同 SPRING_DATASOURCE_URL | 会话数据库连接串 |
| `SESSION_DATABASE_NAME` | `harnax_admin` | 会话数据库名 |
| `SESSION_USERNAME` | `root` | 会话数据库用户名 |
| `SESSION_PASSWORD` | `123456` | 会话数据库密码 |
| `LOCAL_TMP_DIR` | `/tmp/harnax` | 本地临时文件目录 |
| `ADMIN_INTERNAL_API_SECRET` | `change-me-in-production-min-32-chars!!` | 内部 API 认证密钥 (>=32字符) |
| `FLYWAY_ENABLED` | `true` | 是否启用 Flyway 建表 |
| `SWAGGER_ENABLED` | `true` | 是否启用 Swagger 文档 (生产建议关闭) |
| `HARNAX_ROUTER_URL` | `http://localhost:8081` | Router 服务地址 |

> **安全提醒**: `ADMIN_INTERNAL_API_SECRET` 是其他服务 (channel/router/agent-service) 调用 admin 内部 API 的凭证，生产环境必须修改，且长度不少于 32 字符。此密钥需与 router 和 channel 的配置保持一致。

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

参考 `docker/docker-compose.personal.yml` 中的 `backend` 服务。

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

admin 是无状态服务（Quartz 使用内存 JobStore，不持久化），可水平扩展，不需要 sticky session。

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
curl http://localhost:8080/api/health
```

返回 HTTP 200 表示服务正常。

---

## 端口与防火墙

| 端口 | 用途 | 对外暴露 |
|------|------|---------|
| 8080 | HTTP API | 集群模式下仅 nginx 可访问，不直接对外 |

---

## 与其他服务的关联

| 调用方 | 关系 | 配置项 |
|--------|------|--------|
| harnax-session-router | Router 调用 admin 的内部 API 校验 API Key 和获取会话信息 | Router: `admin.service.url` + `admin.internal-api.secret` |
| harnax-agent-service | Agent 通过 admin 的 JWT secret 签发用户 Token | Agent: `jwt.secret` 需与 admin 一致 |
| harnax-channel-service | Channel 调用 Router，Router 再调用 admin | 间接依赖 |
| admin 自身 | 调用 Router 的 URL 获取路由状态 | `harnax.router.url` |

> **JWT Secret 一致性**: admin 和 agent-service 的 `jwt.secret` 必须配置为相同值，否则用户在 admin 登录后请求 agent-service 会被拒绝。
