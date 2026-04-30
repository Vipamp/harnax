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
