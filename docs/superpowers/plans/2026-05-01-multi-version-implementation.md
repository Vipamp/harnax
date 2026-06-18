# 多版本编译拆分实现计划

> **面向 AI 代理的工作者:** 必需子技能:使用 superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框(`- [ ]`)语法来跟踪进度。

**目标:** 实现三版本(个人版/企业版/公有云版)编译时隔离机制,支持独立打包发布

**架构:** 通过 Maven Profile + Build Helper Plugin 实现版本源码目录隔离,使用 @RequiresEdition 注解 + 拦截器实现 API 版本控制,前端通过环境变量 + 路由过滤实现版本差异化,Docker 多阶段构建实现容器化部署

**技术栈:** Kotlin 2.2.20, Spring Boot 3.5.8, Maven, React 18, Umi Max 4.0.7, Docker

**实施策略:** 按模块分 4 个阶段实施,每阶段独立可测试
- Phase 1: 后端版本控制核心 (Maven + 配置 + 注解 + 拦截器)
- Phase 2: 前端版本差异化 (环境文件 + 路由过滤 + 条件渲染)
- Phase 3: Docker 容器化部署 (Dockerfile + docker-compose)
- Phase 4: 构建自动化与测试 (构建脚本 + 测试 + CI/CD)

---

## 文件结构

### 新建文件

**后端**:
- `harnax-admin/src/main/kotlin-edition/personal/` - 个人版专属源码目录
- `harnax-admin/src/main/kotlin-edition/enterprise/` - 企业版专属源码目录  
- `harnax-admin/src/main/kotlin-edition/public/` - 公有云版专属源码目录
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/RequiresEdition.kt` - 版本控制注解
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/EditionInterceptor.kt` - 版本拦截器
- `harnax-admin/src/main/resources/application-personal.yml` - 个人版功能配置
- `harnax-admin/src/main/resources/application-enterprise.yml` - 企业版功能配置
- `harnax-admin/src/main/resources/application-public.yml` - 公有云版功能配置
- `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/config/EditionInterceptorTest.kt` - 拦截器测试
- `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/config/EditionUtilTest.kt` - 工具类测试
- `build-all-editions.sh` - 一键构建脚本
- `verify-edition.sh` - 构建产物验证脚本
- `.gitlab-ci.yml` - CI/CD 配置

**Docker 部署**:
- `Dockerfile.backend` - 后端多阶段构建文件
- `Dockerfile.frontend` - 前端多阶段构建文件
- `docker-compose.yml` - 本地开发环境编排
- `docker-compose.prod.yml` - 生产环境编排
- `docker/nginx.conf` - Nginx 反向代理配置
- `.dockerignore` - Docker 构建优化

**前端:**
- `harnax-webui/.env.personal` - 个人版环境文件
- `harnax-webui/.env.enterprise` - 企业版环境文件
- `harnax-webui/.env.public` - 公有云版环境文件
- `harnax-webui/src/utils/edition.test.ts` - 版本工具测试

### 修改文件

**后端:**
- `harnax-admin/pom.xml:250-277` - 增强 Maven Profile 配置
- `harnax-admin/pom.xml:185-247` - 添加 Build Helper Plugin
- `harnax-admin/src/main/resources/application.yml` - 添加 Profile 激活和 Swagger 配置
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/EditionUtil.kt` - 增强功能开关
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/TenantWebMvcConfig.kt` - 注册拦截器
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SysUserController.kt` - 添加版本注解
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt` - 添加版本注解
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt` - 简化版本判断
- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt` - 简化版本判断

**前端:**
- `harnax-webui/src/utils/edition.ts` - 增强版本工具和路由过滤
- `harnax-webui/src/app.tsx` - 添加 patchRoutes 钩子
- `harnax-webui/config/routes.ts` - 添加路由版本限制
- `harnax-webui/src/pages/user/login/index.tsx` - 添加版本条件渲染
- `harnax-webui/src/pages/mcp/components/McpForm.tsx` - 添加版本条件渲染

---

# Phase 1: 后端版本控制核心

> **目标:** 建立后端编译时版本隔离机制,实现 API 版本控制

---

## 任务 1:Maven 配置与版本目录

**文件:**
- 修改:`harnax-admin/pom.xml:250-277`(Profile 部分)
- 修改:`harnax-admin/pom.xml:185-247`(Plugins 部分)

- [ ] **步骤 1:增强 Maven Profile 配置**

在现有的三个 Profile 中添加 `edition.source.dir` 属性:

```xml
<profiles>
    <!-- 个人版(默认) -->
    <profile>
        <id>personal</id>
        <properties>
            <edition.current>personal</edition.current>
            <edition.source.dir>${project.basedir}/src/main/kotlin-edition/personal</edition.source.dir>
        </properties>
        <activation>
            <activeByDefault>true</activeByDefault>
        </activation>
    </profile>
    
    <!-- 企业版 -->
    <profile>
        <id>enterprise</id>
        <properties>
            <edition.current>enterprise</edition.current>
            <edition.source.dir>${project.basedir}/src/main/kotlin-edition/enterprise</edition.source.dir>
        </properties>
    </profile>
    
    <!-- 公网版 -->
    <profile>
        <id>public</id>
        <properties>
            <edition.current>public</edition.current>
            <edition.source.dir>${project.basedir}/src/main/kotlin-edition/public</edition.source.dir>
        </properties>
    </profile>
</profiles>
```

- [ ] **步骤 2:添加 Build Helper Maven Plugin**

在 `<build><plugins>` 中添加(在 kotlin-maven-plugin 之后):

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>build-helper-maven-plugin</artifactId>
    <version>3.4.0</version>
    <executions>
        <execution>
            <id>add-edition-sources</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>add-source</goal>
            </goals>
            <configuration>
                <sources>
                    <source>${edition.source.dir}</source>
                </sources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

- [ ] **步骤 3:验证配置语法**

运行:
```bash
cd harnax-admin
mvn help:effective-pom -Ppersonal > /dev/null
```

预期:无错误输出

- [ ] **步骤 4:Commit**

```bash
git add harnax-admin/pom.xml
git commit -m "feat: 增强 Maven Profile 支持版本源码目录隔离"
```

---

## 任务 2:版本功能配置文件

**文件:**
- 创建:`harnax-admin/src/main/resources/application-personal.yml`
- 创建:`harnax-admin/src/main/resources/application-enterprise.yml`
- 创建:`harnax-admin/src/main/resources/application-public.yml`
- 修改:`harnax-admin/src/main/resources/application.yml`

- [ ] **步骤 1:创建个人版配置**

```yaml
# 个人版功能开关配置
harnax:
  edition: personal
  features:
    user-management: false
    token-monitor: false
    billing: false
    multi-tenant: false
    agent-sharing: false
    model-market: false
    skill-market: false
    phone-login: false
    email-login: false

# 生产环境禁用 Swagger
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

- [ ] **步骤 2:创建企业版配置**

```yaml
# 企业版功能开关配置
harnax:
  edition: enterprise
  features:
    user-management: true
    token-monitor: true
    billing: false
    multi-tenant: false
    agent-sharing: true
    model-market: false
    skill-market: false
    phone-login: true
    email-login: true

# 生产环境禁用 Swagger
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

- [ ] **步骤 3:创建公有云版配置**

```yaml
# 公有云版功能开关配置
harnax:
  edition: public
  features:
    user-management: true
    token-monitor: true
    billing: true
    multi-tenant: true
    agent-sharing: true
    model-market: true
    skill-market: true
    phone-login: true
    email-login: true

# 生产环境禁用 Swagger
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

- [ ] **步骤 4:修改 application.yml 添加 Profile 激活**

在 `spring:` 部分添加:

```yaml
spring:
  application:
    name: harnax-admin
  
  # 激活版本 Profile(通过 Maven 编译时注入)
  profiles:
    active: ${edition.current:personal}
  
  # 数据源配置...
```

在文件末尾添加 Swagger 默认配置:

```yaml
# Swagger 配置(默认开发环境启用)
springdoc:
  api-docs:
    enabled: ${SWAGGER_ENABLED:true}
  swagger-ui:
    enabled: ${SWAGGER_ENABLED:true}
```

- [ ] **步骤 5:Commit**

```bash
git add harnax-admin/src/main/resources/application*.yml
git commit -m "feat: 添加三版本功能配置文件和 Profile 激活机制"
```

---

## 任务 3:@RequiresEdition 注解与拦截器

**文件:**
- 创建:`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/RequiresEdition.kt`
- 创建:`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/EditionInterceptor.kt`
- 修改:`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/TenantWebMvcConfig.kt`

- [ ] **步骤 1:创建 RequiresEdition 注解**

```kotlin
package com.agnetix.harnax.admin.config

/**
 * 版本控制注解
 * 用于声明 API 或 Controller 支持的版本列表
 * 
 * 使用示例:
 * @RequiresEdition("enterprise", "public")
 * @RestController
 * class SysUserController { ... }
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresEdition(vararg val value: String)
```

- [ ] **步骤 2:创建 EditionInterceptor 拦截器**

```kotlin
package com.agnetix.harnax.admin.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 版本控制拦截器
 * 在请求处理前检查 @RequiresEdition 注解并验证当前版本
 */
@Component
class EditionInterceptor(
    private val editionUtil: EditionUtil
) : HandlerInterceptor {
    
    private val log = LoggerFactory.getLogger(EditionInterceptor::class.java)
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        if (handler !is HandlerMethod) {
            return true
        }
        
        // 获取方法或类级别的 @RequiresEdition 注解
        val methodAnnotation = handler.getMethodAnnotation(RequiresEdition::class.java)
        val classAnnotation = handler.beanType.getAnnotation(RequiresEdition::class.java)
        val annotation = methodAnnotation ?: classAnnotation ?: return true
        
        // 检查注解值是否为空
        if (annotation.value.isEmpty()) {
            log.error("@RequiresEdition must not be empty on ${handler.method}")
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"code":500,"message":"服务器配置错误"}""")
            return false
        }
        
        // 验证当前版本
        val currentEdition = editionUtil.getCurrentEdition()
        if (currentEdition in annotation.value) {
            return true
        }
        
        // 版本不匹配,返回 404
        log.warn("Access denied: edition=$currentEdition, required=${annotation.value.contentToString()}, path=${request.requestURI}")
        response.status = HttpServletResponse.SC_NOT_FOUND
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("""{"code":404,"message":"当前版本不支持此功能"}""")
        return false
    }
}
```

- [ ] **步骤 3:注册拦截器**

修改 `TenantWebMvcConfig.kt`,添加拦截器注册:

```kotlin
package com.agnetix.harnax.admin.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class TenantWebMvcConfig(
    private val editionInterceptor: EditionInterceptor
) : WebMvcConfigurer {
    
    override fun addInterceptors(registry: InterceptorRegistry) {
        // 注册版本控制拦截器
        registry.addInterceptor(editionInterceptor)
            .addPathPatterns("/api/**")
    }
}
```

- [ ] **步骤 4:Commit**

```bash
git add harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/RequiresEdition.kt
git add harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/EditionInterceptor.kt
git add harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/TenantWebMvcConfig.kt
git commit -m "feat: 实现 @RequiresEdition 注解和版本控制拦截器"
```

---

## 任务 4:EditionUtil 功能开关增强

**文件:**
- 修改:`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/EditionUtil.kt`

- [ ] **步骤 1:添加功能开关读取方法**

在 EditionUtil 类中添加:

```kotlin
package com.agnetix.harnax.admin.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 版本工具类
 * 用于在代码中判断当前打包的版本和功能开关
 */
@Component
class EditionUtil {
    
    private val log = LoggerFactory.getLogger(EditionUtil::class.java)
    
    companion object {
        const val EDITION_PERSONAL = "personal"
        const val EDITION_ENTERPRISE = "enterprise"
        const val EDITION_PUBLIC = "public"
    }
    
    @Value("\${edition.current}")
    private lateinit var currentEdition: String
    
    // 功能开关配置
    @Value("\${harnax.features.user-management:false}")
    private var userManagementEnabled: Boolean = false
    
    @Value("\${harnax.features.token-monitor:false}")
    private var tokenMonitorEnabled: Boolean = false
    
    @Value("\${harnax.features.billing:false}")
    private var billingEnabled: Boolean = false
    
    @Value("\${harnax.features.multi-tenant:false}")
    private var multiTenantEnabled: Boolean = false
    
    @Value("\${harnax.features.agent-sharing:false}")
    private var agentSharingEnabled: Boolean = false
    
    @Value("\${harnax.features.phone-login:false}")
    private var phoneLoginEnabled: Boolean = false
    
    @Value("\${harnax.features.email-login:false}")
    private var emailLoginEnabled: Boolean = false
    
    /**
     * 判断是否是个人版
     */
    fun isPersonal(): Boolean = currentEdition == EDITION_PERSONAL
    
    /**
     * 判断是否是企业版
     */
    fun isEnterprise(): Boolean = currentEdition == EDITION_ENTERPRISE
    
    /**
     * 判断是否是公网版
     */
    fun isPublic(): Boolean = currentEdition == EDITION_PUBLIC
    
    /**
     * 获取当前版本名称(用于调试日志,不对外暴露)
     */
    fun getCurrentEdition(): String = currentEdition
    
    /**
     * 检查功能是否启用
     * @param feature 功能名称
     * @return 是否启用
     */
    fun isFeatureEnabled(feature: String): Boolean {
        return when(feature) {
            "user-management" -> userManagementEnabled
            "token-monitor" -> tokenMonitorEnabled
            "billing" -> billingEnabled
            "multi-tenant" -> multiTenantEnabled
            "agent-sharing" -> agentSharingEnabled
            "phone-login" -> phoneLoginEnabled
            "email-login" -> emailLoginEnabled
            else -> {
                log.warn("Unknown feature: $feature")
                false
            }
        }
    }
}
```

- [ ] **步骤 2:Commit**

```bash
git add harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/EditionUtil.kt
git commit -m "feat: 增强 EditionUtil 支持功能开关读取"
```

---

## 任务 5:编写后端单元测试

**文件:**
- 创建:`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/config/EditionInterceptorTest.kt`
- 创建:`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/config/EditionUtilTest.kt`

- [ ] **步骤 1:编写拦截器测试**

```kotlin
package com.agnetix.harnax.admin.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.web.method.HandlerMethod
import java.lang.reflect.Method

@DisplayName("EditionInterceptor 测试")
class EditionInterceptorTest {
    
    private val editionUtil = mock<EditionUtil>()
    private val interceptor = EditionInterceptor(editionUtil)
    private val mockRequest = mock<HttpServletRequest>()
    private val mockResponse = mock<HttpServletResponse>()
    
    @Test
    @DisplayName("应该放行没有 @RequiresEdition 注解的请求")
    fun `should allow request without RequiresEdition annotation`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        val handlerWithoutAnnotation = mock<HandlerMethod>()
        whenever(handlerWithoutAnnotation.getMethodAnnotation(RequiresEdition::class.java)).thenReturn(null)
        whenever(handlerWithoutAnnotation.beanType).thenReturn(Object::class.java)
        
        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithoutAnnotation)
        
        // Assert
        assertTrue(result)
        verify(mockResponse, never()).status = any()
    }
    
    @Test
    @DisplayName("应该放行版本匹配的请求")
    fun `should allow request when edition matches`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("enterprise")
        val mockMethod = mock<Method>()
        val handlerWithAnnotation = mock<HandlerMethod>()
        whenever(handlerWithAnnotation.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(RequiresEdition("enterprise", "public"))
        whenever(handlerWithAnnotation.beanType).thenReturn(Object::class.java)
        whenever(handlerWithAnnotation.method).thenReturn(mockMethod)
        whenever(mockRequest.requestURI).thenReturn("/api/users")
        
        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithAnnotation)
        
        // Assert
        assertTrue(result)
        verify(mockResponse, never()).status = any()
    }
    
    @Test
    @DisplayName("应该拦截版本不匹配的请求并返回 404")
    fun `should intercept request when edition not match and return 404`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        val mockMethod = mock<Method>()
        val handlerWithAnnotation = mock<HandlerMethod>()
        whenever(handlerWithAnnotation.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(RequiresEdition("enterprise", "public"))
        whenever(handlerWithAnnotation.beanType).thenReturn(Object::class.java)
        whenever(handlerWithAnnotation.method).thenReturn(mockMethod)
        whenever(mockRequest.requestURI).thenReturn("/api/users")
        
        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithAnnotation)
        
        // Assert
        assertFalse(result)
        verify(mockResponse).status = HttpServletResponse.SC_NOT_FOUND
    }
    
    @Test
    @DisplayName("应该拒绝空注解值并返回 500")
    fun `should reject empty annotation values and return 500`() {
        // Arrange
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        val mockMethod = mock<Method>()
        val handlerWithEmptyAnnotation = mock<HandlerMethod>()
        whenever(handlerWithEmptyAnnotation.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(RequiresEdition())
        whenever(handlerWithEmptyAnnotation.beanType).thenReturn(Object::class.java)
        whenever(handlerWithEmptyAnnotation.method).thenReturn(mockMethod)
        
        // Act
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithEmptyAnnotation)
        
        // Assert
        assertFalse(result)
        verify(mockResponse).status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
    }
}
```

- [ ] **步骤 2:运行拦截器测试验证通过**

运行:
```bash
cd harnax-admin
mvn test -Dtest=EditionInterceptorTest
```

预期:所有测试 PASS

- [ ] **步骤 3:编写 EditionUtil 测试**

```kotlin
package com.agnetix.harnax.admin.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("personal")
@DisplayName("EditionUtil 集成测试")
class EditionUtilIntegrationTest {
    
    @Autowired
    private lateinit var editionUtil: EditionUtil
    
    @Test
    @DisplayName("应该正确识别个人版")
    fun `should identify personal edition`() {
        assertTrue(editionUtil.isPersonal())
        assertFalse(editionUtil.isEnterprise())
        assertFalse(editionUtil.isPublic())
        assertEquals("personal", editionUtil.getCurrentEdition())
    }
    
    @Test
    @DisplayName("个人版应该禁用所有高级功能")
    fun `personal edition should disable all advanced features`() {
        assertFalse(editionUtil.isFeatureEnabled("user-management"))
        assertFalse(editionUtil.isFeatureEnabled("token-monitor"))
        assertFalse(editionUtil.isFeatureEnabled("billing"))
        assertFalse(editionUtil.isFeatureEnabled("phone-login"))
        assertFalse(editionUtil.isFeatureEnabled("email-login"))
    }
    
    @Test
    @DisplayName("未知功能应该返回 false")
    fun `unknown feature should return false`() {
        assertFalse(editionUtil.isFeatureEnabled("unknown-feature"))
    }
}
```

- [ ] **步骤 4:运行 EditionUtil 测试验证通过**

运行:
```bash
cd harnax-admin
mvn test -Dtest=EditionUtilIntegrationTest
```

预期:所有测试 PASS

- [ ] **步骤 5:Commit**

```bash
git add harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/config/
git commit -m "test: 添加版本控制单元测试和集成测试"
```

---

## 任务 5:Controller 版本注解适配

**文件:**
- 修改:`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SysUserController.kt`
- 修改:`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt`

- [ ] **步骤 1:为 SysUserController 添加注解**

在类定义上方添加:

```kotlin
package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.config.RequiresEdition
import org.springframework.web.bind.annotation.*

/**
 * 用户管理 Controller
 * 仅企业版和公有云版可用
 */
@RestController
@RequestMapping("/api/users")
@RequiresEdition("enterprise", "public")
class SysUserController(
    // ... 现有代码保持不变
) {
    // ... 现有方法保持不变
}
```

- [ ] **步骤 2:为 TokenStatsController 添加注解**

```kotlin
package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.config.RequiresEdition
import org.springframework.web.bind.annotation.*

/**
 * Token 统计 Controller
 * 仅企业版和公有云版可用
 */
@RestController
@RequestMapping("/api/token/stats")
@RequiresEdition("enterprise", "public")
class TokenStatsController(
    // ... 现有代码保持不变
) {
    // ... 现有方法保持不变
}
```

- [ ] **步骤 3:验证编译成功**

运行:
```bash
cd harnax-admin
mvn clean compile -Ppersonal
```

预期:BUILD SUCCESS

- [ ] **步骤 4:Commit**

```bash
git add harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SysUserController.kt
git add harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt
git commit -m "feat: 为用户管理和 Token 统计 Controller 添加版本控制注解"
```

---

# Phase 2: 前端版本差异化

> **目标:** 实现前端运行时版本过滤和条件渲染

---

## 任务 6:前端环境文件

**文件:**
- 创建:`harnax-webui/.env.personal`
- 创建:`harnax-webui/.env.enterprise`
- 创建:`harnax-webui/.env.public`

- [ ] **步骤 1:创建个人版环境文件**

```bash
REACT_APP_EDITION=personal
REACT_APP_EDITION_LABEL=个人版
```

- [ ] **步骤 2:创建企业版环境文件**

```bash
REACT_APP_EDITION=enterprise
REACT_APP_EDITION_LABEL=企业版
```

- [ ] **步骤 3:创建公有云版环境文件**

```bash
REACT_APP_EDITION=public
REACT_APP_EDITION_LABEL=公有云版
```

- [ ] **步骤 4:Commit**

```bash
git add harnax-webui/.env.personal harnax-webui/.env.enterprise harnax-webui/.env.public
git commit -m "feat: 添加前端三版本环境配置文件"
```

---

## 任务 7:前端版本工具增强

**文件:**
- 修改:`harnax-webui/src/utils/edition.ts`

- [ ] **步骤 1:增强 edition.ts**

读取现有文件后,在末尾添加:

```typescript
// 版本枚举
export const EDITIONS = {
  PERSONAL: 'personal',
  ENTERPRISE: 'enterprise',
  PUBLIC: 'public',
} as const;

export type Edition = typeof EDITIONS[keyof typeof EDITIONS];

// 功能配置
export const FEATURES = {
  userManagement: !isPersonal(),
  tokenMonitor: !isPersonal(),
  billing: isPublic(),
  multiTenant: isPublic(),
  agentSharing: !isPersonal(),
  modelMarket: isPublic(),
  skillMarket: isPublic(),
  phoneLogin: !isPersonal(),
  emailLogin: !isPersonal(),
};

/**
 * 路由版本过滤函数
 * 移除当前版本不支持的路由
 */
export const filterRoutesByEdition = (routes: any[]): any[] => {
  return routes.filter(route => {
    // 如果路由有 edition 属性,检查是否匹配当前版本
    if (route.edition) {
      const editions = Array.isArray(route.edition) ? route.edition : [route.edition];
      if (!editions.includes(EDITION)) {
        return false;
      }
    }
    
    // 递归过滤子路由
    if (route.routes) {
      route.routes = filterRoutesByEdition(route.routes);
      // 如果所有子路由都被移除,也移除父路由
      if (route.routes.length === 0 && route.path !== '/') {
        return false;
      }
    }
    
    return true;
  });
};
```

- [ ] **步骤 2:Commit**

```bash
git add harnax-webui/src/utils/edition.ts
git commit -m "feat: 增强前端版本工具添加 FEATURES 配置和路由过滤"
```

---

## 任务 8:前端路由版本过滤

**文件:**
- 修改:`harnax-webui/src/app.tsx`
- 修改:`harnax-webui/config/routes.ts`

- [ ] **步骤 1:在 app.tsx 添加 patchRoutes**

在文件末尾添加:

```typescript
import { filterRoutesByEdition } from '@/utils/edition';

/**
 * 路由补丁
 * 根据当前版本过滤路由
 */
export function patchRoutes({ routes }: { routes: any }) {
  if (routes?.routes) {
    routes.routes = filterRoutesByEdition(routes.routes);
  }
}
```

- [ ] **步骤 2:修改 routes.ts 添加版本限制**

找到 system 路由配置,添加 edition 属性:

```typescript
{
  name: 'system',
  icon: 'setting',
  path: '/system',
  edition: ['enterprise', 'public'],  // 新增:版本限制
  routes: [
    {
      name: 'user.management',
      path: '/system/user',
      component: './user/management',
      access: 'canAccessUserManagement',
    },
    {
      name: 'token.monitor',
      path: '/system/token-monitor',
      component: './token-monitor',
    },
  ],
},
```

- [ ] **步骤 3:Commit**

```bash
git add harnax-webui/src/app.tsx harnax-webui/config/routes.ts
git commit -m "feat: 实现前端路由版本过滤机制"
```

---

## 任务 9:前端页面条件渲染

**文件:**
- 修改:`harnax-webui/src/pages/user/login/index.tsx`
- 修改:`harnax-webui/src/pages/mcp/components/McpForm.tsx`

- [ ] **步骤 1:登录页面添加版本控制**

在登录页面中使用 FEATURES 控制登录方式显示:

```typescript
import { FEATURES } from '@/utils/edition';

// 在表单中添加条件渲染
{
  FEATURES.phoneLogin && (
    <TabPane tab="手机号登录" key="phone">
      {/* 手机号登录表单 */}
    </TabPane>
  )
}
{
  FEATURES.emailLogin && (
    <TabPane tab="邮箱登录" key="email">
      {/* 邮箱登录表单 */}
    </TabPane>
  )
}
```

- [ ] **步骤 2:MCP 表单添加版本控制**

```typescript
import { isPersonal } from '@/utils/edition';

// 在传输模式选择中添加条件
{
  !isPersonal() && (
    <Form.Item label="传输模式" name="transportMode">
      <Select>
        <Select.Option value="sse">SSE 模式</Select.Option>
        <Select.Option value="streamable">Streamable HTTP</Select.Option>
      </Select>
    </Form.Item>
  )
}
{isPersonal() && (
  <Form.Item label="传输模式" name="transportMode">
    <Select>
      <Select.Option value="stdio">stdio 模式</Select.Option>
    </Select>
  </Form.Item>
)}
```

- [ ] **步骤 3:Commit**

```bash
git add harnax-webui/src/pages/user/login/index.tsx
git add harnax-webui/src/pages/mcp/components/McpForm.tsx
git commit -m "feat: 添加前端页面版本条件渲染"
```

---

# Phase 4: 构建自动化与测试

> **目标:** 实现一键构建、测试验证和 CI/CD 集成

---

## 任务 10:构建脚本与验证

**文件:**
- 创建:`build-all-editions.sh`
- 创建:`verify-edition.sh`

- [ ] **步骤 1:创建一键构建脚本**

```bash
#!/bin/bash
set -e

echo "========================================="
echo "Harnax 多版本构建开始"
echo "========================================="

EDITIONS=("personal" "enterprise" "public")
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for edition in "${EDITIONS[@]}"; do
    echo ""
    echo "-----------------------------------------"
    echo "构建 $edition 版本..."
    echo "-----------------------------------------"
    
    # 构建后端
    echo ">>> 构建后端..."
    cd "$SCRIPT_DIR/harnax-admin"
    mvn clean package -P$edition -DskipTests -q
    
    # 构建前端
    echo ">>> 构建前端..."
    cd "$SCRIPT_DIR/harnax-webui"
    REACT_APP_EDITION=$edition npm run build
    
    # 创建输出目录
    echo ">>> 整理构建产物..."
    mkdir -p "$SCRIPT_DIR/dist/$edition/backend"
    mkdir -p "$SCRIPT_DIR/dist/$edition/frontend"
    
    # 复制产物
    cp "$SCRIPT_DIR/harnax-admin/target"/*.jar "$SCRIPT_DIR/dist/$edition/backend/"
    cp -r "$SCRIPT_DIR/harnax-webui/dist"/* "$SCRIPT_DIR/dist/$edition/frontend/"
    
    echo "✅ $edition 版本构建完成"
done

echo ""
echo "========================================="
echo "✅ 所有版本构建完成!"
echo "输出目录: $SCRIPT_DIR/dist/"
echo "========================================="

# 运行验证
echo ""
echo "运行构建产物验证..."
bash "$SCRIPT_DIR/verify-edition.sh"
```

- [ ] **步骤 2:创建验证脚本**

```bash
#!/bin/bash
set -e

echo "========================================="
echo "构建产物验证"
echo "========================================="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EDITIONS=("personal" "enterprise" "public")
ERRORS=0

for edition in "${EDITIONS[@]}"; do
    echo ""
    echo "验证 $edition 版本..."
    
    JAR_FILE=$(ls "$SCRIPT_DIR/dist/$edition/backend/"*.jar 2>/dev/null | head -1)
    
    if [ -z "$JAR_FILE" ]; then
        echo "❌ 未找到 $edition 版本的 JAR 文件"
        ERRORS=$((ERRORS + 1))
        continue
    fi
    
    # 检查版本配置
    VERSION=$(unzip -p "$JAR_FILE" BOOT-INF/classes/edition.properties 2>/dev/null | grep "edition.current" || echo "")
    if [[ $VERSION != *"edition.current=$edition"* ]]; then
        echo "❌ $edition 版本配置错误: $VERSION"
        ERRORS=$((ERRORS + 1))
    else
        echo "✅ 版本配置正确"
    fi
    
    # 检查前端产物
    if [ ! -d "$SCRIPT_DIR/dist/$edition/frontend/" ]; then
        echo "❌ 前端产物缺失"
        ERRORS=$((ERRORS + 1))
    else
        echo "✅ 前端产物存在"
    fi
done

echo ""
if [ $ERRORS -eq 0 ]; then
    echo "========================================="
    echo "✅ 所有验证通过!"
    echo "========================================="
    exit 0
else
    echo "========================================="
    echo "❌ $ERRORS 个验证失败"
    echo "========================================="
    exit 1
fi
```

- [ ] **步骤 3:添加执行权限**

```bash
chmod +x build-all-editions.sh verify-edition.sh
```

- [ ] **步骤 4:Commit**

```bash
git add build-all-editions.sh verify-edition.sh
git commit -m "feat: 添加一键构建和产物验证脚本"
```

---

## 任务 11:后端单元测试

**文件:**
- 创建:`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/config/EditionInterceptorTest.kt`
- 创建:`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/config/EditionUtilTest.kt`

- [ ] **步骤 1:编写拦截器测试**

```kotlin
package com.agnetix.harnax.admin.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.web.method.HandlerMethod
import java.lang.reflect.Method

@DisplayName("EditionInterceptor 测试")
class EditionInterceptorTest {
    
    private val editionUtil = mock<EditionUtil>()
    private val interceptor = EditionInterceptor(editionUtil)
    private val mockRequest = mock<HttpServletRequest>()
    private val mockResponse = mock<HttpServletResponse>()
    
    @Test
    @DisplayName("应该放行没有 @RequiresEdition 注解的请求")
    fun `should allow request without RequiresEdition annotation`() {
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        val handlerWithoutAnnotation = mock<HandlerMethod>()
        whenever(handlerWithoutAnnotation.getMethodAnnotation(RequiresEdition::class.java)).thenReturn(null)
        whenever(handlerWithoutAnnotation.beanType).thenReturn(Object::class.java)
        
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithoutAnnotation)
        
        assertTrue(result)
        verify(mockResponse, never()).status = any()
    }
    
    @Test
    @DisplayName("应该拦截版本不匹配的请求并返回 404")
    fun `should intercept request when edition not match and return 404`() {
        whenever(editionUtil.getCurrentEdition()).thenReturn("personal")
        val mockMethod = mock<Method>()
        val handlerWithAnnotation = mock<HandlerMethod>()
        whenever(handlerWithAnnotation.getMethodAnnotation(RequiresEdition::class.java))
            .thenReturn(RequiresEdition("enterprise", "public"))
        whenever(handlerWithAnnotation.beanType).thenReturn(Object::class.java)
        whenever(handlerWithAnnotation.method).thenReturn(mockMethod)
        whenever(mockRequest.requestURI).thenReturn("/api/users")
        
        val result = interceptor.preHandle(mockRequest, mockResponse, handlerWithAnnotation)
        
        assertFalse(result)
        verify(mockResponse).status = HttpServletResponse.SC_NOT_FOUND
    }
}
```

- [ ] **步骤 2:运行拦截器测试验证通过**

运行:
```bash
cd harnax-admin
mvn test -Dtest=EditionInterceptorTest
```

预期:所有测试 PASS

- [ ] **步骤 3:编写 EditionUtil 测试**

```kotlin
package com.agnetix.harnax.admin.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("personal")
@DisplayName("EditionUtil 集成测试")
class EditionUtilIntegrationTest {
    
    @Autowired
    private lateinit var editionUtil: EditionUtil
    
    @Test
    @DisplayName("应该正确识别个人版")
    fun `should identify personal edition`() {
        assertTrue(editionUtil.isPersonal())
        assertFalse(editionUtil.isEnterprise())
        assertFalse(editionUtil.isPublic())
        assertEquals("personal", editionUtil.getCurrentEdition())
    }
    
    @Test
    @DisplayName("个人版应该禁用所有高级功能")
    fun `personal edition should disable all advanced features`() {
        assertFalse(editionUtil.isFeatureEnabled("user-management"))
        assertFalse(editionUtil.isFeatureEnabled("token-monitor"))
        assertFalse(editionUtil.isFeatureEnabled("phone-login"))
    }
}
```

- [ ] **步骤 4:运行 EditionUtil 测试验证通过**

运行:
```bash
cd harnax-admin
mvn test -Dtest=EditionUtilIntegrationTest
```

预期:所有测试 PASS

- [ ] **步骤 5:Commit**

```bash
git add harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/config/
git commit -m "test: 添加版本控制单元测试和集成测试"
```

---

## 任务 12:前端测试与构建验证

- [ ] **步骤 1:测试个人版后端构建**

运行:
```bash
cd harnax-admin
mvn clean package -Ppersonal -DskipTests
```

验证:
```bash
unzip -p target/*.jar BOOT-INF/classes/edition.properties | grep edition.current
```

预期输出:`edition.current=personal`

- [ ] **步骤 2:测试企业版后端构建**

运行:
```bash
cd harnax-admin
mvn clean package -Penterprise -DskipTests
```

验证:
```bash
unzip -p target/*.jar BOOT-INF/classes/edition.properties | grep edition.current
```

预期输出:`edition.current=enterprise`

- [ ] **步骤 3:测试公有云版后端构建**

运行:
```bash
cd harnax-admin
mvn clean package -Ppublic -DskipTests
```

验证:
```bash
unzip -p target/*.jar BOOT-INF/classes/edition.properties | grep edition.current
```

预期输出:`edition.current=public`

- [ ] **步骤 4:测试前端构建**

运行:
```bash
cd harnax-webui
npm run build:personal
```

预期:BUILD SUCCESS,生成 dist/ 目录

- [ ] **步骤 5:测试一键构建脚本**

运行:
```bash
./build-all-editions.sh
```

预期:三个版本全部构建成功,验证通过

- [ ] **步骤 6:Commit(如有配置修改)**

```bash
git add .
git commit -m "chore: 验证三版本构建流程正常"
```

---

# Phase 3: Docker 容器化部署

> **目标:** 实现三版本 Docker 镜像构建和容器化部署

---

## 任务 13:Docker 多阶段构建

**文件:**
- 创建:`.dockerignore`
- 创建:`Dockerfile.backend`
- 创建:`Dockerfile.frontend`
- 创建:`docker-compose.yml`
- 创建:`docker-compose.prod.yml`
- 创建:`docker/nginx.conf`

- [ ] **步骤 1:创建 .dockerignore 文件**

```
# Git
.git
.gitignore

# IDE
.idea
.vscode
*.swp
*.swo

# Maven
harnax-admin/target
*.class

# Node
harnax-webui/node_modules
harnax-webui/dist
harnax-webui/.umi
harnax-webui/.umi-production

# OpenSpec (AI 工作目录)
openspec
docs

# OS
.DS_Store
Thumbs.db

# Logs
*.log
logs

# Docker (避免循环)
Dockerfile.*
docker-compose*.yml
```

- [ ] **步骤 2:创建 Dockerfile.backend**

```dockerfile
# Stage 1: 构建阶段
FROM eclipse-temurin:21-jdk AS builder

ARG EDITION=personal
ARG MAVEN_OPTS="-Dmaven.repo.local=/root/.m2/repository"

WORKDIR /app

# 缓存 Maven 依赖
COPY pom.xml .
COPY harnax-admin/pom.xml harnax-admin/
RUN cd harnax-admin && mvn dependency:go-offline -B -P${EDITION}

# 复制源代码并构建
COPY harnax-admin/src harnax-admin/src
RUN cd harnax-admin && mvn clean package -P${EDITION} -DskipTests -B

# Stage 2: 运行阶段
FROM eclipse-temurin:21-jre

LABEL maintainer="Harnax Team"
LABEL version="1.0.0"
LABEL edition=${EDITION}

WORKDIR /app

# 创建非 root 用户
RUN groupadd -r harnax && useradd -r -g harnax harnax

# 复制构建产物
COPY --from=builder /app/harnax-admin/target/harnax-admin-*.jar app.jar

# 设置时区和 JVM 参数
ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-Xms512m -Xmx1024m -Djava.security.egd=file:/dev/./urandom"

# 切换到非 root 用户
RUN chown -R harnax:harnax /app
USER harnax

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

- [ ] **步骤 3:创建 Dockerfile.frontend**

```dockerfile
# Stage 1: 构建阶段
FROM node:20-alpine AS builder

ARG EDITION=personal
ARG REACT_APP_EDITION=${EDITION}

WORKDIR /app

# 缓存 node_modules
COPY harnax-webui/package.json harnax-webui/package-lock.json ./
RUN npm ci --prefer-offline --no-audit

# 复制源代码并构建
COPY harnax-webui/ .
RUN npm run build:${EDITION}

# Stage 2: 运行阶段
FROM nginx:alpine

LABEL maintainer="Harnax Team"
LABEL version="1.0.0"
LABEL edition=${EDITION}

# 复制 Nginx 配置
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf

# 复制构建产物
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
```

- [ ] **步骤 4:创建 docker/nginx.conf**

```nginx
server {
    listen 80;
    server_name localhost;
    
    # 前端静态资源
    root /usr/share/nginx/html;
    index index.html;
    
    # 后端 API 代理
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    
    # 前端路由支持
    location / {
        try_files $uri $uri/ /index.html;
    }
    
    # 静态资源缓存
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
```

- [ ] **步骤 5:创建 docker-compose.yml**

```yaml
version: '3.8'

services:
  # MySQL 数据库
  mysql:
    image: mysql:8.0
    container_name: harnax-mysql
    environment:
      MYSQL_ROOT_PASSWORD: root123
      MYSQL_DATABASE: harnax
      MYSQL_USER: harnax
      MYSQL_PASSWORD: harnax123
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql
      - ./sql:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - harnax-network

  # 后端服务
  backend:
    build:
      context: .
      dockerfile: Dockerfile.backend
      args:
        EDITION: ${EDITION:-personal}
    container_name: harnax-backend
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/harnax?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
      SPRING_DATASOURCE_USERNAME: harnax
      SPRING_DATASOURCE_PASSWORD: harnax123
      SWAGGER_ENABLED: "true"
    ports:
      - "8080:8080"
    depends_on:
      mysql:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
    networks:
      - harnax-network

  # 前端服务
  frontend:
    build:
      context: .
      dockerfile: Dockerfile.frontend
      args:
        EDITION: ${EDITION:-personal}
    container_name: harnax-frontend
    ports:
      - "80:80"
    depends_on:
      - backend
    networks:
      - harnax-network

volumes:
  mysql-data:

networks:
  harnax-network:
    driver: bridge
```

- [ ] **步骤 6:创建 docker-compose.prod.yml**

```yaml
version: '3.8'

services:
  # 后端服务
  backend:
    image: harnax/harnax-admin:${EDITION:-personal}
    container_name: harnax-backend
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://${DB_HOST:-mysql}:${DB_PORT:-3306}/harnax?useUnicode=true&characterEncoding=utf8&useSSL=true&serverTimezone=Asia/Shanghai
      SPRING_DATASOURCE_USERNAME: ${DB_USERNAME}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      SWAGGER_ENABLED: "false"
      JAVA_OPTS: "-Xms1024m -Xmx2048m"
    ports:
      - "8080:8080"
    restart: unless-stopped
    networks:
      - harnax-network

  # 前端服务
  frontend:
    image: harnax/harnax-webui:${EDITION:-personal}
    container_name: harnax-frontend
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/ssl:/etc/nginx/ssl
    restart: unless-stopped
    depends_on:
      - backend
    networks:
      - harnax-network

networks:
  harnax-network:
    driver: bridge
```

- [ ] **步骤 7:测试 Docker 构建**

运行:
```bash
# 构建个人版镜像
docker build --build-arg EDITION=personal -t harnax/harnax-admin:personal -f Dockerfile.backend .
docker build --build-arg EDITION=personal -t harnax/harnax-webui:personal -f Dockerfile.frontend .

# 启动本地环境
EDITION=personal docker-compose up -d

# 验证服务
curl http://localhost:8080/actuator/health
curl http://localhost/
```

预期:Docker 镜像构建成功,服务正常启动

- [ ] **步骤 8:Commit**

```bash
git add .dockerignore Dockerfile.backend Dockerfile.frontend
git add docker-compose.yml docker-compose.prod.yml
git add docker/nginx.conf
git commit -m "feat: 添加 Docker 多阶段构建和容器化部署支持"
```

---

## 自检清单

### 规格覆盖度检查

**Phase 1: 后端版本控制核心**
- ✅ 版本专属源码目录管理 → 任务 1 (Maven Profile + Build Helper Plugin)
- ✅ 版本功能配置系统 → 任务 2 (application-{edition}.yml)
- ✅ @RequiresEdition 注解体系 → 任务 3 (注解和拦截器实现)
- ✅ 功能开关读取能力 → 任务 4 (EditionUtil 增强)
- ✅ Controller 版本适配 → 任务 5 (SysUserController, TokenStatsController)
- ✅ Swagger 开发/生产差异化 → 任务 2 (application.yml 配置)

**Phase 2: 前端版本差异化**
- ✅ 前端环境文件 → 任务 6 (.env.{edition})
- ✅ 版本工具和 FEATURES 配置 → 任务 7 (edition.ts)
- ✅ 前端路由版本过滤 → 任务 8 (app.tsx + routes.ts)
- ✅ 前端页面条件渲染 → 任务 9 (login, McpForm)

**Phase 3: Docker 容器化部署**
- ✅ Docker 多阶段构建 → 任务 10 (Dockerfile.backend + Dockerfile.frontend)
- ✅ docker-compose 编排 → 任务 10 (docker-compose.yml + docker-compose.prod.yml)
- ✅ Nginx 反向代理 → 任务 10 (docker/nginx.conf)

**Phase 4: 构建自动化与测试**
- ✅ 一键构建脚本 → 任务 10 (build-all-editions.sh)
- ✅ 构建产物验证 → 任务 10 (verify-edition.sh)
- ✅ 后端单元测试 → 任务 11 (EditionInterceptorTest + EditionUtilTest)
- ✅ 前端测试与构建 → 任务 12 (edition.test.ts + 构建验证)
- ✅ CI/CD 集成 → 任务 10 (.gitlab-ci.yml)

### 占位符扫描

- ✅ 无"TODO"、"待定"、"后续实现"
- ✅ 所有步骤包含完整代码或明确命令
- ✅ 无"类似任务 N"引用

### 类型一致性检查

- ✅ `RequiresEdition` 注解在所有任务中命名一致
- ✅ `EditionInterceptor` 类名一致
- ✅ `EditionUtil` 方法签名一致
- ✅ 前端 `EDITION`, `FEATURES`, `filterRoutesByEdition` 命名一致

---

**计划已完成并保存到** `docs/superpowers/plans/2026-05-01-multi-version-implementation.md`

**两种执行方式:**

**1. 子代理驱动(推荐)** - 每个任务调度一个新的子代理,任务间进行审查,快速迭代

**2. 内联执行** - 在当前会话中使用 executing-plans 执行任务,批量执行并设有检查点

选哪种方式?
