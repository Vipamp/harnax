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
| `HARNAX_CLI_PACKAGE_DIR` | `/home/harnax/cli-packages` | CLI 插件包目录（配置项 `harnax.cli.package-dir`）。只影响 admin；compose 把宿主机 `docker-new/dist/cli-packages/` 以**只读 bind mount** 盖在同名路径上，详见下文「CLI 插件包投放与升级」 |
| `MINIO_CLI_PACKAGE_BUCKET` | `harnax-cli-packages` | 登记后的包存放的桶（配置项 `minio.cli-package-bucket`）。**agent-service 读的是同一个变量名**（`harness.minio.cli-package-bucket`），只改一侧会导致运行期取不到包。两侧各自都会建桶（admin 登记器与 agent-service 的 `ensureBuckets`），谁先起来谁建，不需要手工预建 |
| `HARNAX_AUTH_SECRET` | 占位串 | 服务间 token 的签名密钥，与 `ADMIN_INTERNAL_API_SECRET` 是两条不同的东西，别混用。**发布 2 起它是双向的**：既签本服务调 scheduler 的出向 token，也是 scheduler 校验 admin 转发进来的那枚 bearer 的密钥（契约 C4），所以**必须与 scheduler 同值**——compose 里两个服务由同一个变量插值，配一次就同源，手工/裸机部署两边各写一次才是坑。**两种配错的症状不一样，别混成一条**：两边**不同值**才是 401——scheduler 把每一次转发都拒掉，用户在网页上看到「Scheduler service unavailable」/40902，而 cron 照常触发；两边都**留占位值**（yml 与 compose 的默认串 `change-me-in-production-min-32-chars!!`，36 字符，过得了长度校验）**不报错、照常 200**，因为两个服务插的是同一个变量、值天然相同——它的问题是安全而不是可用：那串写在公开仓库里，等于给 `/api/scheduler/**` 配了一把谁都能配的钥匙，必须换掉。注意本服务另有一个 `ADMIN_INTERNAL_API_SECRET`（`admin.internal-api.secret`）确实会因占位值被拒（`JwtAuthenticationFilter` 认它为"未配置"，`/api/admin/**` 上直接 401），那是另一条链上的另一把密钥，别把两者的行为套到 `HARNAX_AUTH_SECRET` 上 |
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

> **定时任务域在本服务里已经只剩转发（发布 2 起）**。`/api/admin/agent-tasks/**` 的 12 个端点、请求体形状、`Page` 的 7 个键与 `records[*]` 的字段名、以及 `40901`/`40902`/`40903` 三个业务码的含义**对三客户端一字未改**；改的是这些调用背后做了什么：11 条走 `SchedulerClientImpl.forward` 打到 `http://scheduler:8084`（`HARNAX_SCHEDULER_URL`），只有 `/agents` 仍是 admin 自己的域（agent 表在它手上）。**本服务对 `agent_task` / `agent_task_log` / `agent_task_execution` / `QRTZ_*` 这四张表发不出任何一条 SQL**——实体与 mapper 已经不在 `harnax-entity` 里，`grep -rn "agentTaskMapper\|agentTaskLogMapper\|AgentTaskExecution" harnax-admin/src/main` 是零命中。每一发转发现在带三样东西：一枚 `typ=internal` 的 JWT（`HARNAX_AUTH_SECRET` 签）、`X-Forwarded-User`、`X-Tenant-Id`；scheduler 侧的门禁认这三样，所以 **admin 与 scheduler 必须同窗口升级**，只升一边的话旧 admin 的转发一律 401（症状见 `docs/deploy-harnax-scheduler.md` 的「常见问题」）。
>
> **本服务内没有 Quartz**（`harnax-admin/pom.xml` 无该依赖，也没有 `@Scheduled`），调度全在独立服务 `harnax-scheduler`，部署与容量事项见 `docs/deploy-harnax-scheduler.md`。本服务重启只会打断内存里那两处状态（OAuth 待授权、验证码），任务定义与执行历史都在库里不受影响——而且它们在 scheduler 的库里，本服务重启碰不到。

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

## CLI 插件包投放与升级

`/context/cli` 页面是只读的：CLI 不由人在界面上创建，只由 admin 启动时扫描包目录登记。包怎么写见 `prod_doc/cli-package-spec.zh-CN.md`，平台侧链路见 `prod_doc/cli-plugin-package-design.zh-CN.md`，这里只写运维动作。

包的位置有四层，别混：

| 层 | 路径 | 说明 |
|---|---|---|
| 货架（投递口） | `cli-packages/dist/*.harnaxcli.zip` | `cli-packages/build.sh` 只把有来源的包补齐进来：`harnax-cli`（`make package`）+ 每个 `cli-packages/<name>/build.sh`。**它不清架**，手工丢进来的 zip 原样留下、一起投放。同名两份留 manifest `version` 高的，落选那份移进 `cli-packages/dist/.superseded/`；只有同名同版本两份才让构建失败 |
| 构建输入 | `docker-new/dist/cli-packages/` | 三个入口脚本（`build.sh` / `deploy-all.sh` / `deploy-service.sh admin`）先清空再从货架整份复制，然后才 `docker build`。多这一层是因为 `.dockerignore` 只放行 `docker-new/dist`，`cli-packages/dist` 进不了构建上下文 |
| 镜像内 | `/home/harnax/cli-packages/`（配置项 `harnax.cli.package-dir`） | `Dockerfile.admin` 用一条 `COPY` 落进去，目录为空也能构建，只是不带任何 CLI。单独 `docker run` 这个镜像时，它就是登记来源 |
| 运行期 | 同上路径，被 compose 的**只读 bind mount** 盖住 | 宿主机 `docker-new/dist/cli-packages/` 是唯一一份：换包就是换这个目录的内容，不再有「镜像里是新包、跑的是旧包」的失同步，也不再需要 `docker cp` |

登记只发生在 admin 启动时（`ApplicationReadyEvent`，Flyway 之后），**运行期不重扫**。所以放好包必须重启才生效，页面没有「立即同步」按钮。

| 动作 | 怎么做 | 何时对会话生效 |
|---|---|---|
| 加一个 CLI | 自研的放 `harnax-cli/`（`make package` 的产物自动上架），第三方的新建 `cli-packages/<name>/`（`plugin.yaml` + `skill/` + `build.sh`）；已经打好的包直接丢进货架 `cli-packages/dist/`，不必有来源目录 → `./cli-packages/build.sh` → 把 `cli-packages/dist/*.harnaxcli.zip` 复制进 `docker-new/dist/cli-packages/` → `docker compose -f docker-new/docker-compose.yml up -d --force-recreate admin` | 新会话 |
| 升级 | 换掉那个包的来源（manifest 里的 `version` 提高），或把新版本包直接丢进货架——同名两份时 `build.sh` 留 `version` 高的、落选那份移进 `cli-packages/dist/.superseded/`；重跑上面两步：同 `name` 覆盖同一行 | 新会话。**已在保活的会话仍跑旧镜像的旧容器**，要立刻换过来只能等空闲回收或 `docker rm -f agentscope-sandbox-<sessionId>` |
| 只改文档（`SKILL.md`） | 同上重打包 | 新会话拿到新提示词，镜像不动 |
| 下线 | 两处都删：`docker-new/dist/cli-packages/` 与货架 `cli-packages/dist/`（有来源目录的还要删掉 `cli-packages/<name>/`，否则下次构建又打回货架）→ 重启 admin | 级联：`cli` 行硬删、`agent_cli_binding` 清空、自带技能行 `active=0`（软删），日志以 WARN 列出包名与行 id。**前提：架上留下的包要比待删的多**，否则被第三道刹车拒绝 |
| 紧急停用 | 页面 toggle（绑定关系保留） | 下一次配置解析 |

四个会咬人的点：

1. **宿主机那一份是唯一的一份，而且挂载是只读的。** 投放就丢进货架 `cli-packages/dist/`，部署脚本会把整架复制进 `docker-new/dist/cli-packages/`；只改后者是临时的，下一轮部署整架覆盖它。`docker cp` 进容器会被拒——挂载本身不许写。换来的是不再有「镜像里是新包、卷里是旧包」：重建镜像并 recreate 即换包。
2. **把 `docker-new/dist/cli-packages/` 清成空目录 = 把所有 CLI 一起报成退役。** 目录缺失时 Docker 会就地建一个空的，admin 读到空货架就下 pruning 结论；包少的部署里这一步会被第三道刹车拦下（只留一条 ERROR 什么都不删），但它不是「什么都没发生」的保险。
3. **`MINIO_ENABLED=false` 且包目录非空 ⇒ admin 直接启动失败**，不回落本地存储。留空目录则不要求 MinIO。
4. **包目录很小时「下线」会被静默拒绝**（其实是 ERROR，不是没执行）。prune 的第三道刹车是「待删数 ≥ 剩余数即拒绝」，所以 2 个包删 1 个正好落在拒绝侧，日志形如：`Skipping prune: 1 row(s) not in the directory vs only 1 registered — this looks like a missing package directory, not retired packages`。这时 `cli` 行、绑定、技能行都还在。要真退役：先多放一个包再重启（3 删 1 即 `1 < 2` 通过），或直接把这三处 SQL 手工清掉。这条刹车同时定了下界：能删的那一轮必须留 ≥2 个包在架上，所以登记器写的行永远删不到只剩 1 个——退掉倒数第二个 CLI 只能走 SQL。

包放错地方、manifest 写坏，页面表现只有一个「没出现」——真相在日志里：

```bash
docker logs harnax-admin 2>&1 | grep -i "cli-package\|CliPackage"
# 这一份就是容器里看到的那一份（只读 bind mount）
ls -l docker-new/dist/cli-packages/
# 对象是否真进了 MinIO（键是 <name>/<packageDigest>.harnaxcli.zip，没有 version 段）
docker exec harnax-minio ls -R /data/harnax-cli-packages
```

`harnax` 自身的包就是按这套规范做的第一份实现，源码在 `harnax-cli/`，可当参考。

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
| admin → harnax-scheduler | **定时任务域的转发**：`/api/admin/agent-tasks/**` 的 11 条调度相关调用原样打到 scheduler，四张表（`agent_task` / `agent_task_log` / `agent_task_execution` / `QRTZ_*`）的读写全在对面，本服务零 SQL；同时它也是 C5 那个 owner 查询的调用方（MCP 属主解析） | `harnax.scheduler.url` + `harnax.auth.internal.shared-secret`（= `HARNAX_AUTH_SECRET`，**两侧必须同值**） |

> **JWT Secret 一致性**: admin 和 agent-service 的 `jwt.secret` 必须配置为相同值，否则用户在 admin 登录后请求 agent-service 会被拒绝。
