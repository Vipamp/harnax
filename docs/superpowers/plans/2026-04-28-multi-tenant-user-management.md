# 多租户用户管理实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 harnax 项目实现多租户用户管理功能（第一期 MVP），包括租户 CRUD、租户内用户管理、租户切换功能

**架构：** 共享数据库 + tenant_id 字段隔离，通过 user_tenant 关联表实现用户-租户多对多关系，JWT Token 携带租户上下文，拦截器统一处理租户验证

**技术栈：** Spring Boot 3.5.8、Kotlin 2.2.20、MyBatis 3.0.4、JWT 0.12.3、React 18.3.1、Ant Design 5.25.4

---

## 文件清单

### 新增文件

**数据库迁移**：
- `harnax-admin/src/main/resources/db/migration/V8__create_tenant_table.sql` - 租户表
- `harnax-admin/src/main/resources/db/migration/V9__create_user_tenant_table.sql` - 用户-租户关联表

**Entity**：
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/entity/TenantEntity.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/entity/UserTenantEntity.kt`

**DTO**：
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/request/CreateTenantRequest.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/request/UpdateTenantRequest.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/request/SwitchTenantRequest.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/request/AddUserToTenantRequest.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/response/TenantResponse.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/response/UserTenantResponse.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/response/LoginResponse.kt`

**Mapper**：
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/mapper/TenantMapper.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/mapper/UserTenantMapper.kt`
- `harnax-admin/src/main/resources/mapper/TenantMapper.xml`
- `harnax-admin/src/main/resources/mapper/UserTenantMapper.xml`

**Service**：
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/TenantService.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TenantServiceImpl.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/UserTenantService.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/UserTenantServiceImpl.kt`

**Controller**：
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TenantController.kt`

**Interceptor & Context**：
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/context/TenantContext.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/interceptor/TenantInterceptor.kt`
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/TenantWebMvcConfig.kt`

**JWT**：
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/JwtUtil.kt` (修改)

**前端**：
- `harnax-webui/src/pages/tenant/index.tsx`
- `harnax-webui/src/pages/tenant/components/CreateTenantModal.tsx`
- `harnax-webui/src/pages/tenant/components/EditTenantModal.tsx`
- `harnax-webui/src/pages/tenant/components/TenantUserList.tsx`
- `harnax-webui/src/components/TenantSwitcher/index.tsx`
- `harnax-webui/src/components/TenantSwitcher/index.less`
- `harnax-webui/src/services/tenant.ts`

### 修改文件

**后端**：
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt` - 登录返回租户列表
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/UserController.kt` - 支持租户过滤
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/UserService.kt` - 重构用户管理逻辑
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/UserServiceImpl.kt`

**前端**：
- `harnax-webui/src/pages/user/management/index.tsx` - 重构支持租户上下文
- `harnax-webui/src/services/user.ts` - 更新 API
- `harnax-webui/config/routes.ts` - 添加租户管理路由
- `harnax-webui/src/app.tsx` - 添加全局租户切换组件

---

## 任务分解

### 任务 1：创建数据库迁移脚本

**目标：** 创建 tenant 表和 user_tenant 表的迁移脚本

**文件：**
- 创建：`harnax-admin/src/main/resources/db/migration/V8__create_tenant_table.sql`
- 创建：`harnax-admin/src/main/resources/db/migration/V9__create_user_tenant_table.sql`

**步骤：**

1. 创建 V8__create_tenant_table.sql，包含 tenant 表定义（id, name, status, creator, active, create_time, update_time）
2. 创建 V9__create_user_tenant_table.sql，包含 user_tenant 表定义（id, user_id, tenant_id, role, status, joined_at）
3. 验证 SQL 语法正确性
4. Commit

**规范要求：**
- 遵循 harnax-admin-rules.md 的数据库表设计规范
- 必须包含通用字段：id, status, active, create_time, update_time
- 使用 TINYINT(1) 表示状态和布尔值
- 添加适当的注释和索引

---

### 任务 2：创建 Entity 实体类

**目标：** 创建 TenantEntity 和 UserTenantEntity

**文件：**
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/entity/TenantEntity.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/entity/UserTenantEntity.kt`

**步骤：**

1. 创建 TenantEntity，包含所有字段（id, name, status, creator, active, createTime, updateTime）
2. 创建 UserTenantEntity，包含所有字段（id, userId, tenantId, role, status, joinedAt）
3. 使用 @Schema 注解为每个字段添加描述
4. 实现 Serializable 接口
5. Commit

**规范要求：**
- 继承 Serializable
- 使用 @Schema 注解
- 字段使用 var 声明，有默认值
- 时间字段使用 LocalDateTime

---

### 任务 3：创建 DTO 类

**目标：** 创建请求和响应 DTO

**文件：**
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/request/CreateTenantRequest.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/request/UpdateTenantRequest.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/request/SwitchTenantRequest.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/request/AddUserToTenantRequest.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/response/TenantResponse.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/response/UserTenantResponse.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/response/LoginResponse.kt`

**步骤：**

1. 创建 CreateTenantRequest（name, adminUserId），使用 @NotBlank 验证
2. 创建 UpdateTenantRequest（name?, status?），所有字段可选
3. 创建 SwitchTenantRequest（tenantId），使用 @NotNull 验证
4. 创建 AddUserToTenantRequest（userId, role），使用验证注解
5. 创建 TenantResponse（包含所有展示字段）
6. 创建 UserTenantResponse（包含用户信息和角色）
7. 创建 LoginResponse（token, tenants 列表）
8. Commit

**规范要求：**
- Request 使用 data class
- Response 使用 class
- 使用 Jakarta Validation 注解
- 使用 @Schema 注解添加描述和示例

---

### 任务 4：创建 Mapper 接口和 XML

**目标：** 创建 TenantMapper 和 UserTenantMapper

**文件：**
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/mapper/TenantMapper.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/mapper/UserTenantMapper.kt`
- 创建：`harnax-admin/src/main/resources/mapper/TenantMapper.xml`
- 创建：`harnax-admin/src/main/resources/mapper/UserTenantMapper.xml`

**步骤：**

1. 创建 TenantMapper 接口，定义方法：insert, selectById, selectByName, selectList, updateById, updateStatus, deleteById
2. 创建 UserTenantMapper 接口，定义方法：insert, selectByUserId, selectByTenantId, selectByUserIdAndTenantId, deleteByUserIdAndTenantId, deleteByTenantId, updateRole
3. 创建 TenantMapper.xml，实现所有 SQL 映射
4. 创建 UserTenantMapper.xml，实现所有 SQL 映射
5. Commit

**规范要求：**
- Mapper 接口使用 @Mapper 注解
- XML 文件中 namespace 指向对应接口
- 使用 resultMap 映射复杂对象
- SQL 语句遵循项目现有风格

---

### 任务 5：创建 TenantContext 和拦截器

**目标：** 实现租户上下文管理和请求拦截

**文件：**
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/context/TenantContext.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/interceptor/TenantInterceptor.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/TenantWebMvcConfig.kt`

**步骤：**

1. 创建 TenantContext object，使用 ThreadLocal<Long> 存储租户 ID
2. 创建 TenantInterceptor 类，实现 HandlerInterceptor
3. 在 preHandle 中解析 X-Tenant-ID 请求头
4. 验证用户是否属于该租户（全局管理员跳过验证）
5. 在 afterCompletion 中清理 TenantContext
6. 创建 TenantWebMvcConfig，注册拦截器
7. Commit

**规范要求：**
- TenantContext 使用 object 单例
- 拦截器必须清理 ThreadLocal 防止内存泄漏
- 全局管理员（isAdmin=1）跳过租户验证
- 使用 BusinessException 抛出错误

---

### 任务 6：实现 TenantService

**目标：** 实现租户管理业务逻辑

**文件：**
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/TenantService.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TenantServiceImpl.kt`

**步骤：**

1. 创建 TenantService 接口，定义方法：createTenant, getTenantById, getTenantList, updateTenant, toggleStatus, deleteTenant
2. 创建 TenantServiceImpl 实现类
3. 实现 createTenant 方法：创建租户并绑定管理员
4. 实现 deleteTenant 方法：检查依赖后删除（预留检查逻辑）
5. 添加 @Transactional 注解
6. Commit

**规范要求：**
- Service 接口定义，impl 包下实现
- 使用 @Service 注解
- 事务方法添加 @Transactional
- 业务逻辑不泄露到 Controller

---

### 任务 7：实现 UserTenantService

**目标：** 实现用户-租户关联管理

**文件：**
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/UserTenantService.kt`
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/UserTenantServiceImpl.kt`

**步骤：**

1. 创建 UserTenantService 接口，定义方法：addUserToTenant, removeUserFromTenant, getUserTenants, getTenantUsers, updateUserRole
2. 创建 UserTenantServiceImpl 实现类
3. 实现 addUserToTenant：创建关联，检查是否已存在
4. 实现 removeUserFromTenant：删除关联
5. 实现 getUserTenants：查询用户所属的所有租户
6. Commit

---

### 任务 8：实现 TenantController

**目标：** 实现租户管理 REST API

**文件：**
- 创建：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TenantController.kt`

**步骤：**

1. 创建 TenantController，使用 @RestController 和 @RequestMapping("/api/tenant")
2. 实现 POST /api/tenant - 创建租户
3. 实现 GET /api/tenant - 查询租户列表
4. 实现 GET /api/tenant/{id} - 查询租户详情
5. 实现 PUT /api/tenant/{id} - 更新租户
6. 实现 PUT /api/tenant/{id}/status - 切换状态
7. 实现 DELETE /api/tenant/{id} - 删除租户
8. 实现 GET /api/tenant/{id}/users - 查询租户下用户
9. 实现 POST /api/tenant/{id}/users - 添加用户到租户
10. 实现 DELETE /api/tenant/{id}/users/{userId} - 从租户移除用户
11. 添加 @Tag 和 @Operation 注解
12. Commit

**规范要求：**
- Controller 仅处理请求和响应
- 业务逻辑全部在 Service 层
- 使用 @Valid 验证请求参数
- 统一返回 Result<T> 格式

---

### 任务 9：扩展 JWT 支持租户 ID

**目标：** 在 JWT Token 中添加 tenantId 字段

**文件：**
- 修改：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/JwtUtil.kt`

**步骤：**

1. 修改 generateToken 方法，添加 tenantId 参数
2. 在 JWT claims 中添加 "tenantId" 字段
3. 添加 getTenantIdFromToken 方法
4. 修改 Token 刷新逻辑，继承 tenantId
5. Commit

**JWT Token 结构：**
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

---

### 任务 10：重构 AuthController 登录流程

**目标：** 登录时检查租户归属并返回租户列表

**文件：**
- 修改：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt`

**步骤：**

1. 修改 login 方法：验证密码后查询 user_tenant
2. 如果用户无任何租户，返回错误"您的账号暂无可用租户，请联系管理员"
3. 如果有租户，选择第一个作为默认租户
4. 生成 JWT Token（包含 tenantId）
5. 返回 LoginResponse（token + 租户列表）
6. 添加 POST /api/auth/switch-tenant 接口
7. 添加 GET /api/auth/tenants 接口
8. Commit

---

### 任务 11：重构 UserService 支持租户过滤

**目标：** 用户管理操作在租户上下文中执行

**文件：**
- 修改：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/UserService.kt`
- 修改：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/UserServiceImpl.kt`

**步骤：**

1. 修改 getUserList 方法：从 TenantContext 获取 tenantId
2. 查询 user_tenant 获取该租户下的用户 ID 列表
3. JOIN sys_user 获取用户详情
4. 修改 createUser 方法：创建用户后自动添加到当前租户
5. 修改 deleteUser 方法：仅删除 user_tenant 关联，不删除用户
6. Commit

---

### 任务 12：重构 UserController

**目标：** 用户管理 API 支持租户上下文

**文件：**
- 修改：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/UserController.kt`

**步骤：**

1. 移除全局用户列表查询（改为租户级别）
2. 修改所有查询方法：依赖 TenantInterceptor 设置的上下文
3. 确保所有操作都在租户上下文中执行
4. Commit

---

### 任务 13：创建租户管理前端页面

**目标：** 实现租户管理 UI

**文件：**
- 创建：`harnax-webui/src/pages/tenant/index.tsx`
- 创建：`harnax-webui/src/pages/tenant/components/CreateTenantModal.tsx`
- 创建：`harnax-webui/src/pages/tenant/components/EditTenantModal.tsx`
- 创建：`harnax-webui/src/pages/tenant/components/TenantUserList.tsx`
- 创建：`harnax-webui/src/services/tenant.ts`

**步骤：**

1. 创建 tenant.ts API 服务层
2. 创建租户列表页面（ProTable、分页、搜索）
3. 创建 CreateTenantModal 对话框（表单验证、管理员选择）
4. 创建 EditTenantModal 对话框
5. 创建 TenantUserList 组件（用户列表管理）
6. Commit

---

### 任务 14：创建租户切换组件

**目标：** 实现全局租户切换功能

**文件：**
- 创建：`harnax-webui/src/components/TenantSwitcher/index.tsx`
- 创建：`harnax-webui/src/components/TenantSwitcher/index.less`

**步骤：**

1. 创建 TenantSwitcher 组件（Select 下拉框）
2. 从 initialState 获取用户租户列表
3. 切换时调用 /api/auth/switch-tenant API
4. 更新 Token 并刷新页面
5. 个人版隐藏该组件（根据 REACT_APP_EDITION 判断）
6. Commit

---

### 任务 15：重构前端用户管理页面

**目标：** 用户管理页面支持租户上下文

**文件：**
- 修改：`harnax-webui/src/pages/user/management/index.tsx`
- 修改：`harnax-webui/src/services/user.ts`
- 修改：`harnax-webui/config/routes.ts`
- 修改：`harnax-webui/src/app.tsx`

**步骤：**

1. 在 routes.ts 中添加租户管理路由 /system/tenant
2. 在 app.tsx 中添加全局 TenantSwitcher 组件
3. 修改用户列表 API：携带 X-Tenant-ID 请求头
4. 修改创建用户：在租户下创建
5. 修改删除用户：从租户移除（不删除用户）
6. Commit

---

### 任务 16：编写后端单元测试

**目标：** 为 Service 层编写单元测试

**文件：**
- 创建：`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/TenantServiceTest.kt`
- 创建：`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/UserTenantServiceTest.kt`

**步骤：**

1. 创建 TenantServiceTest，使用 Mockito Kotlin
2. 测试 createTenant 方法
3. 测试 deleteTenant 方法
4. 创建 UserTenantServiceTest
5. 测试 addUserToTenant 方法
6. 运行测试验证通过
7. Commit

---

### 任务 17：编写集成测试

**目标：** 为 Controller 层编写集成测试

**文件：**
- 创建：`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/controller/TenantControllerTest.kt`

**步骤：**

1. 创建 TenantControllerTest，使用 @SpringBootTest
2. 测试创建租户 API
3. 测试查询租户列表 API
4. 测试租户切换 API
5. 运行测试验证通过
6. Commit

---

### 任务 18：端到端验证

**目标：** 验证完整的多租户流程

**步骤：**

1. 启动后端服务（personal profile）
2. 执行数据库迁移脚本
3. 验证默认租户创建
4. 测试登录流程（返回租户列表）
5. 测试租户切换功能
6. 验证用户管理按租户过滤
7. Commit

---
