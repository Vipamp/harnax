# 部署端口统一：对外 80/443 + 其余 2 开头五位数

## 背景

`docker-new/` 目前的宿主端口约定是「10000 + 服务知名端口」（18080、13306、10443…），动因是宿主机 80 / 443 / 3306 / 3389 被本机另一组容器（done-nginx-local、done-service-local、done-mysql-local）占用。现在这批冲突容器已停止，宿主 80 / 443 实测空闲，端口段 2xxxx 全空闲。

同时仓库里存在三套部署入口：`docker-new/`（Docker 集群，主力）、`deploy/local/`（宿主机直跑 + nginx）、`docker/`（早期版本，仅自身 README 引用）。三套端口规则各不相同（1xxxx / 四位数 + 3389 / 四位数 + 80），维护时反复出现改一处漏两处的问题。

本次统一为：**唯一部署入口 `docker-new/`，对外入口用标准 80 / 443，其余端口一律 5 位数且以 2 开头**；`deploy/local/` 与 `docker/` 删除。

## 决策记录

| 决策 | 选择 | 理由 | 被否方案 |
|---|---|---|---|
| 其余端口编号规则 | 宿主端口 = 20000 + 容器内端口 | 与旧 1xxxx 规则同构，看容器端口即可推出宿主端口，compose 里 `28080:8080` 自解释 | 顺序编号 20001/20002（丢映射关系）；只改入口保留 1xxxx（规范不统一） |
| 对外入口 | 真绑宿主 80 / 443 | 用户要求，浏览器免端口访问；冲突容器已停，macOS Docker Desktop 绑特权端口无阻碍 | 容器内 80/443 + 宿主 20080/20443（浏览器仍要带端口） |
| 部署入口收敛 | 仅保留 `docker-new/` | 用户要求；旧目录无外部引用 | 旧目录同步改端口（保留两套配置继续漂移） |
| CORS 允许列表 | 必须同时列出**无端口**形式 | 实测：端口回到 443 后 Origin 变为 `https://localhost`，`https://localhost:*` 不匹配它，会立刻复现上一轮修的 403 | 保留现状（403 复发）；整体放宽为 `*`（router 与 admin 一致性下降，白名单语义丢失） |

## 1. 端口映射表（唯一事实来源）

| 服务 | 旧宿主端口 | 新宿主端口 | 容器内端口 | 服务间地址（不变） |
|---|---|---|---|---|
| Frontend (nginx) | 10080 / 10443 | **80 / 443** | 80 / 443 | — |
| Admin | 18080 | 28080 | 8080 | `http://admin:8080` |
| Router | 18081 | 28081 | 8081 | `http://router:8081` |
| Agent-Service | 18082 | 28082 | 8082 | `http://agent-service:8082` |
| Channel-Service | 18083 | 28083 | 8083 | `http://channel-service:8083` |
| Scheduler | 18084 | 28084 | 8084 | `http://scheduler:8084` |
| MySQL | 13306 | 23306 | 3306 | `mysql:3306` |
| Redis | 16379 | 26379 | 6379 | `redis:6379` |
| MinIO API | 19000 | 29000 | 9000 | `minio:9000` |
| MinIO Console | 19001 | 29001 | 9001 | — |
| MCP Server | 19002 | 29002 | 9002 | — |

约束：容器内部端口一律不变，因此所有服务间 URL、数据源 JDBC、健康检查的容器内地址都不需要动。唯一面向宿主的 URL 是 `HARNAX_ROUTER_EXTERNAL_URL`（供沙箱/外部回调），改为 `http://localhost:28081`。

## 2. nginx 入口（docker-new/nginx.conf）

- `listen 10080;` → `listen 80;`；`listen 10443 ssl;` → `listen 443 ssl;`
- HTTP→HTTPS 跳转回到 `return 301 https://$host$request_uri;`。原注释记录的坑仍然成立且要保留说明：**这个写法只在入口占用标准端口 443 时安全**——若日后端口改成非标准值，`$host` 会丢掉端口、跳转落到 443，必须显式带端口。
- 顶部注释重写为新约定：对外 80 / 443，其余服务用 2xxxx。
- 反代 upstream（`router:8081`、`admin:8080`、`channel-service:8083`、`scheduler:8084`）与 `client_max_body_size 205m` 等规则全部不动。

## 3. compose 与镜像（docker-new/docker-compose.yml、Dockerfile.frontend）

- 10 处 `ports:` 映射按第 1 节表格改；frontend 为 `"80:80"` + `"443:443"`。
- 顶部端口约定注释重写：不再是「10000 + 知名端口、避开 80/443」，改为「对外 80/443，其余 20000 + 知名端口」。
- frontend 的 `healthcheck` 从 `http://localhost:10080/health` 改为 `http://localhost/health`（nginx 上 `/health` 是明文返回 OK 的白名单 location）。
- `HARNAX_ROUTER_EXTERNAL_URL: http://localhost:18081` → `http://localhost:28081`。
- `Dockerfile.frontend`：`EXPOSE 10080 10443` → `EXPOSE 80 443`，其 HEALTHCHECK 同步去端口；相关注释改写。
- `build.sh` / `deploy-all.sh` 末尾打印的访问地址表按映射表更新：Frontend 行为 `http://localhost`（HTTPS `https://localhost`），其余用 2xxxx。

## 4. CORS 允许列表（router，三处必须一致）

断裂点在于 CorsFilter 以 `Ordered.HIGHEST_PRECEDENCE` 注册、优先于 UnifiedAuthFilter，Origin 不匹配时直接返回裸 403，用户看到的是「权限错误」而非「跨域配置缺失」。新列表：

```
http://localhost,https://localhost,http://127.0.0.1,https://127.0.0.1,http://localhost:*,https://localhost:*,http://127.0.0.1:*,https://127.0.0.1:*
```

- 前 4 项（无端口）覆盖走 80/443 的浏览器；后 4 项（带端口通配）覆盖 App/小程序/H5 直连 `localhost:28081` 等场景。
- 三处同步：`docker-new/docker-compose.yml` 的 `ROUTER_CORS_ALLOWED_ORIGINS` 默认值、`harnax-session-router/src/main/resources/application.yml` 的 `router.cors.allowed-origins`（`${ROUTER_CORS_ALLOWED_ORIGINS:...}`）、`RouterConfig.kt` 的 `@Value` 兜底默认值。
- 注释要点：Spring 的 `allowedOriginPatterns` 中 `https://localhost:*` **不**匹配无端口的 `https://localhost`，两种形式都要写。实测证据见第 6 节。

## 5. 删除与文档连带修正

- 删除 `deploy/local/`（README、build.sh、deploy-all.sh、env.conf、infra.sh、init-db.sql、nginx.conf、services.sh、webui.sh、logs/、pids/）。
- 删除 `docker/`（含 compose、Dockerfile、nginx.conf、build.sh、sql、.env*、DEPLOYMENT.md、data/、dist/）。
- 删除前确认（已取证）：全仓对这两个目录的引用只有 `prod_doc/skill-management.zh-CN.md` 与 `.en-US.md` 中「三份 nginx 配置」那一句 → 改为仅 `docker-new/nginx.conf` 一份，语义（该 location 上有 `client_max_body_size 205m`）保持不变。
- 数据安全：`docker/data/` 实测 0B 且无任何运行中容器挂载（`docker ps -aq` 遍历为 0 命中）；活跃 MySQL 数据在 `docker-new/data/mysql`（harnax-mysql 的 mount 已确认），**本次不动**。`deploy/local/logs`、`pids` 为空目录。
- `docs/superpowers/specs/` 下的历史设计文档属于历史快照，其中的旧端口不改写。
- `tmp/smoke-frontend.sh`、`tmp/smoke-ports.sh`、`tmp/check-skills.sh` 三个本地冒烟脚本里的端口按新表同步——它们同时是本次验收要用的工具。

## 6. 验证

按顺序执行，每步都要看实际输出：

1. `docker compose -f docker-new/docker-compose.yml config -q` — compose 语法与端口映射渲染正确。
2. 全量重建：nginx 配置是 `COPY` 进 frontend 镜像的，`up -d` 不会感知变更，必须先重建镜像。构建上下文是**仓库根目录**（Dockerfile 里 `COPY docker-new/nginx.conf`，与 `build.sh:175` 一致）：`docker build -f docker-new/Dockerfile.frontend -t harnax-frontend:latest .`，再 `docker compose -f docker-new/docker-compose.yml up -d --force-recreate`（其余服务仅 compose 层变更，重建容器即生效）。
3. 入口层：`curl -sI http://localhost/` 期望 `301` 且 `Location: https://localhost/`；`curl -ks https://localhost/health` 期望 `OK`。
4. 代理层：`curl -ks -H 'Origin: https://localhost' https://localhost/api/router/monitor/instances` 期望 `200` + 实例 JSON（这条同时证明无端口 Origin 已通过 CORS）。
5. 反证对照：同一步骤改用 `-H 'Origin: https://evil.example'` 期望回到 `403`（证明白名单是精确生效的，不是被整体放宽为 `*`）。注意**不能**用 `https://localhost:10443` 做反证——它仍匹配保留的 `https://localhost:*` 通配项，预期是 401 而非 403。
6. 上一轮修的接口不回归：`curl -ks -X POST -H 'Origin: https://localhost' -H 'Content-Type: application/json' -d '{"sessionId":"probe","command":"STATUS"}' https://localhost/api/router/agent/command` 期望 `401`（缺凭证，说明已穿过 CORS 到达 UnifiedAuthFilter）。
7. 其余端口逐个探活：28080 admin、28081 router、28082 agent、28083 channel、28084 scheduler、29000 MinIO、29002 MCP 有监听；23306 MySQL、26379 Redis 可连。
8. 旧端口确认已释放：18080 / 18081 / 10443 / 10080 无监听。
9. 脚本自检（不跑真实部署，避免与第 2 步重复编译）：`bash -n docker-new/build.sh docker-new/deploy-all.sh docker-new/deploy-service.sh` 语法通过；`grep -rn "deploy/local\|docker/docker-compose" docker-new` 无命中，确认无脚本依赖被删目录。

## 非目标

- 不改任何容器内部端口、不改服务间 URL、不改 Spring profile 结构。
- 不做「端口可通过 .env 任意重映射」的参数化：`20000 + 知名端口` 是可推导的固定约定，参数化会让映射表重新漂移。
- 不改 `docker-new/ssl/` 证书与 nginx TLS 套件配置。
- 不动 `harnax-wechat-app/miniprogram/services/skill.ts` 的 `190000`（那是请求超时毫秒数，grep 误命中）。
