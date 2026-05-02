## 1. SpringBoot 项目

### 1.1 项目组件及其版本

#### 核心框架
| 组件 | 版本 | 说明 |
|------|------|------|
| **Spring Boot** | 3.5.8 | 核心框架 |
| **Java** | 21 | JDK 版本 |
| **Kotlin** | 2.2.20 | 主要开发语言 |
| **Maven** | - | 构建工具 |

#### 数据库相关
| 组件 | 版本 | 说明 |
|------|------|------|
| **MyBatis Spring Boot Starter** | 3.0.4 | ORM 框架 |
| **MySQL Connector/J** | (由 Spring Boot 管理) | MySQL 驱动 |
| **HikariCP** | 5.0.1 | 数据库连接池 |
| **PageHelper** | 2.1.0 (Spring Boot集成) / 6.1.0 (核心库) | 分页插件 |

#### 安全与认证
| 组件 | 版本 | 说明 |
|------|------|------|
| **Spring Security** | (由 Spring Boot 管理) | 安全框架 |
| **JJWT (Java JWT)** | 0.12.3 | JWT 令牌处理 |
| **jBCrypt** | 0.4 | 密码加密 |

#### API 文档
| 组件 | 版本 | 说明 |
|------|------|------|
| **Springdoc OpenAPI** | 2.3.0 | Swagger 3 / OpenAPI 3 文档生成 |

#### AI 智能体
| 组件 | 版本 | 说明 |
|------|------|------|
| **AgentScope** | 1.0.10 | AI 智能体框架 |

#### Web 与 HTTP
| 组件 | 版本 | 说明 |
|------|------|------|
| **Spring Web MVC** | (由 Spring Boot 管理) | REST API |
| **Spring WebFlux** | (由 Spring Boot 管理) | 响应式编程 |

#### 定时任务
| 组件 | 版本 | 说明 |
|------|------|------|
| **Spring Quartz** | (由 Spring Boot 管理) | 定时任务调度 |

#### JSON 处理
| 组件 | 版本 | 说明 |
|------|------|------|
| **Jackson Databind** | (由 Spring Boot 管理) | JSON 序列化 |
| **Jackson Module Kotlin** | (由 Spring Boot 管理) | Kotlin 支持 |
| **Jackson Dataformat XML** | (由 Spring Boot 管理) | XML 处理 |

#### 测试框架
| 组件 | 版本 | 说明 |
|------|------|------|
| **Spring Boot Test** | (由 Spring Boot 管理) | 集成测试 |
| **Mockito Kotlin** | 5.4.0 | Kotlin Mock 框架 |
| **Mockito JUnit Jupiter** | 5.10.0 | Mock 测试 |
| **TestContainers (MySQL)** | 1.19.3 | 容器化测试 |
| **TestContainers JUnit** | 1.19.3 | 容器测试集成 |
| **MyBatis Test** | 3.0.3 | MyBatis 测试 |

#### 其他工具
| 组件 | 版本 | 说明 |
|------|------|------|
| **Eclipse JGit** | (由 AgentScope BOM 管理) | Git 操作库 |
| **SLF4J** | (由 Spring Boot 管理) | 日志门面 |
| **Jakarta Servlet API** | (由 Spring Boot 管理) | Servlet 规范 |

#### 构建插件
| 插件 | 版本 | 说明 |
|------|------|------|
| **Spring Boot Maven Plugin** | 3.5.8 | Spring Boot 打包 |
| **Kotlin Maven Plugin** | 2.2.20 | Kotlin 编译 |
| **Maven Compiler Plugin** | 3.11.0 | Java 编译 |
| **Kotlin All-Open** | 2.2.20 | Kotlin Spring 支持 |

#### 项目模块结构
- **vipclaw-admin**: 管理后台服务（主应用）
- **vipclaw-agent**: 智能体模块（包含 vipclaw-agent-core）
- **vipclaw-channel**: 多渠道集成模块
- **vipclaw-common**: 公共工具模块

#### 版本发布配置
项目支持三个版本 Profile：
- **personal** (默认激活): 个人版
- **enterprise**: 企业版
- **public**: 公网版

## 2. 前端项目 (vipclaw-webui)

### 2.1 前端技术栈及版本

- **暗黑模式支持**: 已完整实现并归档，覆盖 ThemeContext、ThemeProvider、系统偏好检测、主题解析、动态更新监听等核心能力，以及所有 UI 组件（卡片、分页、渐变效果、滚动条、文件树、Markdown、代码高亮等）的深色模式适配

#### 核心框架
| 组件 | 版本 | 说明 |
|------|------|------|
| **React** | 18.3.1 | UI 框架 |
| **TypeScript** | 5.6.3 | 类型系统 |
| **Umi Max** | 4.0.7 | 企业级前端应用框架 |
| **Node.js** | >=20.0.0 | 运行环境 |

#### UI 组件库
| 组件 | 版本 | 说明 |
|------|------|------|
| **Ant Design** | 5.25.4 | 企业级 UI 组件库 |
| **Ant Design Pro Components** | 2.8.10 | 高级业务组件 |
| **Ant Design Icons** | 5.6.1 | 图标库 |
| **Ant Design Charts** | 2.6.7 | 图表组件 |
| **Ant Design Plots** | 2.6.8 | 可视化图表 |
| **Antd Style** | 3.7.0 | 样式方案 |
| **Ant Design v5 Patch for React 19** | 1.0.3 | React 19 兼容补丁 |

#### 工具库
| 组件 | 版本 | 说明 |
|------|------|------|
| **Day.js** | 1.11.13 | 日期处理库 |
| **Classnames** | 2.5.1 | CSS 类名处理 |
| **Crypto-JS** | 4.2.0 | 加密算法库 |
| **React Markdown** | 10.1.0 | Markdown 渲染 |
| **React Syntax Highlighter** | 16.1.1 | 代码语法高亮 |
| **Remark GFM** | 4.0.1 | GitHub Flavored Markdown 支持 |

#### 开发工具
| 组件 | 版本 | 说明 |
|------|------|------|
| **Biome** | 2.0.6 | 代码格式化和 linting |
| **Jest** | 30.0.4 | 单元测试框架 |
| **Testing Library** | 10.4.0 / 16.0.1 | 测试工具库 |
| **Husky** | 9.1.7 | Git hooks 管理 |
| **Lint-staged** | 16.1.2 | 暂存文件 linting |
| **Commitlint** | 19.5.0 | Git 提交规范 |
| **Cross-env** | 7.0.3 | 跨平台环境变量设置 |

#### 类型定义
| 组件 | 版本 | 说明 |
|------|------|------|
| **@types/react** | 19.1.5 | React 类型定义 |
| **@types/react-dom** | 19.1.5 | React DOM 类型定义 |
| **@types/node** | 24.0.10 | Node.js 类型定义 |
| **@types/lodash** | 4.17.10 | Lodash 类型定义 |
| **@types/jest** | 30.0.0 | Jest 类型定义 |

#### 构建与部署
| 组件 | 版本 | 说明 |
|------|------|------|
| **TS-Node** | 10.9.2 | TypeScript 执行引擎 |
| **Express** | 4.21.1 | 开发服务器 |
| **gh-pages** | 6.1.1 | GitHub Pages 部署 |
| **Mock.js** | 1.1.0 |  Mock 数据生成 |

### 2.2 项目配置

#### 构建脚本
- `dev` / `start:dev`: 开发模式启动
- `build`: 标准构建
- `build:personal`: 个人版构建
- `build:enterprise`: 企业版构建
- `build:public`: 公网版构建
- `analyze`: 打包分析
- `lint`: 代码检查
- `test`: 运行测试

#### Umi 插件配置
- **数据流插件** (model): 状态管理
- **初始状态插件** (initialState): 全局状态
- **Layout 插件**: 布局配置
- **国际化插件** (locale): 多语言支持 (默认 zh-CN)
- **Antd 插件**: 主题配置 (AlibabaSans 字体)
- **请求插件** (request): 统一网络请求
- **权限插件** (access): 权限控制
- **OpenAPI 插件**: API 代码生成
- **Moment2Dayjs 插件**: 日期库替换

#### TypeScript 配置
- **目标**: ES2015
- **模块系统**: ES2015
- **JSX**: React
- **Source Map**: 启用
- **模块解析**: Node

### 2.3 暗黑模式支持规范

#### 强制要求
- 所有新开发的前端组件必须同时支持浅色模式（light mode）和深色模式（dark mode）
- 必须使用 CSS 变量系统管理所有颜色，禁止硬编码颜色值（#hex, rgb(), rgba(), hsl(), hsla(), color names）
- 必须提供完整的主题变量映射，包括主色调、功能色、文本色、背景色、边框色、阴影等
- 必须为所有 rgba() 函数提供对应的 RGB 组件变量（如 --vip-primary-rgb: 79, 110, 247）
- 必须在 HTML 根元素上使用 `html.dark` 类进行主题切换

#### 实现标准
- 使用 ThemeContext 和 ThemeProvider 管理主题状态
- 支持系统偏好检测（prefers-color-scheme）
- 主题切换必须平滑过渡，包含适当的 CSS 过渡动画
- 所有 UI 组件（卡片、分页、渐变效果、滚动条、文件树、Markdown、代码高亮等）必须完整适配
- 必须测试所有组件在 light/dark 模式下的显示效果和交互体验

#### 验收检查
- 新组件提交前必须通过暗黑模式兼容性检查
- 所有颜色相关的 CSS 属性必须使用 CSS 变量
- 不得存在任何硬编码颜色值
- 主题切换必须实时生效且无闪烁

### 2.4 国际化（i18n）开发规范

#### 架构设计
- **后端**: 基于 Spring Boot MessageSource + LocaleResolver 实现
- **前端**: 基于 Umi Max 国际化插件 + react-intl 实现
- **语言同步**: 通过 HTTP 请求头 `Accept-Language` 传递用户语言偏好

#### 后端国际化规范

##### 消息资源文件管理
- 所有消息资源文件必须放置在 `src/main/resources/i18n/` 目录下
- 文件命名规范：`{module}_{locale}.properties`
  - 默认文件：`messages.properties`（英文）
  - 中文文件：`messages_zh_CN.properties`
  - 错误消息：`messages_error.properties`, `messages_error_zh_CN.properties`
- 必须保持中英文消息文件的 key 完全一致
- 新增消息时必须同时更新中英文文件

##### 消息 Key 命名规范
- 采用点分隔的层级命名：`{module}.{submodule}.{messageType}`
- 示例：
  - `user.login.success` - 用户登录成功消息
  - `user.validation.email.invalid` - 用户邮箱验证错误
  - `agent.create.failed` - 智能体创建失败
- Key 必须使用小写字母和点号，避免使用特殊字符
- 错误消息 Key 必须以 `error.` 开头

##### 消息内容规范
- 消息中需要动态替换的参数使用 `{0}`, `{1}`, `{2}` 占位符
- 示例：`user.welcome=Welcome, {0}! Your last login was {1}`
- 避免在消息中硬编码 HTML 或 Markdown 格式
- 消息文本应该完整，不要拼接多个消息 key

##### 代码使用规范
```kotlin
// 使用 MessageUtil 工具类获取国际化消息
val message = MessageUtil.getMessage("user.login.success")
val messageWithParams = MessageUtil.getMessage("user.welcome", userName, lastLoginTime)

// 在异常中使用
throw BusinessException(ErrorCode.USER_NOT_FOUND, 
    MessageUtil.getMessage("error.user.notFound", userId))
```

##### 错误消息国际化
- 所有 `BusinessException` 必须使用国际化消息
- 错误码（ErrorCode）与消息 key 建立映射关系
- 全局异常处理器必须根据请求头 `Accept-Language` 返回对应语言的消息
- 禁止在代码中硬编码错误消息文本

#### 前端国际化规范

##### 消息文件管理
- 所有国际化文案放置在 `src/locales/` 目录下
- 中文文件：`zh-CN.ts`
- 英文文件：`en-US.ts`
- 必须保持两个文件的 key 结构和数量完全一致

##### 消息 Key 命名规范
- 采用点分隔的层级命名：`{page}.{component}.{messageType}`
- 示例：
  - `pages.login.title` - 登录页面标题
  - `pages.agent.create.success` - 智能体创建成功提示
  - `components.table.noData` - 表格无数据提示
- Key 必须使用小写字母和点号
- 公共消息使用 `global.*` 前缀

##### 组件使用规范
```typescript
import { useIntl } from 'umi';

const MyComponent = () => {
  const intl = useIntl();
  
  // 简单消息
  const title = intl.formatMessage({ 
    id: 'pages.agent.title',
    defaultMessage: 'Agent Management' 
  });
  
  // 带参数的消息
  const welcome = intl.formatMessage(
    { 
      id: 'pages.user.welcome',
      defaultMessage: 'Welcome, {name}!'
    },
    { name: userName }
  );
  
  return <h1>{title}</h1>;
};
```

##### 语言同步机制
- Axios 请求拦截器必须添加 `Accept-Language` 请求头
- 从 `localStorage` 读取 `umi_locale` 作为语言设置
- 语言切换时自动更新请求头

```typescript
// requestErrorConfig.ts
axios.interceptors.request.use((config) => {
  const locale = localStorage.getItem('umi_locale') || 'zh-CN';
  config.headers['Accept-Language'] = locale;
  return config;
});
```

##### 禁止行为
- ❌ 禁止在组件中硬编码中文或英文文本
- ❌ 禁止在同一个组件中混用 `intl.formatMessage` 和硬编码文本
- ❌ 禁止在消息文件中使用 HTML 标签（特殊场景除外）
- ❌ 禁止删除或遗漏任何一种语言的消息 key

#### 开发流程

##### 新增页面/组件
1. 在 `zh-CN.ts` 和 `en-US.ts` 中添加对应的消息 key
2. 在组件中使用 `useIntl()` 获取 intl 对象
3. 使用 `intl.formatMessage()` 渲染文本
4. 测试中英文切换是否正常

##### 新增后端消息
1. 在 `messages.properties` 和 `messages_zh_CN.properties` 中添加 key
2. 使用 `MessageUtil.getMessage()` 获取消息
3. 编写单元测试验证消息获取
4. 测试不同语言下的响应

##### 代码审查检查项
- [ ] 所有用户可见文本已国际化
- [ ] 消息 key 遵循命名规范
- [ ] 中英文消息文件保持同步
- [ ] 错误消息使用国际化
- [ ] 无硬编码文本残留
- [ ] 参数化消息使用正确的占位符

#### 验收标准
- 所有页面和组件支持中英文切换
- 所有 API 错误消息根据请求头返回对应语言
- 消息文件无遗漏的 key
- 代码审查 100% 通过国际化检查项
- 无硬编码文本（除特殊场景如品牌名称）

### 2.5 搜索筛选框统一规范

#### 组件架构

所有管理页面的搜索筛选区域必须使用统一的公共组件，确保样式一致性和代码可维护性。

**组件位置**: `src/components/SearchFilterBar/`

**核心组件**:
| 组件名 | 说明 | 用途 |
|--------|------|------|
| `SearchFilterBar` | 搜索筛选栏容器 | 主容器，包含搜索区和操作区 |
| `SearchInput` | 搜索输入框 | 带搜索图标的输入框 |
| `FilterSelect` | 筛选下拉框 | 统一样式的筛选下拉框 |
| `ActionButton` | 操作按钮 | 右侧操作按钮（新建、导入等） |

#### 强制要求

- ✅ **必须使用公共组件**：所有管理页面的筛选框必须使用 `SearchFilterBar` 及其子组件
- ✅ **禁止硬编码样式**：不得手动设置 borderRadius、height、fontSize 等统一样式
- ✅ **保持样式一致**：所有页面的筛选框必须保持相同的视觉规格
- ✅ **支持国际化**：按钮文本必须通过 props 传入翻译后的文本

#### 样式规格标准

##### 搜索卡片 (SearchFilterBar)
```typescript
{
  borderRadius: '12px',           // 圆角
  padding: '12px 20px',           // 内边距
  border: '1px solid var(--vip-border)',  // 边框
  boxShadow: '0 2px 12px rgba(0,0,0,0.04)', // 阴影
  marginBottom: 24                // 下边距
}
```

##### 筛选组件通用规格
```typescript
{
  height: '28px',                 // 统一高度
  fontSize: '12px',               // 统一字体
  borderRadius: '6px'             // 统一圆角
}
```

##### 搜索输入框 (SearchInput)
```typescript
{
  width: 240,                     // 默认宽度（可通过 props 调整）
  prefix: {
    color: '#8c8c9a',             // 图标颜色
    fontSize: '12px'              // 图标大小
  }
}
```

##### 筛选下拉框 (FilterSelect)
```typescript
{
  width: 120                      // 默认宽度（可通过 props 调整）
}
```

##### 按钮规格
```typescript
// 搜索/重置按钮
{
  height: '28px',
  padding: '0 12px',
  fontSize: '12px'
}

// 操作按钮（右侧）
{
  height: '28px',
  padding: '0 16px',
  fontSize: '12px',
  fontWeight: 500
}
```

##### 布局规格
```typescript
// 主容器
{
  display: 'flex',
  alignItems: 'center',
  gap: 16,                        // 搜索区和操作区间距
  flexWrap: 'wrap'                // 支持响应式换行
}

// 搜索组容器
{
  display: 'flex',
  alignItems: 'center',
  gap: 12,                        // 筛选组件间距
  flex: 1,
  minWidth: 300                   // 最小宽度
}
```

#### 使用标准

##### 基础示例
```tsx
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';
import { PlusOutlined } from '@ant-design/icons';

<SearchFilterBar
  onSearch={handleSearch}
  onReset={handleReset}
  searchText={intl.formatMessage({ id: 'pages.common.search', defaultMessage: 'Search' })}
  resetText={intl.formatMessage({ id: 'pages.common.reset', defaultMessage: 'Reset' })}
  extra={
    <ActionButton
      type="primary"
      icon={<PlusOutlined />}
      onClick={handleCreate}
    >
      {intl.formatMessage({ id: 'pages.common.create', defaultMessage: 'Create' })}
    </ActionButton>
  }
>
  <SearchInput
    value={keyword}
    onChange={setKeyword}
    onSearch={handleSearch}
    placeholder={intl.formatMessage({ id: 'pages.placeholder.search', defaultMessage: 'Please enter to search' })}
  />
  <FilterSelect
    value={status}
    onChange={setStatus}
    placeholder={intl.formatMessage({ id: 'pages.placeholder.statusFilter', defaultMessage: 'Status filter' })}
    options={[
      { label: intl.formatMessage({ id: 'pages.common.enabled', defaultMessage: 'Enabled' }), value: 1 },
      { label: intl.formatMessage({ id: 'pages.common.disabled', defaultMessage: 'Disabled' }), value: 0 },
    ]}
  />
</SearchFilterBar>
```

##### 多选筛选示例
```tsx
<FilterSelect
  mode="multiple"
  value={types}
  onChange={setTypes}
  placeholder="类型筛选"
  width={160}
  options={[
    { label: 'STDIO', value: 'stdio' },
    { label: 'SSE', value: 'sse' },
    { label: 'HTTP', value: 'http' },
  ]}
/>
```

##### 自定义宽度示例
```tsx
<SearchInput
  value={keyword}
  onChange={setKeyword}
  placeholder="搜索..."
  width={280}  // 自定义宽度
/>

<FilterSelect
  value={status}
  onChange={setStatus}
  placeholder="状态"
  width={140}  // 自定义宽度
  options={[...]}
/>
```

#### 迁移指南

##### 旧代码模式（禁止）
```tsx
// ❌ 错误：手动编写样式
<Card
  style={{ marginBottom: 24, borderRadius: '12px', boxShadow: '0 2px 12px rgba(0,0,0,0.04)' }}
  styles={{ body: { padding: '12px 20px' } }}
>
  <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, flex: 1, minWidth: 300 }}>
      <Input
        placeholder="搜索..."
        value={keyword}
        onChange={(e) => setKeyword(e.target.value)}
        style={{ width: 240, borderRadius: '6px', height: '28px', fontSize: '12px' }}
      />
      <Select
        placeholder="状态"
        value={status}
        onChange={(val) => setStatus(val)}
        style={{ width: 120, height: '28px', fontSize: '12px' }}
        options={[...]}
      />
      <Button type="primary" onClick={handleSearch} style={{ borderRadius: '6px', height: '28px', padding: '0 12px', fontSize: '12px' }}>
        查询
      </Button>
      <Button onClick={handleReset} style={{ borderRadius: '6px', height: '28px', padding: '0 12px', fontSize: '12px' }}>
        重置
      </Button>
    </div>
    <Button type="primary" onClick={handleCreate} style={{ borderRadius: '6px', height: '28px', padding: '0 16px', fontSize: '12px' }}>
      新建
    </Button>
  </div>
</Card>
```

##### 新代码模式（推荐）
```tsx
// ✅ 正确：使用公共组件
import SearchFilterBar, { SearchInput, FilterSelect, ActionButton } from '@/components/SearchFilterBar';

<SearchFilterBar
  onSearch={handleSearch}
  onReset={handleReset}
  extra={
    <ActionButton type="primary" onClick={handleCreate}>
      新建
    </ActionButton>
  }
>
  <SearchInput
    value={keyword}
    onChange={setKeyword}
    onSearch={handleSearch}
    placeholder="搜索..."
  />
  <FilterSelect
    value={status}
    onChange={setStatus}
    placeholder="状态"
    options={[...]}
  />
</SearchFilterBar>
```

**代码减少**: 约 50%（从 ~40 行减少到 ~20 行）

#### 验收检查

##### 代码审查检查项
- [ ] 使用 `SearchFilterBar` 公共组件，而非手动编写 Card 和样式
- [ ] 使用 `SearchInput` 组件，而非直接使用 Ant Design Input
- [ ] 使用 `FilterSelect` 组件，而非直接使用 Ant Design Select
- [ ] 使用 `ActionButton` 组件，而非直接使用 Ant Design Button
- [ ] 未手动设置 borderRadius、height、fontSize 等统一样式
- [ ] 按钮文本通过 `searchText`、`resetText` 等 props 传入
- [ ] 支持国际化（使用 `intl.formatMessage`）
- [ ] 响应式布局正常（小屏幕自动换行）

##### 视觉验收检查项
- [ ] 搜索卡片圆角为 12px
- [ ] 搜索卡片内边距为 12px 20px
- [ ] 搜索卡片有边框（1px solid var(--vip-border)）
- [ ] 所有筛选组件高度为 28px
- [ ] 所有筛选组件字体为 12px
- [ ] 所有筛选组件圆角为 6px
- [ ] 搜索输入框宽度为 240px（或自定义宽度）
- [ ] 筛选下拉框宽度为 120px（或自定义宽度）
- [ ] 搜索图标颜色为 #8c8c9a
- [ ] 搜索区和操作区间距为 16px
- [ ] 筛选组件间距为 12px

#### 已应用页面

- ✅ Agent 管理页面
- ⏳ MCP 管理页面（待迁移）
- ⏳ Channel 管理页面（待迁移）
- ⏳ Job 管理页面（待迁移）
- ⏳ Job Log 执行日志页面（待迁移）
- ⏳ 用户管理页面（待迁移）
- ⏳ 租户管理页面（待迁移）

#### 禁止行为

- ❌ 禁止在管理页面中手动编写筛选框样式
- ❌ 禁止直接使用 Ant Design 的 Input、Select、Button 组件作为筛选框
- ❌ 禁止在筛选框中使用硬编码的 borderRadius、height、fontSize
- ❌ 禁止在不同的页面使用不同的筛选框样式规格
- ❌ 禁止在筛选框中混用多种样式规范

#### 开发流程

##### 新增管理页面
1. 导入 `SearchFilterBar` 及其子组件
2. 使用 `SearchFilterBar` 作为筛选栏容器
3. 在 children 中添加 `SearchInput` 和 `FilterSelect`
4. 在 extra 中添加 `ActionButton`（如需要）
5. 实现 `onSearch` 和 `onReset` 回调
6. 测试样式是否与其他页面一致

##### 迁移旧页面
1. 识别现有的筛选框代码（Card + Input + Select + Button）
2. 替换为 `SearchFilterBar` 组件
3. 移除所有手动设置的样式（borderRadius、height、fontSize 等）
4. 使用 `SearchInput` 替换 Input
5. 使用 `FilterSelect` 替换 Select
6. 使用 `ActionButton` 替换操作按钮
7. 测试功能和样式

#### 组件文档

详细使用说明和 API 文档请参考：
- 组件实现：`src/components/SearchFilterBar/index.tsx`
- 类型声明：`src/components/SearchFilterBar/index.d.ts`
- 使用说明：`src/components/SearchFilterBar/README.md`
