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
4. 使用 `SearchInput`、`FilterSelect`、`ActionButton` 替换原生组件
5. 测试功能和样式是否正常

### 2.7 弹窗（FormModal）样式规范

#### 组件架构

所有管理页面的表单弹窗必须使用统一的 `FormModal` 组件，确保弹窗样式和交互体验的一致性。

**组件位置**: `src/components/FormModal/FormModal.tsx`

**样式文件**: `src/components/FormModal/FormModal.less`

#### 核心特性

##### 1. 动态宽度计算
- 弹窗宽度根据内容高度的 1.3 倍自动计算（`width = height × 1.3`）
- 最小宽度为浏览器窗口宽度的 50%（`minWidth = window.innerWidth × 0.5`）
- 最终宽度取计算宽度和最小宽度中的较大值：`finalWidth = max(height × 1.3, window.innerWidth × 0.5)`
- 支持通过 `size` 属性使用预设尺寸：`sm`(480px)、`md`(620px)、`lg`(760px)、`xl`(900px)
- 支持通过 `width` 属性传入自定义宽度覆盖默认计算
- 优先级：`width` > `size` > 自动计算
- 输入框宽度根据弹窗宽度自适应：`width: calc(100% - 168px)`

##### 2. 标题布局
- **图标和文字水平排列**：使用 `display: flex` 实现
- **主标题和副标题垂直排列**：副标题在主标题下方，字号更小
- **透明背景**：弹窗头部不使用背景色，保持透明

##### 3. 表单布局
- 采用水平布局（`layout="horizontal"`）
- 标签列占比：`labelCol={{ span: 6 }}`
- 控件列占比：`wrapperCol={{ span: 18 }}`
- 控件最大宽度：`max-width: 480px`

#### 强制要求

- ✅ **必须使用 FormModal 组件**：所有管理弹窗必须使用 `FormModal` 组件
- ✅ **禁止硬编码颜色**：必须使用 VIP 主题 CSS 变量（如 `var(--vip-text-primary)`）
- ✅ **标题必须包含图标和副标题**：提供清晰的视觉层次和功能说明
- ✅ **表单控件统一高度**：所有输入框、下拉框高度统一为 32px
- ✅ **按钮区域统一样式**：底部按钮区域使用统一间距和样式

#### 样式规格标准

##### 弹窗容器
```typescript
{
  borderRadius: '12px',           // 弹窗圆角
  overflow: 'hidden',             // 溢出隐藏
  boxShadow: '0 12px 48px rgba(0, 0, 0, 0.15)'  // 阴影
}
```

##### 弹窗头部
```less
.ant-modal-header {
  background: transparent !important;        // 透明背景
  border-bottom: 1px solid var(--vip-border); // 底部边框
  padding: 16px 24px !important;             // 内边距
  border-radius: 12px 12px 0 0 !important;   // 顶部圆角
}
```

##### 标题容器
```less
.form-modal-title {
  display: flex;                   // 水平排列
  align-items: center;             // 垂直居中
  gap: 12px;                       // 图标和文字间距
}
```

##### 标题图标
```less
.form-modal-icon {
  width: 32px;                     // 图标容器宽度
  height: 32px;                    // 图标容器高度
  border-radius: 8px;              // 图标圆角
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);  // 图标阴影
  
  .anticon {
    font-size: 16px;               // 图标大小
    color: #fff;                   // 图标颜色（白色）
  }
}
```

##### 标题文字
```less
.form-modal-title-text {
  .form-modal-title-main {
    font-size: 15px;               // 主标题字号
    font-weight: 600;              // 主标题字重
    color: var(--vip-text-primary);
    line-height: 1.4;
  }
  
  .form-modal-title-subtitle {
    font-size: 11px;               // 副标题字号
    color: var(--vip-text-secondary);
    margin-top: 2px;               // 与主标题间距
    font-weight: 400;
    line-height: 1.4;
  }
}
```

##### 弹窗主体
```typescript
{
  padding: '20px 24px',            // 内边距
  background: 'var(--vip-bg-container)',  // 背景色
  borderRadius: '0 0 12px 12px'    // 底部圆角
}
```

##### 表单标签
```less
.ant-form-item-label > label {
  font-size: 12px !important;
  font-weight: 500 !important;
  height: 32px !important;
  line-height: 32px !important;
  display: flex !important;
  align-items: center !important;
  justify-content: flex-end !important;  // 标签右对齐
  padding-right: 12px !important;
  color: var(--vip-text-primary) !important;
}
```

##### 表单控件容器
```less
.ant-form-item-control {
  width: calc(100% - 168px);       // 根据弹窗宽度动态计算
  max-width: 100%;
}
```

##### 输入框/下拉框统一规格
```less
.ant-input,
.ant-select .ant-select-selector,
.ant-picker {
  height: 32px !important;         // 统一高度
  font-size: 12px !important;      // 统一字体
  padding: 4px 10px !important;    // 内边距
  border-radius: 6px !important;   // 圆角
  border: 1px solid var(--vip-border) !important;
  background: var(--vip-bg-container) !important;
  transition: all 0.2s ease !important;
  
  &:hover {
    border-color: var(--vip-primary) !important;
  }
  
  &:focus {
    border-color: var(--vip-primary) !important;
    box-shadow: 0 0 0 2px rgba(var(--vip-primary-rgb), 0.1) !important;
  }
}
```

##### 表单项间距
```less
.ant-form-item {
  margin-bottom: 14px !important;  // 表单项间距
}
```

##### 底部按钮区域
```less
.form-modal-actions {
  display: flex;
  justify-content: flex-end;       // 按钮右对齐
  gap: 10px;                       // 按钮间距
  margin-top: 12px;                // 上边距
  padding-top: 10px;               // 内边距
  border-top: 1px solid var(--vip-border);  // 顶部分割线
  
  button {
    font-size: 12px !important;
    font-weight: 500 !important;
    height: 32px !important;
    padding: 4px 20px !important;
    border-radius: 6px !important;
    transition: all 0.2s ease !important;
  }
}
```

#### 使用标准

##### 基础示例
```tsx
import { FormModal } from '@/components/FormModal';
import { UserOutlined } from '@ant-design/icons';
import { ProForm, ProFormText } from '@ant-design/pro-components';

const CreateForm: React.FC<CreateFormProps> = (props) => {
  const { onCancel, onSubmit, visible } = props;
  
  return (
    <FormModal
      open={visible}
      onCancel={onCancel}
      titleConfig={{
        mainTitle: '新建用户',
        subtitle: '填写用户基本信息，创建系统账号',
        icon: <UserOutlined />,
      }}
    >
      <ProForm
        onFinish={onSubmit}
        layout="horizontal"
        labelCol={{ span: 6 }}
        wrapperCol={{ span: 18 }}
        submitter={{
          render: (_, dom) => (
            <div style={{ 
              display: 'flex', 
              justifyContent: 'flex-end', 
              gap: '10px',
              marginTop: '12px',
              paddingTop: '10px',
              borderTop: '1px solid var(--vip-border)'
            }}>
              {dom.map((item) => 
                React.cloneElement(item, {
                  style: {
                    fontSize: '12px',
                    fontWeight: 500,
                    height: '32px',
                    padding: '4px 20px',
                    borderRadius: '6px',
                  }
                })
              )}
            </div>
          ),
        }}
      >
        <ProFormText
          name="username"
          label="用户名"
          placeholder="请输入用户名"
          rules={[{ required: true, message: '请输入用户名' }]}
        />
      </ProForm>
    </FormModal>
  );
};
```

##### 自定义宽度示例
```tsx
<FormModal
  open={visible}
  onCancel={onCancel}
  width={800}  // 自定义宽度，覆盖默认的 1.3 倍高度计算
  titleConfig={{
    mainTitle: '编辑配置',
    subtitle: '修改配置信息，保存后生效',
    icon: <SettingOutlined />,
  }}
>
  {/* 表单内容 */}
</FormModal>
```

##### 预设尺寸示例
```tsx
// 小尺寸弹窗（480px）
<FormModal size="sm" titleConfig={...}>
  {/* 简单表单 */}
</FormModal>

// 中等尺寸弹窗（620px）
<FormModal size="md" titleConfig={...}>
  {/* 标准表单 */}
</FormModal>

// 大尺寸弹窗（760px）
<FormModal size="lg" titleConfig={...}>
  {/* 复杂表单 */}
</FormModal>

// 超大尺寸弹窗（900px）
<FormModal size="xl" titleConfig={...}>
  {/* 大型表单 */}
</FormModal>
```

#### 深色模式适配

所有颜色必须使用 CSS 变量，确保深色模式下正常显示：

```less
// ✅ 正确：使用 CSS 变量
color: var(--vip-text-primary);
background: var(--vip-bg-container);
border: 1px solid var(--vip-border);

// ❌ 错误：硬编码颜色
color: #000;
background: #fff;
border: 1px solid #d9d9d9;
```

深色模式下的特殊处理：

```less
html.dark .form-modal {
  .form-modal-icon {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);  // 更深的阴影
  }
  
  .ant-form-item-control {
    .ant-input,
    .ant-select .ant-select-selector {
      background: var(--vip-bg-container) !important;
      color: var(--vip-text-primary) !important;
    }
  }
}
```

#### 开发检查清单

创建或修改弹窗时，请确保：

- [ ] 使用 `FormModal` 组件而非直接使用 `Modal`
- [ ] 标题包含图标、主标题和副标题
- [ ] 头部背景色为 `transparent`
- [ ] 表单使用水平布局（`layout="horizontal"`）
- [ ] 标签和控件比例设置为 `6:18`
- [ ] 所有输入框高度统一为 32px
- [ ] 按钮区域使用统一间距（marginTop: 12px, paddingTop: 10px）
- [ ] 所有颜色使用 CSS 变量，无硬编码
- [ ] 测试深色模式下的显示效果
- [ ] 弹窗宽度合理，不会过宽或过窄

#### 已应用页面

所有管理页面的弹窗已统一使用 `FormModal` 组件：

| 页面 | 新建弹窗 | 编辑弹窗 | 样式统一 |
|------|---------|---------|----------|
| 用户管理 | ✅ | ✅ | ✅ |
| 租户管理 | ✅ | ✅ | ✅ |
| 任务管理 | ✅ | ✅ | ✅ |
| 模型管理 | ✅ | ✅ | ✅ |
| Channel管理 | ✅ | ✅ | ✅ |
| 智能体管理 | ✅ | ✅ | ✅ |
| 技能管理 | ✅ | ✅ | ✅ |
| MCP管理 | ✅ | ✅ | ✅ |
4. 使用 `SearchInput` 替换 Input
5. 使用 `FilterSelect` 替换 Select
6. 使用 `ActionButton` 替换操作按钮
7. 测试功能和样式

#### 组件文档

详细使用说明和 API 文档请参考：
- 组件实现：`src/components/SearchFilterBar/index.tsx`
- 类型声明：`src/components/SearchFilterBar/index.d.ts`
- 使用说明：`src/components/SearchFilterBar/README.md`

## 6. 表单弹窗组件化规范

### 6.1 核心原则

所有新建/编辑弹窗必须使用 `FormModal` 公共组件，实现：
- 标题样式统一（图标 + 主标题 + 副标题）
- 表单控件样式统一（输入框、密码框、下拉框等）
- 操作按钮统一（重置、提交按钮）
- 自动支持暗黑模式
- 自动国际化支持

### 6.2 组件清单

| 组件 | 文件路径 | 用途 |
|------|---------|------|
| **FormModal** | `src/components/FormModal/FormModal.tsx` | 弹窗容器，统一标题和布局 |
| **FormActions** | `src/components/FormModal/FormActions.tsx` | 表单操作按钮（提交、重置） |

### 6.3 使用规范

#### 弹窗标题规范
- 必须包含图标、主标题、副标题
- 图标使用 Ant Design Icon 组件
- 新建操作使用主色调渐变（`var(--vip-primary)`）
- 编辑操作使用警告色渐变（`var(--vip-warning)`）
- 删除操作使用错误色渐变（`var(--vip-error)`）

#### 表单控件规范
- **输入框**：高度 36px，圆角 6px，边框色 `var(--vip-border)`
- **密码框**：与输入框一致样式
- **下拉框**：高度 36px，圆角 6px
- **日期选择器**：高度 36px，圆角 6px
- **文本域**：最小高度 80px

#### 操作按钮规范
- **提交按钮**：高度 36px，圆角 6px，主色背景
- **重置按钮**：高度 36px，圆角 6px，默认样式
- 按钮区域：上边框 1px solid `var(--vip-border)`，上边距 24px，内边距 20px 0

#### 布局规范
- 弹窗宽度：默认 680px
- 表单项间距：18px
- 标签宽度：`labelCol={{ span: 6 }}`
- 控件宽度：`wrapperCol={{ span: 18 }}`
- 弹窗内边距：28px 32px

### 6.4 使用示例

```tsx
import { FormModal } from '@/components/FormModal';
import { UserOutlined } from '@ant-design/icons';

<FormModal
  open={visible}
  onCancel={onCancel}
  titleConfig={{
    mainTitle: intl.formatMessage({
      id: 'pages.user.management.add',
      defaultMessage: '新建用户',
    }),
    subtitle: intl.formatMessage({
      id: 'pages.user.management.add.subtitle',
      defaultMessage: '填写用户基本信息，创建系统账号',
    }),
    icon: <UserOutlined />,
  }}
>
  <ProForm
    layout="horizontal"
    labelCol={{ span: 6 }}
    wrapperCol={{ span: 18 }}
  >
    <ProFormText name="username" label="用户名" />
    <ProFormText.Password name="password" label="密码" />
    <ProFormSelect name="gender" label="性别" options={[]} />
  </ProForm>
</FormModal>
```

### 6.5 样式验收检查项

#### 标题验收
- [ ] 包含图标、主标题、副标题
- [ ] 图标尺寸 36x36px，圆角 10px
- [ ] 主标题字号 18px，字重 700
- [ ] 副标题字号 12px，颜色 `var(--vip-text-secondary)`
- [ ] 弹窗头部有渐变背景

#### 表单控件验收
- [ ] 所有输入框高度 36px
- [ ] 所有输入框圆角 6px
- [ ] 所有输入框边框色 `var(--vip-border)`
- [ ] 聚焦状态有主色边框和阴影
- [ ] 标签右对齐，字重 500

#### 操作按钮验收
- [ ] 按钮高度 36px，圆角 6px
- [ ] 按钮区域有上边框分隔
- [ ] 提交按钮为主色背景
- [ ] 按钮右对齐

#### 暗黑模式验收
- [ ] 深色模式下背景为 `var(--vip-bg-container)`
- [ ] 深色模式下文本为 `var(--vip-text-primary)`
- [ ] 深色模式下边框为 `var(--vip-border)`
- [ ] 主题切换无闪烁

### 6.6 禁止行为

- ❌ 禁止直接使用 Ant Design Modal 创建表单弹窗
- ❌ 禁止在弹窗中硬编码标题样式
- ❌ 禁止在表单控件中手动设置高度、圆角等样式
- ❌ 禁止在不同页面使用不同的弹窗样式规格
- ❌ 禁止在按钮区域使用非标准样式

### 6.7 开发流程

#### 新建表单弹窗
1. 导入 `FormModal` 组件
2. 配置 `titleConfig`（图标、主标题、副标题）
3. 添加 `ProForm` 组件
4. 设置 `layout="horizontal"` 和列比例
5. 配置 `submitter` 自定义按钮区域
6. 测试浅色和深色模式

#### 迁移旧弹窗
1. 识别现有的 Modal 代码
2. 替换为 `FormModal` 组件
3. 配置 `titleConfig` 替代原有 title
4. 移除所有手动设置的样式
5. 测试功能和样式

### 6.8 组件文档

详细使用说明和 API 文档请参考：
- 组件实现：`src/components/FormModal/FormModal.tsx`
- 样式文件：`src/components/FormModal/FormModal.less`
- 使用说明：`src/components/FormModal/README.md`

## 7. 操作按钮组件化规范

### 7.1 核心原则

所有操作按钮必须使用独立的场景无关组件，不区分卡片或表格使用场景。通过组件化实现：
- 代码减少 70-80%
- 样式完全统一
- 自动国际化支持
- 类型安全（TypeScript）
- 场景无关，随处可用
- 易于维护和测试

### 5.2 独立按钮组件清单

#### 基础操作按钮

| 组件 | 文件路径 | 用途 | 默认颜色 | 图标 |
|------|---------|------|---------|------|
| **EditButton** | `src/components/EditButton/index.tsx` | 编辑操作 | `var(--vip-primary)` | EditOutlined |
| **DeleteButton** | `src/components/DeleteButton/index.tsx` | 删除操作（内置Popconfirm） | `var(--vip-danger)` | DeleteOutlined |

#### 业务特定按钮

| 组件 | 文件路径 | 用途 | 默认颜色 | 图标 |
|------|---------|------|---------|------|
| **TestButton** | `src/components/TestButton/index.tsx` | 连接测试 | `var(--vip-warning)` | ThunderboltOutlined |
| **SyncButton** | `src/components/SyncButton/index.tsx` | 仓库同步（支持loading） | `var(--vip-primary)` | SyncOutlined |
| **RunButton** | `src/components/RunButton/index.tsx` | 立即执行 | `var(--vip-info)` | PlayCircleOutlined |
| **ManageUsersButton** | `src/components/ManageUsersButton/index.tsx` | 管理用户 | `var(--vip-primary)` | UserOutlined |

#### 状态控制组件

| 组件 | 文件路径 | 用途 | 颜色 |
|------|---------|------|------|
| **StatusSwitch** | `src/components/StatusSwitch/index.tsx` | 启用/停用开关 | 启用: `var(--vip-primary)`<br>停用: `var(--vip-border)` |

### 5.3 组件特性

#### 通用特性
- **默认样式**: `type="link"`, `padding: '4px'`, `size="small"`
- **事件冒泡**: 默认阻止事件冒泡（`stopPropagation: true`）
- **国际化**: 所有文案支持国际化
- **Tooltip**: 默认显示提示
- **自定义**: 支持自定义颜色、尺寸、文案

#### StatusSwitch 特性
- 统一显示"启用/停用"文案（已统一术语，不再使用"禁用"）
- 支持 disabled 属性（用于管理员等不可操作场景）
- 自动切换状态值（0/1）

#### DeleteButton 特性
- 内置 Popconfirm 确认弹窗
- 支持自定义确认标题（`confirmTitle`）
- 危险操作红色主题

#### SyncButton 特性
- 支持 loading 状态
- loading 时图标旋转动画
- 禁用重复点击

### 5.4 使用规则

#### 基础用法

```tsx
// 编辑按钮
<EditButton onClick={() => handleEdit(record)} />

// 删除按钮（带确认弹窗）
<DeleteButton 
  onConfirm={() => handleDelete(id)}
  confirmTitle={intl.formatMessage({ 
    id: 'pages.common.deleteConfirm', 
    defaultMessage: '确定删除?' 
  })}
/>

// 启停开关
<StatusSwitch 
  status={record.status}
  onChange={(newStatus) => handleToggle(record.id, newStatus)}
  disabled={record.isAdmin === 1} // 管理员禁用
/>
```

#### 组合使用

```tsx
import { Space } from 'antd';

// 操作栏按钮组合
<Space size={8}>
  <StatusSwitch 
    status={record.status}
    onChange={(newStatus) => handleToggle(record.id, newStatus)}
  />
  <EditButton onClick={() => handleEdit(record)} />
  <DeleteButton 
    onConfirm={() => handleDelete(id)}
    confirmTitle="确定删除?"
  />
</Space>
```

#### 权限控制

```tsx
// 使用逻辑与
{hasOperationPermission(isAdmin, currentUser, record.creator) && (
  <Space size={8}>
    <EditButton onClick={() => handleEdit(record)} />
    <DeleteButton onConfirm={() => handleDelete(id)} />
  </Space>
)}

// 使用三元表达式
return canOperate ? (
  <Space size={8}>
    <StatusSwitch status={record.status} onChange={handleToggle} />
    <EditButton onClick={() => handleEdit(record)} />
    <DeleteButton onConfirm={() => handleDelete(id)} />
  </Space>
) : null;
```

#### 显示文字（可选）

```tsx
// 默认仅图标
<EditButton onClick={() => handleEdit(record)} />

// 显示图标+文字
<EditButton 
  onClick={() => handleEdit(record)}
  showText
/>

// 自定义文字
<EditButton 
  onClick={() => handleEdit(record)}
  showText
  text="编辑"
/>
```

### 5.5 已应用页面

所有管理页面的操作栏已统一使用独立按钮组件：

| 页面 | 文件 | 操作栏按钮 |
|------|------|-----------|
| ✅ 用户管理 | `user/management/index.tsx` | StatusSwitch + EditButton + DeleteButton |
| ✅ 租户管理 | `tenant/management/index.tsx` | StatusSwitch + EditButton + ManageUsersButton + DeleteButton |
| ✅ 模型管理 | `model/components/ModelListTable.tsx` | StatusSwitch + EditButton + DeleteButton |
| ✅ Channel管理 | `channel/index.tsx` | StatusSwitch + EditButton + DeleteButton |
| ✅ 任务管理 | `job/index.tsx` | StatusSwitch + RunButton + EditButton + DeleteButton |
| ✅ Agent管理 | `agent/index.tsx` | EditButton + DeleteButton |
| ✅ MCP管理 | `mcp/index.tsx` | TestButton + EditButton + DeleteButton |
| ✅ Skill仓库 | `skill/components/RepositoryList.tsx` | SyncButton + EditButton + DeleteButton |

### 5.6 表格列规范

#### 状态列处理
- ❌ **删除独立的状态列** - 不再使用单独的列显示状态标签
- ✅ **StatusSwitch移至操作栏** - 状态操作统一在操作栏中

#### 操作栏宽度
- 包含StatusSwitch: `width: 150-200px`
- 不包含StatusSwitch: `width: 100-150px`
- 固定右侧: `fixed: 'right'`

### 5.7 国际化术语

#### 统一术语
- ✅ **启用/停用** - StatusSwitch 文案（已统一，不再使用"禁用"）
- ✅ **编辑** - EditButton 文案
- ✅ **删除** - DeleteButton 文案
- ✅ **连接测试** - TestButton 文案
- ✅ **同步** - SyncButton 文案
- ✅ **立即执行** - RunButton 文案
- ✅ **管理用户** - ManageUsersButton 文案

#### 国际化 Key 规范
```typescript
// 通用文案
'pages.common.enabled': '启用',
'pages.common.disabled': '停用',  // 注意：统一使用"停用"而非"禁用"
'pages.common.edit': '编辑',
'pages.common.delete': '删除',

// 删除确认
'pages.user.management.deleteConfirm': '确定删除该用户吗？',
'pages.tenant.management.deleteConfirm': '确定删除该租户吗？',
```

### 5.8 禁止行为

- ❌ 禁止直接使用 `Button + Icon + Tooltip` 组合
- ❌ 禁止内联定义按钮样式
- ❌ 禁止在按钮上手动设置 `type="link"` 和 `padding: '4px'`
- ❌ 禁止在不同的页面使用不同的按钮样式规格
- ❌ 禁止使用旧的 CardActions 或 TableActions 组件
- ❌ 禁止在操作栏外单独显示状态列（已统一移至操作栏）
- ❌ 禁止使用"禁用"术语，统一使用"停用"

### 5.9 开发流程

#### 新增管理页面
1. 导入需要的按钮组件（EditButton, DeleteButton, StatusSwitch等）
2. 在操作栏中使用 `Space` 组件包裹按钮
3. 设置 `Space size={8}` 统一间距
4. 配置按钮的回调函数
5. 添加权限控制（如需要）
6. 测试功能和样式

#### 迁移旧页面
1. 识别现有的操作栏代码（Button + Icon + Tooltip）
2. 删除独立的状态列（如果有）
3. 替换为对应的独立按钮组件
4. 移除所有手动设置的样式
5. 使用 StatusSwitch 替换原有的启停按钮
6. 测试功能和样式

### 5.10 组件文档

详细使用说明和 API 文档请参考：
- 组件目录：`src/components/EditButton/`, `src/components/DeleteButton/` 等
- 使用指南：`src/components/独立按钮组件使用指南.md`

## 6. 表格与组件样式规范

### 6.1 表格样式优化

#### 基础配置
所有管理页面的ProTable必须使用以下配置：

```tsx
<ProTable
  size="small"                    // 紧凑尺寸
  className="user-management-table"  // 自定义类名
  style={{
    fontSize: '12px',              // 全局字体大小
  }}
/>
```

#### 单元格样式
**文件**: `src/global.less`

```less
/* 表格单元格 */
.ant-table-cell {
  font-size: 12px !important;         // 字体大小
  line-height: 1.5 !important;        // 行高
  padding: 8px 12px !important;       // 内边距
  text-align: center !important;      // 文字居中
}

/* 表头 */
.ant-table-thead > tr > th {
  font-size: 12px !important;
  font-weight: 600 !important;        // 加粗
  padding: 10px 12px !important;
  background-color: var(--vip-bg-layout) !important;
  border-bottom: 1px solid var(--vip-border) !important;
  text-align: center !important;
}

/* 表格行 */
.ant-table-tbody > tr > td {
  padding: 8px 12px !important;
  border-bottom: 1px solid var(--vip-border-light) !important;
}

/* 行悬停效果 */
.ant-table-tbody > tr:hover > td {
  background-color: var(--vip-primary-lighter) !important;
}
```

#### 操作栏对齐
操作栏必须左对齐,其他列居中:

```less
/* 操作栏左对齐（最后一列）*/
td.ant-table-cell:last-child,
th.ant-table-cell:last-child {
  text-align: left !important;
}
```

#### 标签组件
```less
.ant-tag {
  font-size: 11px !important;         // 更小的字体
  padding: 2px 8px !important;        // 紧凑内边距
  line-height: 18px !important;
}
```

#### 分页器
```less
.ant-pagination {
  font-size: 12px !important;
}

.ant-pagination-item,
.ant-pagination-prev,
.ant-pagination-next {
  font-size: 12px !important;
}
```

### 6.2 StatusSwitch 启停开关样式

#### 组件配置
**文件**: `src/components/StatusSwitch/index.tsx`

```tsx
<Switch
  checkedChildren="启用"              // 不能用"启"
  unCheckedChildren="停用"           // 统一术语,不能用"禁用"
  style={{
    backgroundColor: status === 1 ? activeColor : inactiveColor,
    fontSize: '10px',                 // 文字调小
    border: 'none',                   // 无边框
  }}
/>
```

#### 全局样式覆盖
**文件**: `src/global.less`

```less
/* StatusSwitch 全局优化 */
.ant-switch {
  border: none !important;            // 去掉外边框
  
  .ant-switch-inner {
    font-size: 10px !important;       // 开关文字调小
  }
}

.ant-switch-checked .ant-switch-inner,
.ant-switch .ant-switch-inner {
  font-size: 10px !important;
}
```

#### 术语规范
- ✅ **启用/停用** - 统一术语
- ❌ ~~启用/禁用~~ - 禁止使用
- ❌ ~~启/停~~ - 太简短

### 6.3 SearchFilterBar 筛选框样式

#### 组件字体
所有筛选组件统一使用 `12px` 字体:

```tsx
/* SearchInput */
<Input
  style={{
    height: '28px',
    fontSize: '12px',
  }}
/>

/* FilterSelect */
<Select
  style={{
    height: '28px',
    fontSize: '12px',
  }}
/>
```

#### 全局样式覆盖
**文件**: `src/global.less`

```less
/* 筛选下拉框选项 */
.ant-select-dropdown {
  font-size: 12px !important;
  
  .ant-select-item {
    font-size: 12px !important;
    padding: 4px 12px !important;     // 紧凑内边距
  }
  
  .ant-select-item-option-content {
    font-size: 12px !important;
  }
}

/* 筛选框输入框 */
.ant-select {
  font-size: 12px !important;
  
  .ant-select-selection-item,
  .ant-select-selection-placeholder {
    font-size: 12px !important;
  }
}
```

### 6.4 表格列优化规范

#### 所属租户列
只显示纯数字,不使用Tag或文字:

```tsx
{
  title: '所属租户',
  dataIndex: 'tenantCount',
  render: (_, record) => {
    const count = (record as any).tenantCount || 0;
    return count;                     // ✅ 只返回数字
  },
}
```

#### 列名规范
- ✅ **ID** - 简洁明了
- ❌ ~~用户 ID~~ - 冗余

### 6.5 CSS 变量规范

#### 新增边框变量
```less
:root {
  --vip-border: #e8eaf2;              // 主边框色
  --vip-border-secondary: #f0f2f8;   // 次要边框色
  --vip-border-light: #f5f6fa;       // 浅边框色(表格行分隔)
}
```

### 6.6 样式优先级

#### 覆盖策略
1. **组件内联样式** - 优先使用
2. **全局CSS覆盖** - 使用 `!important` 强制覆盖Ant Design默认样式
3. **CSS变量** - 统一主题色管理

#### 禁止行为
- ❌ 禁止在组件中硬编码颜色值
- ❌ 禁止使用内联样式定义字体大小(应在global.less统一管理)
- ❌ 禁止不同页面使用不同的表格样式规格
- ❌ 禁止修改操作栏按钮的字体大小(保持默认)

### 6.7 已应用页面

所有管理页面已统一应用上述样式规范:

| 页面 | 表格样式 | StatusSwitch | 筛选框 | 状态列 |
|------|---------|--------------|--------|--------|
| 用户管理 | ✅ | ✅ | ✅ | ❌ 已删除 |
| 租户管理 | ✅ | ✅ | ✅ | ❌ 已删除 |
| 任务管理 | ✅ | ✅ | ✅ | ❌ 已删除 |
| 模型管理 | ✅ | ✅ | ✅ | ❌ 已删除 |
| Channel管理 | ✅ | ✅ | ✅ | ❌ 已删除 |
| 智能体管理 | ✅ | ✅ | ✅ | ❌ 已删除 |
| 技能管理 | ✅ | ✅ | ✅ | ❌ 已删除 |
| MCP管理 | ✅ | ✅ | ✅ | ❌ 已删除 |

### 6.8 开发检查清单

新增或修改管理页面时,请确保:

- [ ] ProTable使用 `size="small"` 和 `fontSize: '12px'`
- [ ] 表格文字居中对齐(`text-align: center`)
- [ ] 操作栏左对齐(最后一列)
- [ ] 使用StatusSwitch组件(无边框,10px字体)
- [ ] 筛选框使用FilterSelect组件(12px字体)
- [ ] 删除独立的状态列
- [ ] 使用统一的CSS变量
- [ ] 测试深色模式下的显示效果

### 6.9 响应式卡片网格规范

#### 核心要求

当需要展示卡片列表时，必须使用 `ResponsiveCardGrid` 公共组件，该组件确保：

1. **卡片高度固定**：所有卡片高度一致
2. **宽高比 >= 指定比例**：确保卡片不变形
3. **动态计算每行数量**：根据容器宽度自动调整
4. **卡片均匀分布**：不会重叠，间距一致

#### 组件位置

```
src/components/ResponsiveCardGrid/index.tsx
```

#### 使用方式

```tsx
import ResponsiveCardGrid from '@/components/ResponsiveCardGrid';

<ResponsiveCardGrid
  data={items}
  cardHeight={260}
  minAspectRatio={1.4}
  gutter={[20, 20]}
  renderCard={(item, index) => (
    <Card>
      {/* 卡片内容 */}
    </Card>
  )}
/>
```

#### 参数说明

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| data | T[] | - | 数据列表 |
| cardHeight | number | 260 | 卡片固定高度（px） |
| minAspectRatio | number | 1.4 | 最小宽高比（宽度/高度） |
| gutter | [number, number] | [20, 20] | 网格间距 [水平, 垂直] |
| renderCard | Function | - | 渲染卡片的函数 |
| containerStyle | CSSProperties | - | 容器自定义样式 |
| loading | boolean | false | 加载状态 |
| emptyText | ReactNode | '暂无数据' | 空状态显示 |

#### 计算逻辑

```typescript
// 1. 根据最小宽高比和卡片高度计算最小宽度
const minCardWidth = cardHeight * minAspectRatio;
// 例如：260 * 1.4 = 364px

// 2. 计算可以显示的卡片数量
const cardsCount = Math.floor(
  (containerWidth + horizontalGutter) / (minCardWidth + horizontalGutter)
);

// 3. 每个卡片的实际宽度
const cardWidth = 100 / cardsPerRow; // 百分比
```

#### 实现原理

1. **容器宽度检测**：使用 `useRef` 获取容器引用
2. **动态计算**：监听窗口 resize 和容器大小变化（ResizeObserver）
3. **均匀分布**：每个 Col 使用百分比宽度 `100 / cardsPerRow %`
4. **高度固定**：卡片高度由 `cardHeight` 参数控制

#### 数学保证

- **最小宽度保证**：`actualWidth >= cardHeight * minAspectRatio`
- **宽高比保证**：`actualWidth / actualHeight >= minAspectRatio`
- **不重叠保证**：通过精确计算 `cardsPerRow` 确保

#### 示例场景

**场景 1：容器宽度 800px，cardHeight=260，minAspectRatio=1.4**
- minCardWidth = 260 * 1.4 = 364px
- cardsCount = (800 + 20) / (364 + 20) = 2.13 → 2
- 每张卡片宽度 = 800 / 2 = 400px
- 实际宽高比 = 400 / 260 = 1.54:1 > 1.4:1 ✅

**场景 2：容器宽度 1600px，cardHeight=260，minAspectRatio=1.4**
- minCardWidth = 364px
- cardsCount = (1600 + 20) / (364 + 20) = 4.21 → 4
- 每张卡片宽度 = 1600 / 4 = 400px
- 实际宽高比 = 400 / 260 = 1.54:1 > 1.4:1 ✅

#### 开发检查清单

新增卡片列表页面时，请确保：

- [ ] 使用 ResponsiveCardGrid 组件
- [ ] 设置合适的 cardHeight（推荐 260px）
- [ ] 设置合适的 minAspectRatio（推荐 1.4）
- [ ] 卡片内容在固定高度内完整显示
- [ ] 测试不同屏幕宽度下的显示效果
- [ ] 验证卡片不会重叠
- [ ] 验证宽高比始终 >= minAspectRatio
