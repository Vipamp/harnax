# 内置工具开发手册

本文档介绍如何为 Harnax 智能体平台开发新的内置工具（Built-in Tool）。
内置工具是 ToolBox 的实现类，Agent 可以在运行时调用这些工具来完成任务。

## 快速开始

创建一个新的内置工具只需 **3 步**：

1. 在 `harnax-tools-buildin` 模块中新建 ToolBox 子类
2. 在每个工具方法上加 `@Tool` + `@ToolMeta` 注解，方法参数加 `@ToolParam` 注解
3. 重启 admin 服务 —— 工具自动注册到数据库（每个 `@Tool` 方法一条记录），无需手动操作

## 项目结构

```
harnax-agent/
├── harnax-tools-sdk/          # SDK：基类、注解、注册中心
│   └── src/main/kotlin/.../tools/sdk/
│       ├── ToolBox.kt              # 抽象基类（必须继承）
│       ├── ToolMeta.kt             # 方法级元数据注解
│       ├── ToolEnvParamDef.kt      # 环境参数定义注解
│       ├── ToolMetaDescriptor.kt   # 运行时元数据模型
│       ├── NeedConfirmed.kt        # 方法级注解（定义在 ToolBox.kt 中）
│       └── registry/
│           └── ToolRegistry.kt     # Spring 自动发现注册中心
│
└── harnax-tools-external/
    └── harnax-tools-buildin/  # 内置工具实现
        └── src/main/kotlin/.../tools/builtin/
            ├── TimeToolBox.kt      # 示例：时间工具
            └── YourNewToolBox.kt   # 你的新工具放在这里
```

## 核心概念

### 工具粒度：每个 @Tool 方法 = 一个工具

一个 ToolBox 类可以包含多个 `@Tool` 方法，**每个方法在系统中算一个独立的工具**：
- 每个 `@Tool` 方法对应一条 `agent_tool` 数据库记录
- 不同方法可以独立启用/禁用
- 不同方法可以独立设置 `needConfirm`
- 不同方法可以有独立的 `envParamDefs`（环境参数）

### 术语

- **环境参数（env param）**：工具运行前需要配置的外部参数（如 API Key、服务器地址），在 Admin UI 中配置
- **动态参数**：工具运行时由 LLM 提供的参数（如搜索关键词），由 `@Tool` 方法签名定义，**必须加 `@ToolParam` 注解**才会出现在工具 JSON Schema 中

## 分步指南：创建一个新工具

### 第 1 步：创建 ToolBox 类

在 `harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/builtin/` 下新建文件：

```kotlin
package com.agnetix.harnax.tools.builtin

import com.agnetix.harnax.tools.sdk.ToolBox
import com.agnetix.harnax.tools.sdk.ToolEnvContext
import com.agnetix.harnax.tools.sdk.ToolEnvParamDef
import com.agnetix.harnax.tools.sdk.ToolMeta
import io.agentscope.core.tool.Tool
import io.agentscope.core.tool.ToolParam
import org.springframework.stereotype.Component

@Component("weather-tool-box")
class WeatherToolBox : ToolBox() {

    @Tool(name = "getCurrentWeather", description = "获取城市当前天气", readOnly = true)
    @ToolMeta(
        displayName = "Get Current Weather",
        displayNameZh = "获取当前天气",
        envParamDefs = [
            ToolEnvParamDef(key = "WEATHER_API_KEY", description = "天气 API 密钥", required = true, secret = true),
        ],
    )
    fun getCurrentWeather(
        @ToolParam(name = "city", description = "城市名称")
        city: String,
        envContext: ToolEnvContext,
    ): String = execute("city" to city) {
        val apiKey = envContext.require("WEATHER_API_KEY")
        "$city 当前天气：晴，25°C"
    }

    @Tool(name = "getForecast", description = "获取未来 N 天天气预报", readOnly = true)
    @ToolMeta(
        displayName = "Get Forecast",
        displayNameZh = "获取天气预报",
        needConfirm = true,
        envParamDefs = [
            ToolEnvParamDef(key = "WEATHER_API_KEY", description = "天气 API 密钥", required = true, secret = true),
        ],
    )
    fun getForecast(
        @ToolParam(name = "city", description = "城市名称")
        city: String,
        @ToolParam(name = "days", description = "预报天数")
        days: Int,
        envContext: ToolEnvContext,
    ): String = execute("city" to city, "days" to days) {
        val apiKey = envContext.require("WEATHER_API_KEY")
        "$city 未来 $days 天预报：持续晴好"
    }

    override fun name(): String = "weather-tool-box"

    companion object {
        const val NAME = "weather-tool-box"
    }
}
```

### 第 2 步：添加依赖（如需要）

如果工具需要外部库，在 `harnax-tools-buildin/pom.xml` 中添加：

```xml
<!-- 示例：Jsoup 用于网页抓取 -->
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.17.2</version>
</dependency>
```

### 第 3 步：配置环境参数

如果工具使用了 `envParamDefs`，需要在 Admin UI 中配置实际的值：
1. 进入 **工具管理** 页面
2. 找到自动注册的工具（每个 @Tool 方法一条记录）
3. 点击 **编辑** → 填写环境参数的值
4. 保存

就这么简单 —— 不需要手动插入数据库记录。

## 注解参考

### `@ToolMeta`（方法级注解）

应用于每个 `@Tool` 方法，声明该工具的元数据，启动时同步到数据库。

| 属性             | 类型              | 默认值   | 说明                                    |
|------------------|-------------------|----------|-----------------------------------------|
| `displayName`    | String            | `""`     | 英文显示名称                             |
| `displayNameZh`  | String            | `""`     | 中文显示名称                             |
| `envParamDefs`   | ToolEnvParamDef[] | `[]`     | 环境参数定义（含 description、required 等）|
| `timeoutSeconds` | Int               | `0`      | 执行超时时间（秒），0=使用系统默认        |
| `isPublic`       | Boolean           | `true`   | 是否对所有用户公开                        |
| `needConfirm`    | Boolean           | `false`  | 是否需要用户确认                          |

### `@Tool`（方法级注解，来自 agentscope）

应用于每个工具方法，描述动作让 LLM 理解何时调用。

| 属性          | 类型    | 默认值   | 说明                              |
|---------------|---------|----------|-----------------------------------|
| `name`        | String  | `""`     | 动作名（默认使用方法名）           |
| `description` | String  | `""`     | 动作描述，发送给 LLM               |
| `readOnly`    | Boolean | `false`  | 该动作是否为只读                   |

### `@ToolParam`（参数级注解，来自 agentscope）

应用于工具方法中**需要 LLM 传入的参数**，生成 JSON Schema 供 LLM 识别。

| 属性          | 类型    | 默认值   | 说明                              |
|---------------|---------|----------|-----------------------------------|
| `name`        | String  | **必填** | 参数名（建议 snake_case）          |
| `description` | String  | `""`     | 参数描述，帮助 LLM 理解该传什么值  |
| `required`    | Boolean | `true`   | 是否为必填参数                     |

> **重要**：没有 `@ToolParam` 的参数（如 `ToolEnvContext`、`RuntimeContext`）会被框架视为自动注入的上下文参数，不会出现在工具 JSON Schema 中，LLM 无法传值。

### `@NeedConfirmed`（方法级注解，来自 tools-sdk）

旧版注解，功能已合并到 `@ToolMeta.needConfirm`。仍可使用，与 `@ToolMeta.needConfirm` 取或。

### `@Component`（Spring 注解）

Bean 名称**必须**显式指定，遵循 `{name}-tool-box` 命名模式：

```kotlin
@Component("email-tool-box")    // 正确
@Component                      // 错误 - 没有 bean 名称
```

## execute() 方法

所有工具逻辑**必须**用 `execute { ... }` 包装，以启用自动调用日志：

```kotlin
// 带参数（推荐，便于追踪）
fun sendEmail(
    @ToolParam(name = "to", description = "收件人邮箱")
    to: String,
    @ToolParam(name = "subject", description = "邮件主题")
    subject: String,
): String = execute("to" to to, "subject" to subject) {
    // 实现
}

// 不带参数
fun getCurrentTime(): String = execute {
    // 实现
    System.currentTimeMillis().toString()
}
```

## 命名规范

| 元素          | 规范                            | 示例                          |
|---------------|--------------------------------|-------------------------------|
| Bean 名称     | `{name}-tool-box`              | `email-tool-box`              |
| 类名          | `{Name}ToolBox`                | `EmailToolBox`                |
| `name()` 返回值 | 与 bean 名称保持一致           | `"email-tool-box"`            |
| 文件名        | 与类名相同                      | `EmailToolBox.kt`             |
| 包名          | `com.agnetix.harnax.tools.builtin` | （所有工具统一使用）      |

## 最佳实践

### 1. 写清晰的描述

`@Tool.description` 是 LLM 在决定是否使用工具时看到的内容：

```kotlin
// 好的写法 - 具体且可执行
@Tool(description = "向指定收件人发送邮件，支持主题和 HTML 正文")

// 差的写法 - 过于模糊
@Tool(description = "发邮件")
```

### 2. 对危险操作使用 needConfirm

```kotlin
// 只读操作 - 无需确认
@Tool(description = "按关键词搜索商品", readOnly = true)
@ToolMeta(displayName = "Search Products", displayNameZh = "搜索商品")
fun searchProducts(
    @ToolParam(name = "query", description = "搜索关键词")
    query: String,
): String = execute { ... }

// 写操作 - 需要确认
@Tool(description = "为商品下单")
@ToolMeta(displayName = "Place Order", displayNameZh = "下单", needConfirm = true)
fun placeOrder(
    @ToolParam(name = "product_id", description = "商品 ID")
    productId: String,
    @ToolParam(name = "quantity", description = "购买数量")
    quantity: Int,
): String = execute { ... }
```

### 3. 声明环境参数

如果工具需要 API 密钥或配置，在每个方法的 `envParamDefs` 中声明：

```kotlin
@Tool(name = "getWeather", description = "查询天气")
@ToolMeta(
    displayName = "Get Weather",
    displayNameZh = "查询天气",
    envParamDefs = [
        ToolEnvParamDef(key = "WEATHER_API_KEY", description = "天气 API 密钥", required = true, secret = true),
        ToolEnvParamDef(key = "WEATHER_BASE_URL", description = "天气 API 地址", defaultValue = "https://api.weather.com"),
    ],
)
fun getWeather(
    @ToolParam(name = "city", description = "城市名称")
    city: String,
    envContext: ToolEnvContext,
): String = execute { ... }
```

### 4. 保持工具职责单一

一个 ToolBox = 一个领域，不要把不相关的功能混在一起：

```kotlin
// 好的做法 - 职责单一
class WeatherToolBox : ToolBox() { ... }
class EmailToolBox : ToolBox() { ... }

// 差的做法 - 混合关注点
class UtilityToolBox : ToolBox() {
    fun sendEmail() { ... }
    fun getWeather() { ... }
    fun calculateHash() { ... }
}
```

## 常见陷阱

### 陷阱 1：忘记指定 @Component 的 Bean 名称

```kotlin
// 错误 - ToolRegistry 无法识别 bean
@Component
class MyToolBox : ToolBox() { ... }

// 正确
@Component("my-tool-box")
class MyToolBox : ToolBox() { ... }
```

### 陷阱 2：忘记用 execute() 包装

```kotlin
// 错误 - 没有调用日志，没有错误追踪
@Tool(description = "做某件事")
fun doSomething(): String {
    return "result"
}

// 正确
@Tool(description = "做某件事")
fun doSomething(): String = execute {
    "result"
}
```

### 陷阱 3：name() 返回值与 Bean 名称不一致

```kotlin
@Component("email-tool-box")
class EmailToolBox : ToolBox() {
    // 错误 - 与 bean 名称不匹配
    override fun name(): String = "email-tool"

    // 正确 - 与 bean 名称一致
    override fun name(): String = "email-tool-box"
}
```

### 陷阱 4：忘记添加 @Tool

没有 `@Tool` 注解的方法不会被 ToolRegistry 发现，也不会同步到数据库。

### 陷阱 5：工具方法参数忘记加 @ToolParam

```kotlin
// 错误 - city 没有 @ToolParam，不会出现在 JSON Schema 中，LLM 无法传值
@Tool(description = "查询天气")
fun getWeather(city: String): String = execute { ... }

// 正确 - city 有 @ToolParam，LLM 能看到并传入该参数
@Tool(description = "查询天气")
fun getWeather(
    @ToolParam(name = "city", description = "城市名称")
    city: String,
): String = execute { ... }
```

> 框架注入参数（如 `ToolEnvContext`）**不加** `@ToolParam`，它们由框架自动注入。

## 完整示例：计算器工具

```kotlin
package com.agnetix.harnax.tools.builtin

import com.agnetix.harnax.tools.sdk.ToolBox
import com.agnetix.harnax.tools.sdk.ToolMeta
import io.agentscope.core.tool.Tool
import io.agentscope.core.tool.ToolParam
import org.springframework.stereotype.Component

@Component("calculator-tool-box")
class CalculatorToolBox : ToolBox() {

    @Tool(name = "calculate", description = "计算数学表达式，例如 '2 + 3 * 4'", readOnly = true)
    @ToolMeta(displayName = "Calculate", displayNameZh = "数学计算")
    fun calculate(
        @ToolParam(name = "expression", description = "数学表达式，如 '2 + 3 * 4'")
        expression: String,
    ): String = execute("expression" to expression) {
        try {
            val result = evaluateExpression(expression)
            "$expression = $result"
        } catch (e: Exception) {
            "计算错误：${e.message}"
        }
    }

    @Tool(name = "convertUnit", description = "单位换算，例如 100 公里转英里", readOnly = true)
    @ToolMeta(displayName = "Convert Unit", displayNameZh = "单位换算")
    fun convertUnit(
        @ToolParam(name = "value", description = "数值")
        value: Double,
        @ToolParam(name = "from_unit", description = "源单位，如 'km'")
        fromUnit: String,
        @ToolParam(name = "to_unit", description = "目标单位，如 'mile'")
        toUnit: String,
    ): String = execute("value" to value, "fromUnit" to fromUnit, "toUnit" to toUnit) {
        val result = performConversion(value, fromUnit, toUnit)
        "$value $fromUnit = $result $toUnit"
    }

    override fun name(): String = NAME

    private fun evaluateExpression(expr: String): Double {
        val scriptEngine = javax.script.ScriptEngineManager().getEngineByName("js")
        return (scriptEngine.eval(expr) as Number).toDouble()
    }

    private fun performConversion(value: Double, from: String, to: String): Double {
        return value // 占位
    }

    companion object {
        const val NAME = "calculator-tool-box"
    }
}
```

## 提交前检查清单

- [ ] 类继承了 `ToolBox()`
- [ ] `@Component("name-tool-box")` 显式指定了 bean 名称
- [ ] 每个工具方法都有 `@Tool(name, description)` 注解
- [ ] 每个工具方法都有 `@ToolMeta(displayName, displayNameZh)`
- [ ] 所有需要 LLM 传入的参数都加了 `@ToolParam(name, description)` 注解
- [ ] 框架注入参数（如 `ToolEnvContext`）没有加 `@ToolParam`
- [ ] `override fun name()` 返回值与 bean 名称一致
- [ ] 所有工具方法都用 `execute { ... }` 包装
- [ ] 修改状态的方法设置了 `needConfirm = true`
- [ ] 需要外部 API 密钥时声明了 `envParamDefs`
- [ ] 外部依赖已添加到 `harnax-tools-buildin/pom.xml`
- [ ] 编写了单元测试
