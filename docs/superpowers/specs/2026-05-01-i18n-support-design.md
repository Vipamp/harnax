# 国际化支持设计文档

**日期**: 2026-05-01  
**主题**: 前后端国际化完整实施  
**状态**: 已批准

---

## 概述

为 Harnax 项目建立完整的前后端国际化体系，实现错误消息、系统提示、业务文案根据用户语言偏好动态切换。

---

## 架构设计

### 整体架构

```
前端 (React + UmiJS)          后端 (Spring Boot + Kotlin)
┌─────────────────────┐       ┌──────────────────────────────┐
│ localStorage        │       │ I18nConfig                   │
│ (umi_locale)        │       │ ├─ MessageSource Bean        │
└────────┬────────────┘       │ └─ AcceptLanguageLocaleRes.  │
         │                    └──────────────────────────────┘
         │ Accept-Language: zh-CN                    ▲
         ▼                                           │
┌─────────────────────┐       ┌──────────────────────────────┐
│ Axios Interceptor   │──────>│ Controller / Service         │
│ 添加请求头           │       │                              │
└─────────────────────┘       │ MessageUtil.getMessage()     │
                              │ ↓                            │
┌─────────────────────┐       │ i18n/messages.properties     │
│ react-intl          │       │ i18n/messages_zh_CN.prop.    │
│ 前端UI国际化         │       │ i18n/messages_error*.prop.   │
└─────────────────────┘       └──────────────────────────────┘
```

### 数据流

1. 用户在前端切换语言 → localStorage 存储 `umi_locale`
2. 前端发起 API 请求 → Axios 拦截器读取 `umi_locale`，添加到 `Accept-Language` 请求头
3. 后端接收请求 → AcceptLanguageLocaleResolver 解析请求头，设置当前 Locale
4. Service 层抛出异常 → 调用 MessageUtil.getMessage() 获取国际化消息
5. 全局异常处理器 → 捕获异常，返回包含国际化消息的响应
6. 前端显示错误 → 从响应中提取 message 字段显示

---

## 分阶段实施计划

### PR 1: 后端基础设施 + 前端请求头同步

**目标**: 建立国际化基础设施，打通前后端语言同步链路

**后端组件**:

#### 1. I18nConfig 配置类
```kotlin
@Configuration
class I18nConfig {
    @Bean
    fun messageSource(): MessageSource {
        // 配置 i18n/messages*.properties 路径
        // 设置默认编码 UTF-8
        // 设置缓存策略
    }
    
    @Bean
    fun localeResolver(): LocaleResolver {
        // 使用 AcceptLanguageLocaleResolver
        // 设置默认 Locale 为 Locale.ENGLISH
    }
}
```

#### 2. MessageUtil 工具类
```kotlin
@Component
class MessageUtil(
    private val messageSource: MessageSource
) {
    fun getMessage(code: String, vararg args: Any): String {
        val locale = LocaleContextHolder.getLocale()
        return messageSource.getMessage(code, args, locale)
    }
}
```

#### 3. 消息资源文件骨架
```
src/main/resources/i18n/
├── messages.properties              # 默认（英文）
├── messages_zh_CN.properties        # 中文
├── messages_error.properties        # 错误消息（英文）
└── messages_error_zh_CN.properties  # 错误消息（中文）
```

**前端组件**:

#### 4. Axios 请求拦截器增强
在 `requestErrorConfig.ts` 中添加：
```typescript
// 在现有 token 拦截器之后
const locale = localStorage.getItem('umi_locale') || 'zh-CN';
config.headers['Accept-Language'] = locale;
```

**测试**:
- 单元测试：验证 MessageSource 正确加载消息
- 单元测试：验证 LocaleResolver 正确解析请求头
- 集成测试：验证前端请求头正确传递

---

### PR 2: 核心错误消息迁移

**目标**: 迁移核心业务模块的硬编码错误消息

**迁移清单** (约 25 处):

#### 1. AuthServiceImpl (登录相关)
- `用户名不存在` → `error.user.notfound`
- `用户名或密码错误` → `error.user.invalid_credentials`
- `请输入验证码` → `error.captcha.required`
- `验证码 key 不能为空` → `error.captcha.key_required`
- `验证码错误，请重新输入` → `error.captcha.invalid`
- `用户已被禁用，请联系管理员` → `error.user.disabled`
- `您的账号暂无可用租户，请联系管理员` → `error.user.no_tenant`

#### 2. TenantInterceptor (租户相关)
- `无效的租户ID` → `error.tenant.invalid_id`
- `用户未登录` → `error.auth.not_logged_in`
- `无权访问该租户` → `error.tenant.access_denied`
- `您在该租户下已被禁用` → `error.tenant.user_disabled`

#### 3. SysUserServiceImpl (用户管理)
- `用户名已存在` → `error.user.username_exists`
- `手机号已存在` → `error.user.phone_exists`
- `邮箱已存在` → `error.user.email_exists`
- `用户不存在` → `error.user.notfound`
- 等...

**实施步骤**:
1. 在 messages_error*.properties 中添加上述消息代码
2. 在 BizException 中注入 MessageUtil
3. 替换所有 `throw BizException("硬编码消息")` 为 `throw BizException(messageUtil.getMessage("error.xxx"))`
4. 更新全局异常处理器支持国际化消息
5. 编写集成测试验证错误消息国际化

---

### PR 3: 前端文案补充 + 全面测试

**目标**: 完善前端国际化文案，进行端到端测试

**前端工作**:

#### 1. 对比补充国际化文案
- 对比 `zh-CN.ts` 和 `en-US.ts`
- 识别缺失的翻译项
- 补充完整所有模块的国际化文案

#### 2. 端到端测试
- 测试中文语言下的完整用户流程
  - 登录 → 用户管理 → 错误提示
  - 租户切换 → 权限验证
- 测试英文语言下的完整用户流程
  - 验证所有界面文案正确翻译
  - 验证错误消息正确显示英文

#### 3. 语言切换功能验证
- 验证切换语言后刷新页面生效
- 验证请求头正确更新
- 验证 API 错误消息随语言切换

**文档工作**:
- 更新开发者文档，说明国际化使用方式
- 创建国际化消息代码参考文档
- 代码审查确保遵循命名规范

---

## 技术决策

### 1. 后端语言解析策略
**决策**: 使用 `AcceptLanguageLocaleResolver`

**理由**: 
- 标准 HTTP 协议支持，前端可通过请求头传递语言
- 无状态设计，适合 RESTful API
- 前端已在 localStorage 存储语言偏好，可在请求时读取

### 2. 消息资源文件组织
**决策**: 按功能模块组织，使用 properties 格式

**理由**:
- properties 格式是 Spring Boot 标准，工具支持好
- 按模块分离便于维护（messages_error, messages 等）
- 支持消息参数化（如：`error.user.notfound=User {0} not found`）

### 3. 错误消息代码命名规范
**格式**: `error.{模块}.{实体}.{操作}`

**示例**:
- `error.user.notfound` - 用户模块，用户实体，未找到操作
- `error.validation.required` - 验证模块，通用，必填字段
- `success.user.created` - 成功消息，用户模块，创建操作

### 4. 前端语言同步机制
**决策**: 在 Axios 请求拦截器中添加 Accept-Language 请求头

**实现**: 在 `requestErrorConfig.ts` 的 requestInterceptors 中读取 localStorage

---

## 风险与缓解

### Risk 1: 消息 key 管理混乱
**缓解**: 建立消息 key 命名规范，在 Code Review 时检查

### Risk 2: 前端遗漏国际化文案
**缓解**: 建立国际化文案检查清单，对比 zh-CN 和 en-US 文件

### Risk 3: 性能影响
**缓解**: MessageSource 默认会缓存消息，性能影响可忽略

### Risk 4: 迁移范围过大
**缓解**: 分 3 个 PR 逐步实施，每个 PR 独立测试和审查

---

## 回滚策略

- 保留原有硬编码消息作为 fallback
- 如果 MessageSource 未找到消息，返回默认消息或 key 本身
- 可通过配置开关快速回退

---

## 成功标准

✅ 后端正确解析 Accept-Language 请求头  
✅ 错误消息根据语言动态切换（中英文）  
✅ 前端语言切换后，所有 API 错误消息正确翻译  
✅ 所有单元测试和集成测试通过  
✅ 国际化文案检查清单完成  
✅ 代码审查通过，遵循命名规范
