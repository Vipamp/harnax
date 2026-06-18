# 多租户用户管理设计文档

> **创建日期**: 2026-04-28  
> **最后更新**: 2026-04-28  
> **状态**: 已批准  
> **变更提案**: `openspec/changes/multi-tenant-user-management/`

---

## 1. 设计概述

### 1.1 目标

为 harnax 项目实现多租户用户管理功能，支持企业版 SaaS 场景下的租户隔离、租户管理员、租户内用户管理，以及用户在不同租户间的切换。

### 1.2 范围（第一期 MVP）

**包含**：
- ✅ tenant 表 CRUD
- ✅ user_tenant 表管理
- ✅ 租户管理员绑定
- ✅ 租户状态管理（启用/禁用）
- ✅ 租户内用户 CRUD
- ✅ 租户切换功能
- ✅ JWT Token 携带 tenantId
- ✅ 登录时检查租户归属
- ✅ 前端租户管理页面
- ✅ 前端租户切换组件

**不包含**：
- ❌ 业务表添加 tenant_id（agent、mcp、skill 等）
- ❌ 业务数据的租户隔离
- ❌ 租户级别的资源管理
- ❌ 租户邀请码
- ❌ 租户计费和统计

---

## 2. 核心设计决策

| # | 决策点 | 方案 | 理由 |
|---|--------|------|------|
| 1 | 数据隔离策略 | 共享数据库 + tenant_id 字段 | 运维成本低，适合中小型 SaaS |
| 2 | 用户-租户关系 | 多对多关联表（user_tenant） | 灵活支持用户属于多个租户 |
| 3 | 租户上下文传递 | JWT Token + X-Tenant-ID 请求头 | 避免每次查询租户，支持快速切换 |
| 4 | 租户管理员标识 | user_tenant.role 字段 | 避免冗余，支持多管理员 |
| 5 | 全局管理员权限 | 独立于租户角色的特殊权限 | 全局管理员在任何租户中自动拥有全部权限 |
| 6 | 租户删除策略 | 删除前检查依赖 | 检查 agent、session、mcp、skill，有则禁止删除 |
| 7 | 用户无租户处理 | 保留用户但无法登录 | 用户记录保留，登录时拒绝并提示联系管理员 |
| 8 | tenant_id 迁移范围 | 仅新增租户相关表 | 第一期不改业务表，降低风险 |
| 9 | 资源归属判断 | 第一期不考虑租户隔离 | 关键业务表查询跳过租户过滤 |
| 10 | 多租户启用策略 | 编译时 Profile 控制 | 个人版代码不包含多租户逻辑 |

---

## 3. 数据库设计

### 3.1 tenant 表

```sql
CREATE TABLE `tenant` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '租户ID',
    `name` VARCHAR(100) NOT NULL COMMENT '租户名称',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态（0:禁用 1:启用）',
    `creator` VARCHAR(100) NOT NULL COMMENT '创建人',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';
```

**字段说明**：
- `name`: 租户名称，唯一约束
- `status`: 租户状态（0:禁用 1:启用），控制用户是否可以切换到该租户
- `creator`: 创建人（全局管理员用户名）
- `active`: 逻辑删除标识（0:已删除 1:正常）

### 3.2 user_tenant 表

```sql
CREATE TABLE `user_tenant` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `user_id` BIGINT(20) NOT NULL COMMENT '用户ID',
    `tenant_id` BIGINT(20) NOT NULL COMMENT '租户ID',
    `role` VARCHAR(50) DEFAULT 'member' COMMENT '角色（admin/member）',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态（0:禁用 1:启用）',
    `joined_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_tenant` (`user_id`, `tenant_id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-租户关联表';
```

**字段说明**：
- `role`: 用户在租户中的角色（admin: 租户管理员, member: 普通成员）
- `status`: 用户在该租户下的状态（0:禁用 1:启用）
- `joined_at`: 用户加入租户的时间

### 3.3 默认租户初始化

系统启动时自动创建默认租户（仅个人版）：

```sql
INSERT INTO `tenant` (`name`, `status`, `creator`, `active`) 
VALUES ('默认租户', 1, 'system', 1);
```

然后将所有现有用户关联到默认租户：

```sql
INSERT INTO `user_tenant` (`user_id`, `tenant_id`, `role`, `status`)
SELECT id, 1, 'admin', 1 FROM `sys_user` WHERE `active` = 1;
```

---

## 4. 架构设计

### 4.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    前端（harnax-webui）                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ 租户管理页面  │  │ 用户管理页面  │  │ 租户切换组件  │   │
│  │ /system/     │  │ /system/user │  │ TenantSwitcher│   │
│  │ tenant       │  │              │  │              │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
└────────────────────────┬────────────────────────────────┘
                         │ HTTP + X-Tenant-ID 请求头
┌────────────────────────▼────────────────────────────────┐
│                后端（harnax-admin）                       │
│  ┌──────────────────────────────────────────────────┐   │
│  │         TenantInterceptor（拦截器）                │   │
│  │  - 解析 X-Tenant-ID 请求头                        │   │
│  │  - 验证用户是否属于该租户                          │   │
│  │  - 注入 TenantContext（ThreadLocal）              │   │
│  └──────────────────────────────────────────────────┘   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ Tenant       │  │ User         │  │ JWT Service  │   │
│  │ Controller   │  │ Controller   │  │              │   │
│  │              │  │ (重构)       │  │ - Token 携带  │   │
│  │ - 租户 CRUD  │  │ - 租户内用户  │  │   tenantId   │   │
│  │ - 状态管理   │  │   CRUD       │  │ - Token 刷新  │   │
│  │ - 用户列表   │  │ - 角色管理   │  │   继承上下文  │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
└──────────────────┬──────────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────────────┐
│                   数据库（MySQL）                          │
│  ┌──────────────┐  ┌──────────────┐                     │
│  │ tenant       │  │ user_tenant  │                     │
│  └──────────────┘  └──────────────┘                     │
└─────────────────────────────────────────────────────────┘
```

### 4.2 包结构

```
com.agnetix.harnax.admin
├── entity/
│   ├── TenantEntity.kt              # 租户实体
│   └── UserTenantEntity.kt          # 用户-租户关联实体
├── dto/
│   ├── request/
│   │   ├── CreateTenantRequest.kt   # 创建租户请求
│   │   ├── UpdateTenantRequest.kt   # 更新租户请求
│   │   └── SwitchTenantRequest.kt   # 切换租户请求
│   └── response/
│       ├── TenantResponse.kt        # 租户响应
│       └── UserTenantResponse.kt    # 用户-租户关联响应
├── mapper/
│   ├── TenantMapper.kt              # 租户 Mapper
│   └── UserTenantMapper.kt          # 用户-租户 Mapper
├── service/
│   ├── TenantService.kt             # 租户 Service 接口
│   ├── impl/
│   │   └── TenantServiceImpl.kt     # 租户 Service 实现
│   ├── UserTenantService.kt         # 用户-租户 Service 接口
│   └── impl/
│       └── UserTenantServiceImpl.kt # 用户-租户 Service 实现
├── controller/
│   ├── TenantController.kt          # 租户 Controller
│   └── AuthController.kt            # 认证 Controller（重构）
├── config/
│   └── TenantWebMvcConfig.kt        # 租户拦截器配置
├── interceptor/
│   └── TenantInterceptor.kt         # 租户上下文拦截器
└── context/
    └── TenantContext.kt             # 租户上下文（ThreadLocal）
```

---

## 5. 核心流程

### 5.1 租户创建流程

```
全局管理员
  ↓ 选择租户管理员用户
  ↓ 输入租户名称
  ↓ POST /api/tenant
  ↓
TenantController
  ↓ 验证全局管理员权限（is_admin=1）
  ↓ 创建 tenant 记录
  ↓ 创建 user_tenant 关联（role='admin'）
  ↓ 返回租户信息
```

**权限验证**：
- 仅全局管理员（`is_admin=1`）可以创建租户
- 租户管理员和普通成员无权创建租户

### 5.2 租户切换流程

```
用户登录
  ↓ 查询 user_tenant 获取用户所属租户列表
  ↓ 返回租户列表给前端
  ↓
前端展示租户切换组件
  ↓ 用户选择目标租户
  ↓ POST /api/auth/switch-tenant { tenantId: 2 }
  ↓
AuthController
  ↓ 验证用户是否属于该租户（user_tenant 表）
  ↓ 验证租户状态（status=1）
  ↓ 生成新 JWT Token（包含新 tenantId）
  ↓ 返回新 Token
  ↓
前端更新 Token
  ↓ 刷新页面数据
```

**JWT Token 结构**：
```json
{
  "sub": "username",
  "userId": 123,
  "tenantId": 2,
  "isAdmin": 0,
  "iat": 1234567890,
  "exp": 1234575090
}
```

### 5.3 用户管理（租户上下文）流程

```
租户管理员访问 /system/user
  ↓ 前端请求 GET /api/user?tenantId=2
  ↓ 或请求头携带 X-Tenant-ID: 2
  ↓
TenantInterceptor
  ↓ 解析 tenantId
  ↓ 验证用户是否有该租户的管理权限
  │   ├─ 全局管理员：直接通过
  │   └─ 租户管理员：检查 user_tenant.role='admin'
  ↓ 注入 TenantContext
  ↓
UserController
  ↓ 从 TenantContext 获取 tenantId
  ↓ 查询 user_tenant 获取该租户下的用户列表
  ↓ JOIN sys_user 获取用户详情
  ↓ 返回用户列表
```

### 5.4 登录验证流程（新增租户检查）

```
用户输入用户名密码
  ↓ POST /api/auth/login
  ↓
AuthController
  ↓ 验证用户名密码
  ↓ 查询 user_tenant 检查用户是否属于任何租户
  ↓
  ├─ 有租户记录
  │   ↓ 选择第一个租户作为默认租户
  │   ↓ 生成 JWT Token（包含 tenantId）
  │   ↓ 返回 Token + 租户列表
  │
  └─ 无租户记录（被所有租户移除）
      ↓ 拒绝登录
      ↓ 返回错误："您的账号暂无可用租户，请联系管理员"
```

### 5.5 租户删除流程

```
全局管理员请求删除租户
  ↓ DELETE /api/tenant/{id}
  ↓
TenantController
  ↓ 验证全局管理员权限
  ↓ 检查租户下是否有可用资源
  │   ├─ 查询是否有可用 agent
  │   ├─ 查询是否有可用 session
  │   ├─ 查询是否有可用 mcp_server
  │   └─ 查询是否有可用 skill（预留）
  │
  ├─ 有资源
  │   ↓ 返回错误："该租户下存在可用资源，无法删除"
  │
  └─ 无资源
      ↓ 标记租户为已删除（active=0）
      ↓ 解除所有用户关联（删除 user_tenant 记录）
      ↓ 返回成功
```

---

## 6. 关键组件设计

### 6.1 TenantContext（租户上下文）

```kotlin
package com.agnetix.harnax.admin.context

object TenantContext {
    private val CONTEXT = ThreadLocal<Long>()

    fun setTenantId(tenantId: Long) {
        CONTEXT.set(tenantId)
    }

    fun getTenantId(): Long? {
        return CONTEXT.get()
    }

    fun clear() {
        CONTEXT.remove()
    }
}
```

**使用方式**：
```kotlin
// 在拦截器中设置
TenantContext.setTenantId(tenantId)

// 在 Service 中获取
val tenantId = TenantContext.getTenantId()
    ?: throw BusinessException("租户上下文缺失")

// 请求结束后清理
TenantContext.clear()
```

### 6.2 TenantInterceptor（租户拦截器）

```kotlin
package com.agnetix.harnax.admin.interceptor

class TenantInterceptor(
    private val userTenantMapper: UserTenantMapper,
    private val sysUserMapper: SysUserMapper
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val tenantIdHeader = request.getHeader("X-Tenant-ID")
        
        if (tenantIdHeader.isNullOrBlank()) {
            throw BusinessException("缺少租户上下文")
        }

        val tenantId = tenantIdHeader.toLong()
        val currentUser = SecurityUtils.getCurrentUser()

        // 全局管理员跳过验证
        if (currentUser.isAdmin == 1) {
            TenantContext.setTenantId(tenantId)
            return true
        }

        // 验证用户是否属于该租户
        val userTenant = userTenantMapper.selectByUserIdAndTenantId(
            currentUser.id, tenantId
        )

        if (userTenant == null) {
            throw BusinessException("无权访问该租户")
        }

        if (userTenant.status == 0) {
            throw BusinessException("您在该租户下已被禁用")
        }

        TenantContext.setTenantId(tenantId)
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        TenantContext.clear()
    }
}
```

### 6.3 JWT Token 扩展

```kotlin
fun generateToken(
    userId: Long,
    username: String,
    isAdmin: Int,
    tenantId: Long
): String {
    return Jwts.builder()
        .subject(username)
        .claim("userId", userId)
        .claim("tenantId", tenantId)
        .claim("isAdmin", isAdmin)
        .issuedAt(Date())
        .expiration(Date(System.currentTimeMillis() + expiration))
        .signWith(key)
        .compact()
}
```

---

## 7. API 设计

### 7.1 租户管理 API

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/tenant` | 创建租户 | 全局管理员 |
| GET | `/api/tenant` | 查询租户列表（分页） | 全局管理员 |
| GET | `/api/tenant/{id}` | 查询租户详情 | 全局管理员 |
| PUT | `/api/tenant/{id}` | 更新租户信息 | 全局管理员 |
| DELETE | `/api/tenant/{id}` | 删除租户 | 全局管理员 |
| PUT | `/api/tenant/{id}/status` | 切换租户状态 | 全局管理员 |
| GET | `/api/tenant/{id}/users` | 查询租户下用户列表 | 租户管理员 |
| POST | `/api/tenant/{id}/users` | 添加用户到租户 | 租户管理员 |
| DELETE | `/api/tenant/{id}/users/{userId}` | 从租户移除用户 | 租户管理员 |

### 7.2 认证 API（重构）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/auth/login` | 用户登录（返回租户列表） | 公开 |
| POST | `/api/auth/switch-tenant` | 切换租户 | 已登录用户 |
| GET | `/api/auth/tenants` | 获取用户所属租户列表 | 已登录用户 |

### 7.3 用户管理 API（重构）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/api/user` | 查询租户下用户列表 | 租户管理员 |
| POST | `/api/user` | 在租户下创建用户 | 租户管理员 |
| PUT | `/api/user/{id}` | 更新租户下用户信息 | 租户管理员 |
| DELETE | `/api/user/{id}` | 从租户移除用户 | 租户管理员 |
| PUT | `/api/user/{id}/role` | 更新用户在租户下的角色 | 租户管理员 |

---

## 8. 前端设计

### 8.1 租户管理页面（/system/tenant）

**功能**：
- 租户列表展示（表格、分页、搜索）
- 创建租户对话框（含租户管理员选择）
- 编辑租户信息
- 租户状态切换（启用/禁用）
- 租户用户列表管理（添加/移除用户）

**组件结构**：
```
src/pages/tenant/
├── index.tsx              # 租户列表页面
├── components/
│   ├── CreateTenantModal.tsx   # 创建租户对话框
│   ├── EditTenantModal.tsx     # 编辑租户对话框
│   └── TenantUserList.tsx      # 租户用户列表
└── services/
    └── tenant.ts          # API 服务层
```

### 8.2 租户切换组件（TenantSwitcher）

**位置**：全局 Header 右侧

**功能**：
- 展示用户所属的所有租户（下拉框）
- 切换租户后更新 Token 和刷新页面
- 个人版隐藏该组件

**组件结构**：
```
src/components/
└── TenantSwitcher/
    ├── index.tsx          # 租户切换组件
    └── index.less         # 样式
```

### 8.3 用户管理页面重构（/system/user）

**变更**：
- 用户列表按租户过滤
- 创建用户时在租户下创建
- 添加已存在用户到租户
- 更新用户在租户下的角色
- 从租户移除用户（不删除用户本身）

---

## 9. 测试策略

### 9.1 单元测试

- TenantService 单元测试（Mock Mapper）
- UserTenantService 单元测试
- TenantInterceptor 单元测试
- JWT Service 单元测试

### 9.2 集成测试

- 租户 CRUD 完整流程
- 用户多租户管理完整流程
- 租户切换完整流程
- 登录时租户检查流程

### 9.3 安全测试

- 跨租户数据访问隔离验证
- 租户管理员权限验证
- 全局管理员权限验证

---

## 10. 迁移计划

### 10.1 数据库迁移

1. 创建 tenant 表
2. 创建 user_tenant 表
3. 创建默认租户（仅个人版）
4. 将现有用户关联到默认租户

### 10.2 代码迁移

1. 新增 TenantEntity、UserTenantEntity
2. 新增 TenantMapper、UserTenantMapper
3. 新增 TenantService、UserTenantService
4. 新增 TenantController
5. 重构 AuthController（登录返回租户列表）
6. 重构 UserController（支持租户过滤）
7. 新增 TenantInterceptor
8. 扩展 JWT Service

### 10.3 前端迁移

1. 新增租户管理页面
2. 新增租户切换组件
3. 重构用户管理页面
4. 更新 API 服务层

---

## 11. 风险和缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 跨租户数据泄露 | 高 | MyBatis 拦截器自动注入 tenant_id（第二期） |
| JWT Token 过期后租户上下文丢失 | 中 | Token 刷新时继承租户 ID |
| 租户删除导致孤儿数据 | 中 | 删除前检查依赖 |
| 用户被所有租户移除后仍可登录 | 高 | 登录时检查 user_tenant 表 |
| 性能下降 | 低 | 添加复合索引（第二期） |

---

**文档版本**: 1.0.0  
**最后更新**: 2026-04-28  
**维护者**: AI Assistant
