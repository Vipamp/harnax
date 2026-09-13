# 前端与 nginx 部署说明（harnax-frontend）

## 这一层是什么

`frontend` 服务（镜像 `harnax-frontend:latest`）是**整套部署唯一的公网入口**：nginx 同时干两件事——发 SPA 静态文件、按路径把 API 反代到各服务。用户浏览器只看到 `80` / `443` 两个标准端口，其余服务的端口（`28080`/`28081`/…）**只是为了从宿主机直连调试才发布**，不该对外开放。

配置在 `docker-new/nginx.conf`，构建进镜像（`docker-new/Dockerfile.frontend`），改了要**重新 build 镜像**才生效；卷挂载的是 `ssl/` 目录，证书可以直接换。

## 路由表（以 nginx.conf 现状为准）

| 路径 | 转发到 | 说明 |
|---|---|---|
| `/`（静态资源） | 容器内 SPA | `location ~* \.(js|css|png|...)$`，命中不到就 `index.html`（前端路由自己接管） |
| `/api/router/agent/chat/stream`、`/api/router/agent/confirm` | `router:8081` | **SSE 专用 location**：关 buffering、独立读超时。匹配规则是「最长前缀优先」，所以它必须排在 `/api/router/` 之前才有意义 |
| `/api/router/` | `router:8081` | 其余对话与路由接口 |
| `/ui` | `router:8081` | router 自带的监控页。**只有这一条不够**：面板还要 `/index.html`、`/style.css`、`/app.js`，只放 `/ui` 会得到一张空白页 |
| `/api/channel/`、`/api/channel/webhook/` | `channel-service:8083` | 后者是平台回调入口（详见 `docs/deploy-harnax-channel-service.md`） |
| `/api/scheduler/` | `scheduler:8084` | 定时任务接口 |
| `/api/admin/` | `admin:8080` | 管理面 API；`client_max_body_size 205m` |
| `/health` | 本地探测 | 容器健康检查用，不代理到业务服务 |

## 三条必须知道的耦合

1. **`client_max_body_size 205m` 与 admin 的技能上传上限是同一件事。** admin 的 `SKILL_UPLOAD_MAX_REQUEST_SIZE` 默认 `205MB`，nginx 这里写死 `205m`：调大应用侧而忘了这里，用户看到的是 nginx 直接 413，而应用日志里**什么都不会留下**。
2. **上游是硬编码的服务名，且没有 `upstream{}` 块。** 每个服务都是单一容器名（`router:8081` 等），意味着：compose 里改服务名、改容器内端口，必须同步改 `nginx.conf`，否则表现为整站 502；反过来，想在 nginx 层做多实例负载均衡，得自己加 `upstream` 块，配置里现在**没有**。
3. **地址用变量 + `resolver` 取，而不是启动期解析。** 配置里以 `set $upstream_router http://router:8081;` 的形式写，配合 Docker 内置 DNS，目的是让**上游容器重启换 IP 后不出现持续 502**（nginx 默认在加载时就把域名解析死）。改成 `upstream{}` 块时要把这条一起想清楚，别把这条保护弄丢。

## SSE 相关

`/api/router/agent/chat/stream` 那条 location 关掉了 buffering / cache 并设了长读超时。任何前置的 CDN、企业代理、云负载均衡也要**对该路径关缓冲并放宽超时**，否则症状是「回答不出，但后端日志显示已经生成完」。多副本 router 时还要注意会话粘性（见 `docs/deploy-harnax-session-router.md`），以及 admin 的多副本例外：MCP OAuth 的待授权状态与登录验证码都在单 JVM 内存里，`/api/admin/mcp/**` 与 `/api/admin/auth/**` 需要会话保持。

## HTTPS 与证书

- `443` 上是标准端口（用户在浏览器里不带端口号）。SPA 里配置的回调 / 跳转 origin 必须与之一致：`APP_BASE_URL` 与 `APP_FRONTEND_BASE_URL` 要填**浏览器地址栏里那个 origin**（不带端口），否则 MCP OAuth 的 `redirect_uri` 与授权服务器登记值逐字符比不上，第一次授权就被拒。
- 证书放 `docker-new/ssl/`，换证书后 `docker compose restart frontend` 即可，不必重建镜像。
- `server_name localhost`：真实域名要改这一行（否则同机其他 SNI 虚拟主机可能抢走请求）。

## 常见故障定位

| 现象 | 先看什么 |
|---|---|
| 全站 502 | 上游服务名/端口是否与 compose 一致；容器是否换过 IP |
| 技能 ZIP 上传 413 | nginx 的 `client_max_body_size` 与 admin 的 `SKILL_UPLOAD_*` 是否一起调 |
| 流式回答卡在最后一次性出 | 该路径 buffering 是否被中间层重新打开 |
| 路由监控页空白 | 除 `/ui` 外还缺 `/index.html`、`/style.css`、`/app.js` |
| 登录后 MCP 授权跳回错误页 | `APP_FRONTEND_BASE_URL` / `APP_BASE_URL` 是否填成 nginx 的 origin；平台侧登记的回调是否同步 |

## 相关文档

- `docs/deploy-harnax-admin.md`（`APP_BASE_URL` / `APP_FRONTEND_BASE_URL` 与 MCP OAuth 的关系）
- `docs/deploy-harnax-session-router.md`（router 多实例与粘性）
- `docs/deploy-harnax-channel-service.md`（平台回调与监听器所有权）
