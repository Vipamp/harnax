# Harnax 微信小程序 — 完整管理后台设计方案

## 一、项目概述

基于 harnax-webui（Ant Design Pro 管理后台）的全部功能，使用**微信原生开发框架**（WXML + WXSS + JS + TypeScript）从零构建微信小程序。

### 功能范围

| 模块 | 功能 | 优先级 |
|------|------|--------|
| 认证 | 登录/登出 | P0 |
| 仪表盘 | 统计概览 | P0 |
| Agent 管理 | 列表/搜索/CRUD/启停 | P0 |
| Session 对话 | 会话列表/对话聊天(SSE)/CRUD | P0 |
| Agent 任务 | 定时任务列表/CRUD/日志 | P0 |
| 模型管理 | Provider/Model CRUD | P1 |
| MCP 管理 | 列表/CRUD/配置详情 | P1 |
| Skill 管理 | 仓库/技能/同步 | P1 |
| Channel 管理 | 渠道列表/CRUD | P1 |
| 用户管理 | 用户列表/CRUD | P1 |
| 租户管理 | 租户列表/CRUD/用户分配 | P1 |
| Token 监控 | 用量统计/图表 | P2 |
| API Key 管理 | Key 列表/CRUD | P2 |
| 环境变量管理 | 变量列表/CRUD | P2 |

## 二、技术架构

- 微信原生开发框架（WXML + WXSS + TypeScript）
- wx.request 网络请求
- wx.getStorageSync / wx.setStorageSync 本地存储
- SSE 流式对话（enableChunked）

## 三、后端 API 对齐

直接复用 harnax-admin 后端 API：

| 模块 | API 前缀 |
|------|----------|
| 认证 | `/api/admin/auth/*` |
| Agent | `/api/admin/agents/*` |
| Session | `/api/admin/sessions/*` |
| Chat | `/api/router/agent/chat/*`（SSE） |
| Task | `/api/admin/agent-tasks/*` |
| Provider | `/api/admin/model-providers/*` |
| Model | `/api/admin/models/*` |
| MCP | `/api/admin/mcp/*` |
| Skill | `/api/admin/skills/*` |
| SkillRepo | `/api/admin/skill-repositories/*` |
| Channel | `/api/admin/channels/*` |
| User | `/api/admin/users/*` |
| Tenant | `/api/admin/tenant/*` |
| ApiKey | `/api/admin/api-keys/*` |
| EnvVar | `/api/admin/env-variables/*` |
| TokenStats | `/api/admin/token-stats/*` |

## 四、TabBar

- 仪表盘（首页）
- Agent（列表）
- 会话（列表）
- 系统（菜单入口）
