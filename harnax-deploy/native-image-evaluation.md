# GraalVM Native Image 可行性评估

> 评估日期: 2026-06-29
> 分支: kotlin-dev (d66ac75)

## 项目基础信息

| 项 | 值 |
|---|---|
| Java | 21 |
| Kotlin | 2.2.21 (session-router 用的 2.2.20) |
| Spring Boot | 4.0.1 (已内置 AOT 原生支持) |
| 构建工具 | Maven + kotlin-maven-plugin (spring all-open 插件) |

## 四个服务概览

| 服务 | 核心依赖 | 复杂度 |
|---|---|---|
| harnax-admin | Spring Web/Security/Quartz, MyBatis, Flyway, JGit, JWT, PageHelper | 高 |
| harnax-agent-service | Spring Web, AgentScope, MinIO SDK, Docker sandbox, JWT | 极高 |
| harnax-channel-service | Spring Web, MyBatis, Flyway, 飞书 SDK, protobuf, kotlin-reflect | 高 |
| harnax-session-router | Spring Web, MyBatis, Flyway, Redis/Lettuce, SQLite, Caffeine | 中 |

## 有利条件

- Spring Boot 4.0.1 自带 AOT 处理引擎，对 Spring 自身的 Bean 创建、自动配置有很好的 native 支持
- Kotlin spring 编译器插件已配置 kotlin-maven-allopen，解决 Kotlin class final 问题
- Jackson Kotlin Module 可被 Spring Boot AOT 处理
- MySQL Connector/J Oracle 已提供 native-image 支持

## 高风险依赖分析

| 依赖 | 影响服务 | 问题 | 风险等级 |
|---|---|---|---|
| AgentScope (io.agentscope) | agent-service, admin, protocol | AI Agent 框架，大量反射/动态代理/运行时类生成，几乎不可能有 native-image hint | 致命 |
| kotlin-reflect | channel (全局依赖) | 运行时元数据解析，GraalVM 无法静态分析所有反射目标 | 高 |
| JGit | admin | 复杂的 NIO/文件系统操作，大量反射，社区有失败案例 | 高 |
| MinIO SDK | agent-service (via harness-core) | OkHttp + 复杂序列化，无 native 支持 | 高 |
| 飞书 SDK (oapi-sdk) | channel-service | 第三方 SDK，无反射注册，可能用到 protobuf | 高 |
| Quartz | admin | 反射创建 Job 实例，需要手动注册 | 中高 |
| MyBatis + PageHelper | admin, channel, session-router | Mapper 动态代理，需手写 reflect-config.json / proxy-config.json | 中 |
| Flyway | admin, channel, session-router | 已知有 native 兼容方案，但需配置 resource hints | 中 |
| Lettuce/Netty | session-router | Spring Boot AOT 部分处理，但需额外 native config | 中 |
| protobuf-java | channel-service | 需要注册 generated message classes | 中 |
| jjwt | admin, agent-service | 加密算法需要 JCE hints | 低中 |
| SQLite JDBC | session-router | native JNI 库需要 runtime hints | 低中 |

## 已有的 native 尝试

harnax-admin 中已存在 META-INF/native-image/ 目录，包含:

- native-image.properties
- reflect-config.json
- resource-config.json

但 native-maven-plugin 已设置 skip=true，native profile 也已禁用，说明之前尝试过但遇到了问题，已主动放弃。

## 各服务可行性评级

| 服务 | 可行性 | 预估工作量 | 说明 |
|---|---|---|---|
| harnax-session-router | 可行 (70%) | 2-3 周 | 依赖最可控，主要攻克 MyBatis + Flyway + Lettuce + SQLite |
| harnax-admin | 困难 (30%) | 4-6 周 | JGit + Quartz + PageHelper 组合很棘手，需大量手写 hints |
| harnax-channel-service | 困难 (25%) | 4-6 周 | 飞书 SDK + protobuf + kotlin-reflect 是主要障碍 |
| harnax-agent-service | 极难 (10%) | 8+ 周或不可行 | AgentScope 框架是硬伤，MinIO SDK 也困难 |

## 建议方案

### 方案 A: 渐进式推进（推荐）

1. 先从 session-router 开始试点，它最轻量且依赖最可控
2. 积累经验后推 admin（考虑去掉 JGit/Quartz 的 native 兼容，改为外部化）
3. channel-service 和 agent-service 暂时保持 JVM 镜像

### 方案 B: 混合部署

- Native: session-router（启动快、内存小，收益最大）
- JVM: 其余三个服务（用现有 Dockerfile + 精简 JRE 基础镜像如 eclipse-temurin:21-jre-alpine）

### 方案 C: 全部 JVM，优化基础镜像

- 放弃 native image，改用 JRE 瘦身方案（jlink 自定义运行时、CDS 类数据共享）
- 启动时间和内存虽不如 native，但兼容性好、维护成本低

## 关键结论

AgentScope 框架是最大障碍。它作为 AI Agent 运行时，内部大量使用反射和动态特性，基本不具备 GraalVM 兼容的可能性。除非 AgentScope 官方提供 native-image 支持，否则 agent-service 无法打包为 native 镜像。

建议先从 session-router 试点，同时用 jlink + CDS 优化其余服务的 JVM 镜像作为折中方案。
