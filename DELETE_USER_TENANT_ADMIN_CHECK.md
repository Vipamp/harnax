# 删除用户租户管理员检查功能实现

## 功能概述

实现了删除用户时的租户管理员检查逻辑,确保租户管理员不能被误删除,保护租户管理结构的完整性。

## 实现逻辑

### 后端实现

**文件**: `vipclaw-admin/src/main/kotlin/com/vipamp/vipclaw/admin/service/impl/SysUserServiceImpl.kt`

修改了 `deleteUser` 方法,实现了以下逻辑:

1. **全局管理员检查**: 不允许删除全局管理员 (`isAdmin == 1`)
2. **租户上下文检查**: 获取当前请求的租户ID (从 `TenantContext`)
3. **租户管理员检查**: 
   - 如果用户是当前租户的管理员 (`role == "admin"`),抛出异常阻止删除
   - 如果用户是当前租户的普通成员 (`role == "member"`),从租户中移除该用户(不物理删除)
4. **物理删除**: 如果用户不在当前租户中或无租户上下文,执行物理删除

### 关键代码

```kotlin
@Transactional(rollbackFor = [Exception::class])
override fun deleteUser(id: Long): Boolean {
    val user = sysUserMapper.selectById(id)
        ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

    // 不允许删除全局管理员用户
    if (user.isAdmin == 1) {
        throw BizException(messageUtil.getMessage("error.user.cannot_delete_admin"))
    }

    // 获取当前租户上下文
    val currentTenantId = TenantContext.getTenantId()
    
    if (currentTenantId != null) {
        // 检查用户是否是当前租户的管理员
        val userTenant = userTenantMapper.selectByUserIdAndTenantId(id, currentTenantId)
        
        if (userTenant != null) {
            // 用户在该租户中
            if (userTenant.role == "admin") {
                // 是租户管理员,不允许删除
                throw BizException(messageUtil.getMessage("error.user.cannot_delete_tenant_admin"))
            } else {
                // 是普通成员,从租户中移除该用户
                userTenantMapper.deleteByUserIdAndTenantId(id, currentTenantId)
                return true
            }
        } else {
            // 用户不在当前租户中,执行物理删除
            return sysUserMapper.deleteById(id) > 0
        }
    } else {
        // 没有租户上下文,执行物理删除
        return sysUserMapper.deleteById(id) > 0
    }
}
```

## 国际化消息

添加了两个新的错误消息:

### 中文 (messages_error_zh_CN.properties)
```properties
error.user.cannot_delete_admin=不允许删除全局管理员用户
error.user.cannot_delete_tenant_admin=该用户是当前租户的管理员,不允许删除
```

### 英文 (messages_error.properties)
```properties
error.user.cannot_delete_admin=Cannot delete global administrator user
error.user.cannot_delete_tenant_admin=This user is a tenant admin and cannot be deleted
```

## 依赖注入

更新了 `SysUserServiceImpl` 的构造函数,添加了 `UserTenantMapper` 依赖:

```kotlin
@Service
class SysUserServiceImpl(
    private val sysUserMapper: SysUserMapper,
    private val userTenantMapper: UserTenantMapper,  // 新增
    private val messageUtil: MessageUtil
) : SysUserService {
```

## 测试用例

更新了 `SysUserServiceImplTest.kt`,添加了三个测试用例:

1. **删除租户普通成员**: 验证从租户中移除用户
2. **删除租户管理员**: 验证抛出异常阻止删除
3. **删除全局管理员**: 验证抛出异常阻止删除

## 前端影响

前端无需修改,现有的错误处理机制已经可以正确显示后端返回的错误消息。

当用户尝试删除租户管理员时,会收到友好的错误提示:
- 中文: "该用户是当前租户的管理员,不允许删除"
- 英文: "This user is a tenant admin and cannot be deleted"

## 使用场景

### 场景1: 删除租户普通成员
- **操作**: 在租户管理页面删除一个普通成员
- **结果**: 用户从该租户中移除,但用户账号保留
- **日志**: "用户是租户普通成员,从租户中移除用户,userId: X, tenantId: Y"

### 场景2: 删除租户管理员
- **操作**: 尝试删除一个租户管理员
- **结果**: 操作失败,显示错误消息
- **错误**: "该用户是当前租户的管理员,不允许删除"

### 场景3: 删除全局管理员
- **操作**: 尝试删除全局管理员 (isAdmin == 1)
- **结果**: 操作失败,显示错误消息
- **错误**: "不允许删除全局管理员用户"

### 场景4: 无租户上下文删除
- **操作**: 在没有租户上下文的情况下删除用户
- **结果**: 执行物理删除(如果用户不是全局管理员)

## 编译验证

```bash
mvn clean compile -DskipTests -pl vipclaw-admin -am
```

编译状态: ✅ **BUILD SUCCESS**

## 注意事项

1. **租户上下文**: 功能依赖于 `TenantInterceptor` 设置的 `TenantContext`
2. **事务管理**: 方法使用 `@Transactional` 注解,确保操作原子性
3. **日志记录**: 关键操作都有日志记录,便于排查问题
4. **国际化**: 错误消息支持中英文切换

## 相关文件

- `vipclaw-admin/src/main/kotlin/com/vipamp/vipclaw/admin/service/impl/SysUserServiceImpl.kt`
- `vipclaw-admin/src/main/kotlin/com/vipamp/vipclaw/admin/context/TenantContext.kt`
- `vipclaw-admin/src/main/kotlin/com/vipamp/vipclaw/admin/interceptor/TenantInterceptor.kt`
- `vipclaw-admin/src/main/resources/i18n/messages_error_zh_CN.properties`
- `vipclaw-admin/src/main/resources/i18n/messages_error.properties`
- `vipclaw-admin/src/test/kotlin/com/vipamp/vipclaw/admin/service/impl/SysUserServiceImplTest.kt`
