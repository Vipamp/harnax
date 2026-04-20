# VIPClaw 功能清单 (Feature List)

## 项目概览

**项目名称**: VIPClaw - 企业级 AI Agent 管理平台  
**产品定位**: 基于 AgentScope 框架的多模型 AI Agent 编排与管理系统  
**目标用户**: 企业技术人员、AI 应用开发者、系统管理员  
**技术架构**: Spring Boot 3 + Kotlin + React + Ant Design Pro + MySQL  

---

## 核心业务流程

### 🔴 P0 - 智能体生命周期管理
- **Agent 创建与配置**: 用户创建 AI Agent，配置模型、技能、MCP 服务
- **Agent 会话交互**: 通过 Web UI 与 Agent 进行对话，支持流式输出
- **Agent 状态管理**: 启用/禁用、公开/私有、删除等状态切换
- **Channel 接入**: 将 Agent 对接到企业微信、飞书、钉钉等渠道

### 🔴 P0 - 模型与能力集成
- **多模型供应商管理**: 配置 OpenAI、Anthropic、DashScope 等供应商
- **模型能力配置**: 配置联网、推理、工具调用、MCP、视觉等能力
- **MCP 服务集成**: 接入外部工具服务（stdio/SSE/StreamableHTTP）
- **Skill 技能库**: 可扩展的技能包管理，支持 Git 仓库同步

### 🟡 P1 - 会话与监控
- **Session 会话管理**: 查看和管理所有 Agent 会话记录
- **Token 消耗监控**: 实时监控 Token 使用量和费用统计
- **多维度统计**: 按日期、模型、智能体、用户统计消耗

### 🟡 P1 - 系统管理
- **用户权限管理**: 用户 CRUD、管理员权限、登录认证
- **定时任务调度**: Quartz 定时任务管理与执行日志
- **JWT Token 安全**: Token 黑名单、验证码、安全认证

### 🟢 P2 - 高级功能
- **PlanNote 任务规划**: Agent 自主规划任务步骤并展示
- **工具调用日志**: 记录 Agent 工具调用的详细过程
- **多渠道消息**: 支持企业微信、飞书、钉钉消息推送

---

## 用户端功能（Agent 使用）

| 优先级 | 功能模块 | 功能点 | 描述 |
|--------|---------|--------|------|
| 🔴 P0 | 智能体交互 | 流式对话 | SSE 实时流式输出，打字机效果 |
| 🔴 P0 | 智能体交互 | 工具调用可视化 | 显示 Agent 调用工具的过程和结果 |
| 🔴 P0 | 智能体交互 | 任务规划展示 | 展示 Agent 的任务分解和执行进度 |
| 🟡 P1 | 智能体交互 | 会话历史 | 查看历史会话记录和上下文 |
| 🟡 P1 | 智能体交互 | 多会话管理 | 同时与多个 Agent 对话 |
| 🟢 P2 | 智能体交互 | 会话导出 | 导出对话记录为文档 |

---

## 管理端功能

### 1. 智能体管理 (Agent Management)

| 优先级 | 功能点 | 描述 |
|--------|--------|------|
| 🔴 P0 | Agent 列表 | 分页查询、名称搜索、状态筛选、创建人筛选 |
| 🔴 P0 | Agent 创建 | 配置名称、描述、系统提示词、选择模型、关联技能和 MCP |
| 🔴 P0 | Agent 编辑 | 修改 Agent 配置，支持部分字段更新 |
| 🔴 P0 | Agent 状态 | 启用/禁用切换，控制 Agent 是否可用 |
| 🔴 P0 | Agent 删除 | 逻辑删除，支持恢复 |
| 🟡 P1 | Agent 公开 | 设置 Agent 是否公开，其他用户可见 |
| 🟡 P1 | Agent 详情 | 查看关联会话列表、技能列表、MCP 列表 |
| 🟢 P2 | Agent 导入导出 | 批量导入导出 Agent 配置 |

### 2. 模型管理 (Model Management)

| 优先级 | 功能点 | 描述 |
|--------|--------|------|
| 🔴 P0 | 模型列表 | 分页查询、名称搜索、供应商筛选、能力标签筛选、价格范围筛选 |
| 🔴 P0 | 模型创建 | 配置模型名称、供应商、类型、能力标签、价格 |
| 🔴 P0 | 模型编辑 | 修改模型配置和价格 |
| 🔴 P0 | 模型状态 | 启用/禁用切换 |
| 🟡 P1 | 模型能力标签 | 支持联网、推理、工具、MCP、视觉等多维度标签 |
| 🟡 P1 | 模型公开 | 设置模型是否公开 |
| 🟢 P2 | 模型测试 | 测试模型连通性和响应速度 |

### 3. 模型供应商管理 (Model Provider Management)

| 优先级 | 功能点 | 描述 |
|--------|--------|------|
| 🔴 P0 | 供应商列表 | 分页查询、名称搜索、状态筛选 |
| 🔴 P0 | 供应商创建 | 配置供应商名称、显示名称、API Key、Base URL |
| 🔴 P0 | 供应商编辑 | 修改供应商配置和密钥 |
| 🔴 P0 | 供应商状态 | 启用/禁用切换 |
| 🟡 P1 | 供应商连通性测试 | 测试 API 连通性和认证有效性 |
| 🟡 P1 | 供应商公开 | 设置供应商是否公开 |

### 4. MCP 服务管理 (MCP Server Management)

| 优先级 | 功能点 | 描述 |
|--------|--------|------|
| 🔴 P0 | MCP 列表 | 分页查询、关键字搜索、状态筛选、类型筛选 |
| 🔴 P0 | MCP 创建 | 配置名称、描述、类型（stdio/SSE/StreamableHTTP）、命令或 URL |
| 🔴 P0 | MCP 编辑 | 修改 MCP 配置 |
| 🔴 P0 | MCP 状态 | 启用/禁用切换 |
| 🔴 P0 | 连通性测试 | 测试 MCP 服务连通性 |
| 🟡 P1 | MCP 类型支持 | 支持 stdio、SSE、StreamableHTTP 三种类型 |
| 🟡 P1 | MCP 公开 | 设置 MCP 是否公开 |

### 5. 技能管理 (Skill Management)

| 优先级 | 功能点 | 描述 |
|--------|--------|------|
| 🔴 P0 | 技能列表 | 分页查询、名称搜索、仓库筛选、状态筛选 |
| 🔴 P0 | 技能创建 | 配置名称、所属仓库、Markdown 描述 |
| 🔴 P0 | 技能编辑 | 修改技能配置和描述 |
| 🔴 P0 | 技能状态 | 启用/禁用切换 |
| 🟡 P1 | 技能仓库管理 | 管理 Git 仓库，配置仓库 URL 和描述 |
| 🟡 P1 | 仓库同步 | 从 Git 仓库同步技能文件 |
| 🟡 P1 | 技能公开 | 设置技能是否公开 |

### 6. Channel 通道管理

| 优先级 | 功能点 | 描述 |
|--------|--------|------|
| 🔴 P0 | Channel 列表 | 分页查询、名称搜索、类型筛选 |
| 🔴 P0 | Channel 创建 | 配置名称、类型、关联 Agent、回调标识 |
| 🔴 P0 | Channel 编辑 | 修改 Channel 配置和密钥 |
| 🔴 P0 | Channel 状态 | 启用/禁用切换 |
| 🟡 P1 | 企业微信接入 | 支持企业微信机器人消息推送 |
| 🟡 P1 | 飞书接入 | 支持飞书机器人消息推送 |
| 🟡 P1 | 钉钉接入 | 支持钉钉机器人消息推送 |
| 🟢 P2 | HTTP Webhook | 通用 HTTP Webhook 推送 |

### 7. 会话管理 (Session Management)

| 优先级 | 功能点 | 描述 |
|--------|--------|------|
| 🔴 P0 | 会话列表 | 分页查询、标题搜索、Agent 筛选 |
| 🔴 P0 | 会话详情 | 查看会话标题、描述、关联 Agent |
| 🔴 P0 | 会话删除 | 逻辑删除会话记录 |
| 🟡 P1 | 按 Agent 统计 | 统计每个 Agent 的会话数量 |
| 🟢 P2 | 会话导出 | 导出会话记录 |

### 8. Token 监控 (Token Monitoring)

| 优先级 | 功能点 | 描述 |
|--------|--------|------|
| 🔴 P0 | Token 统计列表 | 分页查询、会话/Agent/用户筛选 |
| 🔴 P0 | 总消耗统计 | 统计总 Token 数、总费用、Prompt/Completion 分布 |
| 🟡 P1 | 按日期统计 | 按日期范围统计 Token 消耗趋势 |
| 🟡 P1 | 按模型统计 | 统计各模型的 Token 使用量和费用 |
| 🟡 P1 | 可视化图表 | Token 消耗趋势图、模型使用占比饼图 |
| 🟢 P2 | 费用预警 | 设置费用阈值，超限时告警 |

### 9. 用户管理 (User Management)

| 优先级 | 功能点 | 描述 |
|--------|--------|------|
| 🔴 P0 | 用户列表 | 分页查询、用户名搜索、状态筛选 |
| 🔴 P0 | 用户创建 | 配置用户名、密码、昵称、邮箱、手机、角色 |
| 🔴 P0 | 用户编辑 | 修改用户信息和状态 |
| 🔴 P0 | 用户状态 | 启用/禁用切换 |
| 🔴 P0 | 管理员权限 | 设置用户是否为管理员，控制菜单可见性 |
| 🟡 P1 | 密码重置 | 管理员重置用户密码 |
| 🟡 P1 | 登录日志 | 记录用户登录时间和 IP |

### 10. 定时任务管理 (Job Management)

| 优先级 | 功能点 | 描述 |
|--------|--------|------|
| 🔴 P0 | 任务列表 | 分页查询、任务名称搜索、状态筛选 |
| 🔴 P0 | 任务创建 | 配置任务名称、组名、执行类、Cron 表达式、并发策略 |
| 🔴 P0 | 任务编辑 | 修改任务配置和 Cron 表达式 |
| 🔴 P0 | 任务控制 | 启动、暂停、恢复、立即执行 |
| 🔴 P0 | 任务删除 | 删除定时任务 |
| 🟡 P1 | 执行日志 | 查看任务执行历史和结果 |
| 🟡 P1 | 并发控制 | 设置是否允许并发执行 |

### 11. 系统安全 (Security)

| 优先级 | 功能点 | 描述 |
|--------|--------|------|
| 🔴 P0 | JWT 认证 | 基于 JWT 的用户认证和授权 |
| 🔴 P0 | Token 黑名单 | 支持 Token 失效和黑名单管理 |
| 🔴 P0 | 验证码 | 登录时图形验证码验证 |
| 🟡 P1 | 密码加密 | BCrypt 密码加密存储 |
| 🟡 P1 | 权限控制 | 基于角色的菜单和接口权限控制 |
| 🟢 P2 | 操作审计 | 记录用户操作日志 |

---

## 数据实体清单

| 实体名称 | 表名 | 描述 | 核心字段 |
|---------|------|------|---------|
| SysUser | sys_user | 系统用户 | username, password, nickname, is_admin, status |
| Agent | agent | AI 智能体 | name, description, system_prompt, model_id, mcp_list, skill_list, is_public, creator |
| Model | model | AI 模型 | name, model_name, provider_id, model_type, support_*, price, is_public, creator |
| ModelProvider | model_provider | 模型供应商 | name, display_name, api_key, base_url, is_public, creator |
| McpServer | mcp_server | MCP 服务 | name, description, type, command, url, is_public, creator |
| Skill | skill | 技能 | name, repository_id, skillmd, is_public, creator |
| SkillRepository | skill_repository | 技能仓库 | name, url, description, is_public, creator |
| Channel | channel | 消息通道 | name, type, agent_id, callback_key, webhook_url |
| Session | session | 会话记录 | session_id, agent_id, title, description |
| TokenStats | token_stats | Token 统计 | session_id, agent_id, model_id, prompt_tokens, completion_tokens, cost |
| SysJob | sys_job | 定时任务 | job_name, job_group, job_class, cron_expression, job_status |
| SysJobLog | sys_job_log | 任务日志 | job_id, job_name, execute_time, status, message |
| SysTokenBlacklist | sys_token_blacklist | Token 黑名单 | token, expire_time |
| PlanNote | plan_note | 任务规划 | session_id, task_name, status, result |
| ToolCallLog | tool_call_log | 工具调用日志 | session_id, tool_name, input, output, status |
| ProcessLog | process_log | 处理日志 | session_id, event_type, content |

---

## 技术栈清单

### 后端技术栈
- **框架**: Spring Boot 3.5.8 + Kotlin 2.2.20
- **数据库**: MySQL 8.0 + MyBatis + HikariCP
- **Agent 框架**: AgentScope 1.0.10
- **定时任务**: Quartz
- **安全认证**: JWT (jjwt)
- **API 文档**: SpringDoc OpenAPI
- **测试**: JUnit 5 + Testcontainers

### 前端技术栈
- **框架**: React 18 + TypeScript 5
- **UI 库**: Ant Design 5 + Ant Design Pro
- **构建工具**: Umi 4
- **状态管理**: @umijs/max
- **HTTP 客户端**: Axios
- **代码规范**: Biome

### 基础设施
- **多模块构建**: Maven
- **版本控制**: Git
- **容器化**: Docker (可选)
- **部署**: Cloudflare Pages (前端) + 独立服务器 (后端)

---

## API 端点清单

### 认证相关
- `POST /api/auth/login` - 用户登录
- `POST /api/auth/logout` - 用户登出
- `GET /api/auth/captcha` - 获取验证码
- `GET /api/auth/info` - 获取当前用户信息

### 智能体管理
- `GET /api/agent/list` - 分页查询 Agent 列表
- `GET /api/agent/{id}` - 获取 Agent 详情
- `POST /api/agent` - 创建 Agent
- `PUT /api/agent/{id}` - 更新 Agent
- `PUT /api/agent/{id}/status` - 切换 Agent 状态
- `DELETE /api/agent/{id}` - 删除 Agent

### 模型管理
- `GET /api/model/list` - 分页查询模型列表
- `GET /api/model/{id}` - 获取模型详情
- `POST /api/model` - 创建模型
- `PUT /api/model/{id}` - 更新模型
- `PUT /api/model/{id}/toggle` - 切换模型状态
- `DELETE /api/model/{id}` - 删除模型

### 会话管理
- `GET /api/session/list` - 分页查询会话列表
- `GET /api/session/{id}` - 获取会话详情
- `GET /api/session/agent/{agentId}` - 查询 Agent 的会话列表
- `POST /api/session` - 创建会话
- `PUT /api/session/{id}` - 更新会话
- `DELETE /api/session/{id}` - 删除会话

### Token 监控
- `GET /api/token-stats/list` - 分页查询 Token 统计
- `GET /api/token-stats/total` - 获取总消耗统计
- `GET /api/token-stats/by-date` - 按日期统计
- `GET /api/token-stats/by-model` - 按模型统计
- `POST /api/token-stats` - 保存 Token 统计
- `DELETE /api/token-stats/{id}` - 删除统计记录

### 其他模块
- MCP 服务: `/api/mcp-server/*`
- 技能管理: `/api/skill/*`, `/api/skill-repository/*`
- Channel: `/api/channel/*`
- 用户管理: `/api/user/*`
- 定时任务: `/api/job/*`

---

## 完整性检查

### ✅ 已覆盖的功能
- [x] 智能体全生命周期管理
- [x] 多模型供应商和模型管理
- [x] MCP 服务集成
- [x] 技能库管理
- [x] 多渠道接入
- [x] 会话管理
- [x] Token 消耗监控
- [x] 用户权限管理
- [x] 定时任务调度
- [x] 安全认证体系

### ⚠️ 待完善的功能
- [ ] Agent 性能监控（响应时间、并发数）
- [ ] 模型费用预算和限额控制
- [ ] Agent 模板市场（预置常用 Agent 配置）
- [ ] 批量操作（批量启用/禁用/删除）
- [ ] 数据导入导出（Excel/JSON）
- [ ] Webhook 事件通知
- [ ] 多语言支持（i18n）
- [ ] 移动端适配

### 🔍 潜在风险点
1. **API Key 安全**: 需要加密存储模型供应商的 API Key
2. **并发控制**: 大量 Agent 同时运行时的资源管理
3. **Token 费用**: 需要设置费用上限防止超额消耗
4. **数据隔离**: 多用户环境下的数据可见性控制（is_public + creator）

---

## 优先级总结

| 优先级 | 功能模块数 | 占比 | 说明 |
|--------|-----------|------|------|
| 🔴 P0 | 8 个核心模块 | 60% | 必须实现的基础功能 |
| 🟡 P1 | 15 个增强功能 | 30% | 重要但可延后的功能 |
| 🟢 P2 | 8 个优化功能 | 10% | 体验优化和高级功能 |

**MVP 版本范围**: P0 功能（智能体管理 + 模型管理 + 会话交互 + 用户认证）  
**V1.0 版本范围**: P0 + P1 功能  
**V2.0 版本范围**: 全部功能
