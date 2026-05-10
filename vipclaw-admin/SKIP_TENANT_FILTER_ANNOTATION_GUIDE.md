# @SkipTenantFilter 注解使用指南

## 功能说明

`@SkipTenantFilter` 注解用于标记需要跳过租户过滤的 Mapper 方法。当方法被此注解标记后，MybatisTenantInterceptor 不会为该方法的 SQL 添加 `tenant_id` 过滤条件。

## 使用场景

1. **系统级查询**：查询所有租户列表
2. **跨租户统计**：全局数据统计和分析
3. **管理员操作**：系统管理员需要查看所有租户数据
4. **公共配置**：所有租户共享的配置数据查询

## 使用方法

### 1. 在 Mapper 接口方法上添加注解

```kotlin
package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.annotation.SkipTenantFilter
import com.vipamp.vipclaw.admin.entity.Tenant
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select

@Mapper
interface TenantMapper {
    
    /**
     * 查询所有租户（跳过租户过滤）
     */
    @SkipTenantFilter
    fun selectAllTenants(): List<Tenant>
    
    /**
     * 根据ID查询租户（跳过租户过滤）
     */
    @SkipTenantFilter
    fun selectById(id: Long): Tenant?
    
    /**
     * 统计所有租户数量
     */
    @SkipTenantFilter
    @Select("SELECT COUNT(*) FROM tenant")
    fun countAllTenants(): Int
}
```

### 2. XML Mapper 中使用

对于 XML 中定义的 SQL，需要在对应的 Mapper 接口方法上添加注解：

```kotlin
@Mapper
interface ModelProviderMapper {
    
    /**
     * 查询所有启用的模型服务商（跳过租户过滤）
     */
    @SkipTenantFilter
    fun selectAllEnabledProviders(): List<ModelProvider>
}
```

XML 文件：
```xml
<select id="selectAllEnabledProviders" resultMap="BaseResultMap">
    SELECT * FROM model_provider 
    WHERE status = 1 
    ORDER BY sort_order ASC
</select>
```

## 注意事项

1. **谨慎使用**：只在确实需要跨租户查询时使用，避免破坏数据隔离
2. **权限控制**：建议在 Service 层或 Controller 层额外检查用户权限（如isAdmin）
3. **日志记录**：拦截器会记录跳过租户过滤的日志，便于审计

## 示例：带权限检查的完整用法

```kotlin
@Service
class TenantServiceImpl(
    private val tenantMapper: TenantMapper
) : TenantService {
    
    override fun getAllTenants(currentUser: SysUser): List<Tenant> {
        // 检查是否为管理员
        if (currentUser.isAdmin != 1) {
            throw BizException("只有管理员可以查看所有租户")
        }
        
        // 调用标记了 @SkipTenantFilter 的方法
        return tenantMapper.selectAllTenants()
    }
}
```

## 技术实现

- **注解位置**：`com.vipamp.vipclaw.admin.annotation.SkipTenantFilter`
- **拦截器**：`com.vipamp.vipclaw.admin.interceptor.MybatisTenantInterceptor`
- **检测方式**：通过反射检查 Mapper 接口方法是否标记了该注解

## 调试日志

启用 DEBUG 日志可以看到跳过租户过滤的信息：

```
[MyBatis租户拦截器] 方法标记了 @SkipTenantFilter，跳过租户过滤
```
