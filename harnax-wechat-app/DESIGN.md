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
| SkillRepo | `/api/admin/skill-repositories/*`（列表、详情、启停、删除、同步预览） |
| SkillSource | `/api/admin/skill-sources/*`（新建与编辑仓库；旧版 DTO 只有 `name` / `url` / `branch`，收不下 `sourceType` 与 `sourceConfig`） |
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

## 五、待办

> 小程序功能开发已暂停，以下条目只作记录、未排期。

### TODO：Skill 仓库接口从 legacy 迁到 `skill-sources`

2026-09 已把**新建与编辑**迁到 `/api/admin/skill-sources`（原因见第三章表格），其余调用点仍走旧版 `/api/admin/skill-repositories`。admin 侧后续要把技能管理入口收敛到 `skill-sources` 一套（见 `prod_doc/skill-management.zh-CN.md` 的 9.5 节 TODO-5），旧接口下线后这些调用点会直接 404。

`miniprogram/services/skill.ts` 中待迁移的 6 个调用点：

| 函数 | 当前 | 迁移目标 | 注意 |
|------|------|----------|------|
| `getRepoPage` | `GET /skill-repositories/page` | `GET /skill-sources/page` | 分页参数与响应结构一致 |
| `getRepoById` | `GET /skill-repositories/{id}` | `GET /skill-sources/{id}` | 响应字段一致，`sourceConfig` 为空时两边都返回 `null` |
| `toggleRepo` | `PUT /skill-repositories/toggle/{id}?status=` | `PUT /skill-sources/toggle/{id}?status=` | 参数形态相同，只换路径 |
| `deleteRepo` | `DELETE /skill-repositories/{id}` | `DELETE /skill-sources/{id}` | 两边都级联删除仓库下的技能 |
| `fetchRemoteSkills` | `GET /skill-repositories/fetch/{id}` | `GET /skill-sources/{id}/fetch` | `id` 在路径中的位置变了 |
| `batchSaveSkills` | `POST /skills/batch?repositoryId=` | `POST /skill-sources/{id}/install` | **语义不同**：`batch` 是「先 fetch 预览、再按勾选的名字选择性落库」，`install` 是全量重装。`pages/skill/list/index.ts` 现在的两步流程（fetch → 确认弹窗 → batch）可以简化成一步 install；确认弹窗保留，但「发现 N 个技能」的数量要从 install 的返回结果里取，不能再靠 fetch |

迁移时可以顺手做的：

- `typings/api.d.ts` 的 `SkillRepositoryItem` 字段已与 `SkillSourceResponse` 对齐（含 `sourceType` / `sourceConfig` / `version` / `isPublic`），可直接复用，改个更贴切的名字即可；
- ZIP 与 BUILTIN 两种来源仍要在列表页隐藏同步入口：`install` 对 ZIP 会答「一次性安装，请重新上传」，对内置仓库直接拒写；
- **技能本身的 CRUD 不在迁移范围内**：`getSkillPage` / `getSkillById` / `deleteSkill` / `toggleSkill` 走的 `/api/admin/skills/*` 没有新旧两套，`SkillController` 仍是唯一入口，不要误删。
