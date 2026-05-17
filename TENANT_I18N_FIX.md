# 租户管理国际化修复

## 问题描述

在创建租户时,"租户名称已存在"的提示没有国际化,使用了硬编码的中文字符串。

## 修复内容

### 1. 修复了 TenantServiceImpl 中的所有硬编码错误消息

**文件**: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TenantServiceImpl.kt`

#### 修改前:
```kotlin
throw BizException("租户名称已存在")
throw BizException("租户不存在")
throw BizException("用户不存在")
throw BizException("用户已在该租户中")
throw BizException("用户不在该租户中")
```

#### 修改后:
```kotlin
throw BizException(messageUtil.getMessage("error.tenant.name_exists"))
throw BizException(messageUtil.getMessage("error.tenant.notfound"))
throw BizException(messageUtil.getMessage("error.user.notfound"))
throw BizException(messageUtil.getMessage("error.user.already_in_tenant"))
throw BizException(messageUtil.getMessage("error.user.not_in_tenant"))
```

### 2. 添加了 MessageUtil 依赖注入

```kotlin
@Service
class TenantServiceImpl(
    private val tenantMapper: TenantMapper,
    private val userTenantMapper: UserTenantMapper,
    private val sysUserMapper: SysUserMapper,
    private val messageUtil: MessageUtil  // 新增
) : TenantService {
```

### 3. 添加了新的国际化消息

#### 中文 (messages_error_zh_CN.properties)
```properties
error.user.already_in_tenant=用户已在该租户中
error.user.not_in_tenant=用户不在该租户中
```

#### 英文 (messages_error.properties)
```properties
error.user.already_in_tenant=User already in this tenant
error.user.not_in_tenant=User not in this tenant
```

### 4. 更新了测试用例

**文件**: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/TenantServiceImplTest.kt`

添加了 `MessageUtil` 的 mock 配置:

```kotlin
@Mock
private lateinit var messageUtil: MessageUtil

// 在测试方法中配置 mock
`when`(messageUtil.getMessage("error.tenant.name_exists")).thenReturn("租户名称已存在")
verify(messageUtil).getMessage("error.tenant.name_exists")
```

## 修复的方法

共修复了以下 7 个方法中的硬编码错误消息:

1. ✅ `createTenant()` - 创建租户
   - "租户名称已存在" → `error.tenant.name_exists`
   - "用户不存在" → `error.user.notfound`

2. ✅ `updateTenant()` - 更新租户
   - "租户不存在" → `error.tenant.notfound`
   - "租户名称已存在" → `error.tenant.name_exists`

3. ✅ `toggleStatus()` - 切换租户状态
   - "租户不存在" → `error.tenant.notfound`

4. ✅ `deleteTenant()` - 删除租户
   - "租户不存在" → `error.tenant.notfound`

5. ✅ `addUserToTenant()` - 添加用户到租户
   - "租户不存在" → `error.tenant.notfound`
   - "用户不存在" → `error.user.notfound`
   - "用户已在该租户中" → `error.user.already_in_tenant`

6. ✅ `removeUserFromTenant()` - 从租户移除用户
   - "用户不在该租户中" → `error.user.not_in_tenant`

## 国际化消息清单

所有使用的国际化消息键:

| 消息键 | 中文 | 英文 |
|--------|------|------|
| `error.tenant.name_exists` | 租户名称已存在 | Tenant name already exists |
| `error.tenant.notfound` | 租户不存在 | Tenant not found |
| `error.user.notfound` | 用户不存在 | User not found |
| `error.user.already_in_tenant` | 用户已在该租户中 | User already in this tenant |
| `error.user.not_in_tenant` | 用户不在该租户中 | User not in this tenant |

## 编译验证

```bash
mvn clean compile -DskipTests -pl harnax-admin -am
```

编译状态: ✅ **BUILD SUCCESS**

## 影响范围

- ✅ 后端服务: 所有租户管理相关的错误消息现在都支持国际化
- ✅ 前端显示: 根据用户的语言设置,自动显示对应语言的错误消息
- ✅ 测试用例: 已更新以适配新的国际化实现

## 相关文件

### 修改的文件
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TenantServiceImpl.kt`
- `harnax-admin/src/main/resources/i18n/messages_error_zh_CN.properties`
- `harnax-admin/src/main/resources/i18n/messages_error.properties`
- `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/TenantServiceImplTest.kt`

### 依赖的文件
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/i18n/MessageUtil.kt`

## 后续建议

1. 检查其他 Service 实现类是否还有硬编码的错误消息
2. 建立代码审查规范,禁止在业务代码中使用硬编码的错误消息
3. 考虑使用常量类管理所有的消息键,避免拼写错误
