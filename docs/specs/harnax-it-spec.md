# harnax-it 独立自动化测试模块设计规范（Spec）

> 版本：v1.0（设计稿）
> 状态：**已废弃** —— harnax-it 模块已解散，集成测试迁移至 `harnax-admin/src/test/kotlin/.../admin/it/`，通过 `mvn -pl harnax-admin -Pintegration-test verify` 运行。本文档仅作历史参考。
> 作者：架构组
> 关联项目：harnax（com.agnetix:harnax:1.0.0-SNAPSHOT）

---

## 目录

1. [目标与范围](#1-目标与范围)
2. [技术选型与理由](#2-技术选型与理由)
3. [被测服务启动策略](#3-被测服务启动策略)
4. [模块结构设计](#4-模块结构设计)
5. [测试用例规划](#5-测试用例规划)
6. [CI 集成](#6-ci-集成)
7. [实施计划](#7-实施计划)
8. [风险与开放问题](#8-风险与开放问题)

---

## 1. 目标与范围

### 1.1 背景

harnax 当前的自动化测试全部分散在各业务模块的 `src/test/kotlin` 中，由 Surefire 以 `*Test.kt` 命名约定执行，性质上以单元测试和"单组件 + Testcontainers"的窄集成测试为主（如 harnax-entity 的 `@MybatisTest` + MySQLContainer、harnax-session-router 的 `RedisIntegrationTestBase`）。目前**缺少跨服务、走真实 HTTP 协议、覆盖完整业务链路的集成/E2E 测试**，导致：

- 服务间契约（Admin ↔ Router ↔ Agent Service）的破坏只能在联调或线上暴露；
- SSE 流式链路（`Flux<ChatEvent>`）没有端到端验证；
- 鉴权链路（内部 JWT / X-Api-Key / 共享密钥）在真实网络路径上的行为无自动化保障；
- CI 的 test 阶段只跑了 harnax-admin 的单测，回归信心不足。

### 1.2 模块定位

新增独立 Maven 模块 **`harnax-it`**（Integration Test），挂在父 POM 之下，定位为：

- **黑盒优先的集成/E2E 自动化测试模块**：通过 HTTP/SSE 从外部访问被测服务，验证 API 契约、业务链路和跨服务协作；
- **不打包、不发布**：`skip install/deploy`，纯测试代码载体；
- **可切换测试目标**：同一套用例既能在本地/CI 用 Testcontainers 拉起完整环境，也能指向已部署的外部环境（联调环境、预发环境）做冒烟验证。

### 1.3 覆盖范围（In Scope）

| 维度 | 内容 |
|------|------|
| 服务 | harnax-admin(8080)、harnax-session-router(8081)、harnax-agent-service(8082)、harnax-channel-service(8083)、harnax-scheduler(8084) |
| 协议 | HTTP JSON（ResultVo 契约）、SSE（text/event-stream）、multipart 上传/下载 |
| 中间件 | MySQL 8（含 Flyway 迁移正确性）、Redis、MinIO |
| 鉴权 | Admin 登录态 JWT、内部 JWT（Bearer + X-Caller-Id）、X-Api-Key、共享密钥 |
| 数据 | 测试数据准备/清理、跨用例隔离 |

### 1.4 非目标（Out of Scope）

- **不替代现有单元测试**：各模块 `src/test/kotlin` 中的单测保持原样，继续由 Surefire 执行；
- **不覆盖前端**（harnax-webui / harnax-app / harnax-wechat-app）；
- **不做性能/压力测试**（可在后续引入 Gatling 时复用本模块的环境编排能力，但不在本期）；
- **不直接测试真实 LLM**：Agent 对模型的调用通过 Mock LLM（WireMock 模拟 OpenAI 兼容接口）或测试专用 Provider 完成，避免不确定性与费用；
- **不测试 Docker sandbox 内部行为**（agent-service 的沙箱执行细节由其模块内测试覆盖，本模块只验证对外可观测的结果）；
- **首期不覆盖企业版/公有云版差异逻辑**（默认按 personal profile 构建的产物测试，多版本差异测试列入开放问题）。

### 1.5 成功标准

- `mvn verify -Pintegration-test` 可在本地（有 Docker）与 GitLab CI（dind）一键执行；
- 默认 `mvn install`（不带 profile）**完全不受影响**（harnax-it 默认跳过，不拖慢日常构建）；
- P0 用例全部落地并在 CI 稳定通过（连续 10 次流水线无 flaky 失败）；
- 单次全量 IT 执行时间 ≤ 15 分钟（CI 环境）。

---

## 2. 技术选型与理由

### 2.1 总体原则

1. **与主工程技术栈同源**：Kotlin + JUnit 5 + Maven，降低团队学习与维护成本，复用 Spotless/ktlint 规范；
2. **优先复用项目内已有资产**：harnax-client（HTTP/SSE 客户端）、Testcontainers 1.21.4（已在 3 个模块使用）、reactor-test（已有）；
3. **黑盒测试不依赖被测服务的内部实现**，但允许直连 MySQL/Redis 做数据准备与断言（"灰盒"数据层）。

### 2.2 候选方案对比

#### 2.2.1 测试框架

| 候选 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| **JUnit 5 + kotlin.test**（推荐） | 与全项目现状一致；Failsafe/Surefire 原生支持；`@Nested`/`@Tag`/扩展模型成熟；CI JUnit 报告零成本 | 无 BDD 风格 DSL | ✅ **采用** |
| Kotest | Kotlin 原生 DSL、数据驱动能力强 | 引入第二套测试范式，团队需学习；与现有 mockito-kotlin 用法割裂 | ❌ 暂不引入 |
| Cucumber (BDD) | 业务可读性好 | 维护 feature 文件成本高，当前无非技术干系人阅读需求 | ❌ 不引入 |

#### 2.2.2 HTTP 客户端

| 候选 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| **复用 harnax-single-client + Spring WebClient 组合**（推荐） | ① `SingleHarnaxClient`（纯 JDK HttpClient）覆盖 Router 的 chat/chatStream/command/workspace 全部 API，且**测试它本身就是在测试对外交付的 SDK**（一石二鸟）；② WebClient 补齐 Admin/Scheduler 等 harnax-client 未覆盖的 API，并与 `StepVerifier` 天然配合做 SSE 断言 | 需同时维护两种客户端用法（用基础设施类封装屏蔽） | ✅ **采用** |
| REST Assured | API 测试 DSL 成熟、断言链友好 | Java 风格 DSL 在 Kotlin 下体验一般；不覆盖 SSE；再引入一套 HTTP 栈 | ❌ 不引入（P2 可再评估） |
| 纯 WebClient | 一套客户端走天下 | 放弃了对 harnax-client SDK 的顺带验证，Router API 的请求构造要重写一遍 | ❌ 次选 |
| okhttp | 稳定、SSE 有 okhttp-eventsource | 新增依赖树；JDK HttpClient + WebClient 已够用 | ❌ 不引入 |

> 决策要点：**Router 侧 API 一律通过 `harnax-single-client` 调用**（同时验证 SDK 契约），**Admin/Scheduler/Channel 侧 API 通过封装的 `WebClient` 调用**；SSE 断言统一走 `WebClient + StepVerifier`（详见 §5.4）。

#### 2.2.3 Testcontainers 策略

| 候选 | 说明 | 结论 |
|------|------|------|
| **Testcontainers 1.21.4 + 单例容器（推荐）** | 与项目现有版本一致；用"单例容器模式"（静态初始化 + Ryuk 回收）代替 `@Container` per-class，全套件只起一次 MySQL/Redis/MinIO/服务容器，大幅缩短执行时间 | ✅ **采用** |
| `@Container` per-class（现有 mapper 测试的用法） | 隔离最彻底 | 每个测试类重启容器，E2E 场景下不可接受（5 个服务 + 3 个中间件） | ❌ 不用于 IT |
| Docker Compose 模式（`ComposeContainer` 复用 docker-new/docker-compose.yml） | 直接复用生产编排 | compose 文件包含 frontend/mcp-server 等无关服务且面向生产参数，测试可控性差；容器就绪判定和端口映射不如程序化 API 灵活 | ❌ 备选（作为方案 A 的变体记录，见 §3） |

#### 2.2.4 断言库

| 候选 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| **kotlin.test + AssertJ（推荐）** | kotlin.test 项目已在用；AssertJ 对集合/JSON 解构后的复杂对象断言可读性极佳，错误信息友好 | 两套 API 并存（约定：简单断言用 kotlin.test，复杂对象/集合断言用 AssertJ） | ✅ **采用** |
| JSONAssert / json-path | 直接断言 JSON 文本 | IT 中优先反序列化为 DTO（复用 harnax-client-common 的 `ResultVo`/`ChatEvent`）再断言，比对 JSON 文本脆弱 | ⭕ 按需引入 json-path（少数无 DTO 的接口） |
| **reactor-test (StepVerifier)** | 项目已有；SSE Flux 断言的事实标准 | — | ✅ **采用**（SSE 专用） |
| **Awaitility** | 异步最终一致性断言（如 Scheduler 定时任务、Redis 会话路由生效） | — | ✅ **采用** |

#### 2.2.5 其他支撑组件

| 组件 | 用途 | 说明 |
|------|------|------|
| WireMock (standalone container) | Mock LLM Provider（OpenAI 兼容 `/v1/chat/completions`，含 SSE 流式响应）、Mock 外部渠道回调 | 以 Testcontainers `GenericContainer("wiremock/wiremock")` 方式加入测试网络 |
| Flyway（复用主工程迁移脚本） | 验证迁移脚本可从空库跑通；IT 的 schema 即生产 schema | 服务容器启动时自行执行 Flyway，无需 IT 模块干预 |
| kotlinx-coroutines-test | 调用 harnax-client 的 suspend API | 项目已有 |

---

## 3. 被测服务启动策略

### 3.1 三种方案对比

#### 方案 A：Testcontainers 拉起服务 Docker 镜像（黑盒 E2E）

先 `mvn package` 出各服务 JAR → 构建服务镜像（复用 docker-new/Dockerfile.*）→ 测试代码用 Testcontainers `GenericContainer` 在同一 `Network` 内拉起 MySQL/Redis/MinIO/WireMock + 5 个服务容器 → 测试通过映射端口访问。

| 维度 | 评价 |
|------|------|
| 保真度 | ★★★★★ 与生产部署形态（JRE 21 镜像、环境变量注入、容器网络）完全一致，能发现打包/配置/启动顺序类问题 |
| 隔离性 | ★★★★★ 每条流水线独立环境，可并行 |
| 反馈速度 | ★★★☆☆ 需先构建镜像（CI 中 3-5 分钟额外开销）；本地首次较慢，之后镜像层缓存可用 |
| 调试体验 | ★★★☆☆ 需看容器日志；Testcontainers 可流式转发日志到 slf4j 缓解 |
| 实现成本 | 中：需写环境编排类，但一次性投入 |

#### 方案 B：测试 JVM 内 `@SpringBootTest` 启动服务

harnax-it 依赖各服务模块（test scope），在同一 JVM 内以 `webEnvironment = RANDOM_PORT` 启动多个 Spring 上下文。

| 维度 | 评价 |
|------|------|
| 保真度 | ★★☆☆☆ 非生产形态；**5 个 Spring Boot 应用共存一个 JVM 存在 Bean/配置/classpath 冲突的高风险**（如各服务的 `application.yml`、自动配置、端口、MyBatis mapper 扫描互相污染）；WebFlux(Router) 与 MVC(Admin) 混布问题多 |
| 隔离性 | ★★☆☆☆ 上下文缓存易互相干扰 |
| 反馈速度 | ★★★★☆ 无镜像构建；可断点调试 |
| 调试体验 | ★★★★★ IDE 内直接调试 |
| 实现成本 | 高（工程上解决多应用共 JVM 的冲突成本大，且随服务演进持续付出） |

#### 方案 C：环境变量指向外部已部署环境

不启动任何容器，通过 `HARNAX_IT_*` 环境变量提供各服务 base URL 与凭证，直接对联调/预发环境跑用例。

| 维度 | 评价 |
|------|------|
| 保真度 | ★★★★★（就是真实环境） |
| 隔离性 | ★☆☆☆☆ 共享环境，数据污染与并发冲突风险高；破坏性用例不可跑 |
| 反馈速度 | ★★★★★ 零环境启动时间 |
| 调试体验 | ★★★★☆ |
| 实现成本 | 低，但需要用例按"是否环境安全"打标签 |

### 3.2 推荐结论

**主方案 A、辅方案 C，放弃方案 B。**

- **方案 A 为默认**（本地与 CI）：它是唯一能在"生产等价形态"下做可重复、可并行、可销毁验证的方案；Docker 镜像构建成本可通过 CI 镜像层缓存与"仅 IT job 构建镜像"控制。
- **方案 C 作为运行模式保留**：同一套用例通过环境切换指向已部署环境做部署后冒烟（deploy 阶段之后的 smoke job、或手工触发），只跑打了 `@Tag("smoke")` 且非破坏性的用例。
- **方案 B 不做**：多服务共 JVM 的冲突治理成本远高于收益；单服务内的白盒集成测试留在各自模块（现状即如此），不属于 harnax-it 的职责。

### 3.3 可切换设计

以**环境抽象接口 + 系统属性选择实现**实现切换，测试代码对环境来源无感知：

```kotlin
/** 测试环境抽象：用例只面向它，不关心服务从哪来 */
interface TestEnvironment : AutoCloseable {
    fun baseUrl(service: HarnaxService): String     // ADMIN / ROUTER / AGENT / CHANNEL / SCHEDULER
    fun jdbcUrl(): String                            // 数据准备/断言用（外部环境模式可禁用）
    fun redisUri(): String
    fun credentials(): TestCredentials               // admin 账号、api-key、internal shared-secret
    fun capabilities(): Set<Capability>              // DB_ACCESS / DESTRUCTIVE / MOCK_LLM
}

object TestEnvironmentHolder {
    val env: TestEnvironment by lazy {
        when (System.getProperty("harnax.it.env", "containers")) {
            "containers" -> ContainerizedEnvironment.start()   // 方案 A：单例容器编排
            "external"   -> ExternalEnvironment.fromEnvVars()  // 方案 C：读 HARNAX_IT_* 变量
            else -> error("unknown harnax.it.env")
        }
    }
}
```

- 切换开关：`-Dharnax.it.env=containers|external`（Maven profile 传入，见 §4.2）；
- 用例通过 JUnit `@Tag` + 自定义 `@EnabledIfCapability(DB_ACCESS)` 扩展声明能力需求，external 模式下自动跳过依赖 DB 直连或破坏性的用例；
- `ContainerizedEnvironment` 使用**单例容器模式**（首个用例触发启动，Ryuk 负责回收），全套件只编排一次。

---

## 4. 模块结构设计

### 4.1 模块挂载

- 父 POM `<modules>` 追加 `<module>harnax-it</module>`（放在最后，依赖 harnax-client / harnax-common / harnax-protocol 的已构建产物）；
- `harnax-it` 打包类型 `jar`，但配置 `maven.deploy.skip=true`、`maven.install.skip=true`（可选）与 `spring-boot` 插件不启用——它不是应用。

### 4.2 pom.xml 设计（关键片段示例）

```xml
<project>
    <parent>
        <groupId>com.agnetix</groupId>
        <artifactId>harnax</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>harnax-it</artifactId>
    <name>harnax-it</name>
    <description>Harnax Integration &amp; E2E Tests</description>

    <properties>
        <!-- 默认跳过 IT：日常 mvn install 不受影响 -->
        <skipITs>true</skipITs>
        <harnax.it.env>containers</harnax.it.env>
        <testcontainers.version>1.21.4</testcontainers.version>
    </properties>

    <dependencies>
        <!-- 复用项目内客户端与协议 DTO -->
        <dependency>
            <groupId>com.agnetix</groupId>
            <artifactId>harnax-single-client</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.agnetix</groupId>
            <artifactId>harnax-client-common</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>

        <!-- 测试框架与断言 -->
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-test-junit5</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- SSE 断言：WebClient + StepVerifier -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-webflux</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor.netty</groupId>
            <artifactId>reactor-netty-http</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.jetbrains.kotlinx</groupId>
            <artifactId>kotlinx-coroutines-test</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- 环境编排 -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mysql</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>

        <!-- 数据准备：直连 MySQL / Redis -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.lettuce</groupId>
            <artifactId>lettuce-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Surefire：本模块无单测，显式跳过，避免误跑 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration><skipTests>true</skipTests></configuration>
            </plugin>
            <!-- Failsafe：跑 *IT.kt，绑定 integration-test/verify -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <configuration>
                    <skipITs>${skipITs}</skipITs>
                    <includes><include>**/*IT.kt</include></includes>
                    <systemPropertyVariables>
                        <harnax.it.env>${harnax.it.env}</harnax.it.env>
                    </systemPropertyVariables>
                    <!-- 环境为单例容器，类间可并发；方法内串行 -->
                    <forkCount>1</forkCount>
                    <trimStackTrace>false</trimStackTrace>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <!-- 本地/CI 容器模式：mvn verify -Pintegration-test -->
        <profile>
            <id>integration-test</id>
            <properties><skipITs>false</skipITs></properties>
        </profile>
        <!-- 外部环境冒烟：mvn verify -Pit-external（配合 HARNAX_IT_* 环境变量） -->
        <profile>
            <id>it-external</id>
            <properties>
                <skipITs>false</skipITs>
                <harnax.it.env>external</harnax.it.env>
                <!-- 仅跑 smoke 标签 -->
                <it.groups>smoke</it.groups>
            </properties>
        </profile>
    </profiles>
</project>
```

要点说明：

- **Surefire/Failsafe 分离**：Failsafe 只认 `**/*IT.kt`，与全工程 Surefire 的 `*Test.kt` 约定互不冲突；Failsafe 的 `verify` goal 保证容器清理（`post-integration-test`）后才判定失败；
- **默认跳过**：`skipITs=true` 为默认值，父工程 `mvn clean install` 行为不变；只有显式 `-Pintegration-test` / `-Pit-external` 才执行；
- **标签过滤**：`it.groups` 映射到 Failsafe `<groups>`，支撑 smoke 子集；
- Spotless 继承父 POM 配置，IT 代码同样受 ktlint 约束（目录 `src/test/kotlin` 已在父配置的 include 内）。

### 4.3 目录 / 包结构

```
harnax-it/
├── pom.xml
├── README.md                          # 运行方式速查（本地 / CI / 外部环境）
└── src/test/
    ├── kotlin/com/agnetix/harnax/it/
    │   ├── infra/                     # 基础设施（不含任何用例）
    │   │   ├── TestEnvironment.kt         # 环境抽象接口 + Holder（见 §3.3）
    │   │   ├── ContainerizedEnvironment.kt# 方案 A：Network + MySQL/Redis/MinIO/WireMock + 5 服务容器编排
    │   │   ├── ExternalEnvironment.kt     # 方案 C：HARNAX_IT_* 环境变量解析
    │   │   ├── HarnaxImages.kt            # 服务镜像名/tag 解析（-Dharnax.it.imageTag）
    │   │   ├── BaseIntegrationTest.kt     # 所有 IT 基类：环境注入、通用 WebClient、日志
    │   │   ├── CapabilityExtension.kt     # @EnabledIfCapability JUnit 扩展
    │   │   └── SseSupport.kt              # WebClient SSE -> Flux<ChatEvent> 工具
    │   ├── auth/                      # 认证辅助
    │   │   ├── AdminAuthHelper.kt         # 登录拿 JWT（处理验证码：测试模式绕过/预置账号）
    │   │   ├── InternalTokenHelper.kt     # 用 shared-secret 签发内部 JWT + X-Caller-Id
    │   │   └── ApiKeyHelper.kt            # 创建/吊销测试 API Key
    │   ├── data/                      # 测试数据准备与清理
    │   │   ├── DbFixture.kt               # JDBC 直连；命名空间化数据（it_ 前缀）+ 用例后清理
    │   │   ├── RedisFixture.kt            # Redis 会话/路由数据操作与断言
    │   │   └── seed/                      # SQL 种子脚本（幂等）
    │   ├── mock/
    │   │   └── MockLlmStubs.kt            # WireMock：OpenAI 兼容 chat/completions（含 SSE 流）
    │   ├── admin/                     # 按服务分包的用例
    │   │   ├── AuthApiIT.kt
    │   │   ├── AgentApiIT.kt
    │   │   ├── ChannelApiIT.kt
    │   │   ├── SysUserApiIT.kt
    │   │   └── SessionApiIT.kt
    │   ├── router/
    │   │   ├── ChatIT.kt                  # 非流式 chat（走 harnax-single-client）
    │   │   ├── ChatStreamIT.kt            # SSE 流式（StepVerifier）
    │   │   ├── CommandIT.kt
    │   │   └── WorkspaceIT.kt
    │   ├── agent/
    │   │   └── AgentServiceRegistryIT.kt  # 实例注册/心跳/drain（经 Router）
    │   ├── channel/
    │   │   └── ChannelWebhookIT.kt
    │   ├── scheduler/
    │   │   └── SchedulerJobIT.kt
    │   └── e2e/                       # 跨服务全链路
    │       ├── ChatEndToEndIT.kt          # Admin 建 Agent -> Router chat -> 落库断言
    │       └── SmokeIT.kt                 # @Tag("smoke")：各服务 health + 关键只读接口
    └── resources/
        ├── junit-platform.properties      # 并发与超时全局配置
        ├── logback-test.xml
        └── wiremock/                      # LLM stub 映射 JSON
```

### 4.4 基础设施类设计

#### 4.4.1 BaseIntegrationTest

```kotlin
@Tag("it")
@ExtendWith(CapabilityExtension::class)
abstract class BaseIntegrationTest {
    protected val env get() = TestEnvironmentHolder.env

    /** Admin API 客户端（WebClient 封装，自动带登录 JWT） */
    protected val adminApi by lazy { AdminApiClient(env.baseUrl(ADMIN), AdminAuthHelper.token(env)) }

    /** Router 客户端：直接复用对外交付的 SDK，顺带验证 SDK 契约 */
    protected val routerClient by lazy {
        SingleHarnaxClient.builder()
            .baseUrl(env.baseUrl(ROUTER))
            .apiKey(env.credentials().apiKey)
            .streamTimeout(Duration.ofMinutes(2))
            .build()
    }

    protected val db by lazy { DbFixture(env) }     // capability 校验后才可用
    protected val redis by lazy { RedisFixture(env) }
}
```

#### 4.4.2 ContainerizedEnvironment（单例容器编排）

- 创建共享 `Network`；
- 启动顺序：MySQL(8.0) → Redis(7-alpine) → MinIO → WireMock(mock-llm) → admin → agent-service → router → channel-service → scheduler；
- 服务容器以 `GenericContainer(HarnaxImages.of(service))` 启动，环境变量对齐 `docker-new/docker-compose.yml`（DB/Redis/MinIO 指向网络别名，`JWT_SECRET`、`harnax.auth.internal.shared-secret` 注入固定测试值，LLM provider base-url 指向 WireMock）；
- 就绪判定：`Wait.forHttp("/api/health")`（各服务健康端点），Flyway 迁移完成体现在健康检查通过；
- 日志经 `Slf4jLogConsumer` 输出，CI 失败时可直接从 job log 定位；
- JVM shutdown / Ryuk 兜底回收，不依赖用例显式关闭。

#### 4.4.3 测试数据准备与清理策略

| 层次 | 策略 |
|------|------|
| 基线数据 | 依赖 Flyway 迁移自带的种子（如内置 admin 账号）；不足部分由 `seed/*.sql` 幂等补齐（仅容器模式在环境启动后执行一次） |
| 用例数据 | 一律通过 **API 创建优先**（黑盒原则）；API 无法构造的边界数据用 `DbFixture` 直插；所有数据命名带 `it_<runId>_` 前缀 |
| 清理 | `@AfterEach`/`@AfterAll` 按前缀清理自建数据；容器模式下套件级不强制清库（环境用完即弃）；external 模式**必须**清理且禁跑 DESTRUCTIVE 用例 |
| 隔离 | 用例间不共享可变数据；`runId` 取时间戳+随机数，支持同环境并发流水线 |

#### 4.4.4 认证辅助

- `AdminAuthHelper`：调用 `/api/admin/auth/captcha` + `/login` 获取 JWT。验证码问题的解法（按优先级）：① 若 admin 支持 `captcha.enabled=false` 类配置则容器模式直接关闭；② 否则由 IT 在启动 admin 容器时注入测试 profile 提供固定验证码；③ 兜底用 `DbFixture` 预置用户 + 直接走内部 token。**具体采用哪种在任务 T3 实施时确认**（开放问题 Q2）；
- `InternalTokenHelper`：读取注入容器的 `harnax.auth.internal.shared-secret` 测试值，本地用 jjwt 签发与 harnax-auth 兼容的内部 JWT，携带 `Authorization: Bearer` + `X-Caller-Id`，用于测试服务间接口（如 `InternalApiController`）与鉴权负向用例；
- `ApiKeyHelper`：通过 Admin 的 ApiKeyController 创建 Router 用 `X-Api-Key`，供 `SingleHarnaxClient` 使用。

---

## 5. 测试用例规划

优先级定义：**P0 = 首批必须落地（阻塞发布的核心链路）；P1 = 第二批（重要但非阻塞）**。

### 5.1 harnax-admin（8080）

| 编号 | 场景 | 优先级 | 要点 |
|------|------|--------|------|
| ADM-01 | 登录成功：captcha → login 返回 JWT，ResultVo.code 契约正确 | P0 | 断言 token 可用于后续请求 |
| ADM-02 | 登录失败：错误密码 / 错误验证码 / 不存在用户 | P0 | 断言错误 code/message，不泄露敏感信息 |
| ADM-03 | 未带 token 访问受保护接口 → 401/统一错误契约 | P0 | 鉴权负向 |
| ADM-04 | logout 后原 token 失效（token 黑名单生效） | P1 | 依赖 SysTokenBlacklist |
| ADM-05 | Agent CRUD：创建 → page 查询 → 详情 → 更新 → 删除 | P0 | `/api/admin/agents/*`；断言落库（DbFixture） |
| ADM-06 | Agent 关联会话查询 `/{agentId}/related-sessions` 与 refresh-sessions | P1 | |
| ADM-07 | Channel CRUD 及启停用 | P1 | |
| ADM-08 | SysUser 管理：建用户/改密/禁用后登录被拒 | P1 | |
| ADM-09 | 租户切换 switch-tenant：合法/越权租户 | P1 | 多租户边界 |
| ADM-10 | ApiKey 生命周期：创建 → 用其访问 Router 成功 → 吊销 → 再访问 401 | P0 | 跨服务鉴权链路 |
| ADM-11 | Skill / Model / ModelProvider 基础 CRUD 冒烟 | P1 | 只读+创建冒烟即可 |
| ADM-12 | `/v3/api-docs` 可访问且为合法 OpenAPI JSON | P1 | 契约可用性 |

### 5.2 harnax-session-router（8081）

| 编号 | 场景 | 优先级 | 要点 |
|------|------|--------|------|
| RTR-01 | 非流式 chat：`SingleHarnaxClient.chat()` 返回聚合响应（Mock LLM 固定回复） | P0 | 同时验证 SDK |
| RTR-02 | **SSE 流式 chat**：`/api/router/agent/chat/stream` 事件序列完整（见 §5.4） | P0 | StepVerifier |
| RTR-03 | SSE 中途断连：客户端 cancel 后服务端资源释放（会话可再次发起） | P1 | |
| RTR-04 | command：/clear /stop /compact 语义 | P1 | /clear 后 history 为空 |
| RTR-05 | confirm 流：工具确认 SSE 链路 | P1 | HITL 场景 |
| RTR-06 | 会话粘性路由：同 sessionId 两次请求路由到同一 agent 实例（Redis 断言） | P0 | RedisFixture |
| RTR-07 | 实例注册/心跳/unregister/drain：注册后可被路由，drain 后不再分配新会话 | P0 | InstanceRegistry |
| RTR-08 | 无效 X-Api-Key / 缺失内部 JWT 的负向鉴权 | P0 | |
| RTR-09 | workspace：upload → listFiles → readFile → download 全链路（MinIO 落存） | P1 | multipart |
| RTR-10 | getHistory / getPlans / getCurrentPlan 契约 | P1 | |

### 5.3 其他服务

| 编号 | 服务 | 场景 | 优先级 |
|------|------|------|--------|
| AGT-01 | agent-service | 启动后自动向 Router 注册并心跳（经 RTR-07 环境断言实例存在） | P0 |
| AGT-02 | agent-service | chat 请求经 Router 转发后由 agent-service 调 Mock LLM，ToolCallLog/ProcessLog 落库 | P1 |
| CHN-01 | channel-service | 健康检查 + 渠道 webhook 入站消息触发会话创建（WireMock 模拟渠道方） | P1 |
| SCH-01 | scheduler | 健康检查 + 一个代表性定时任务触发后的可观测结果（Awaitility 轮询 DB） | P1 |
| E2E-01 | 全链路 | Admin 创建 Agent+ApiKey → Router chatStream 完成对话 → history 可查 → Admin 会话列表可见 | P0 |
| E2E-02 | 全链路 | Flyway 从空库迁移成功（环境启动即隐式验证，失败时定位为迁移问题） | P0 |
| SMK-01 | 全部 | `@Tag("smoke")`：5 服务 `/api/health` + 关键只读接口，external 模式专用 | P0 |

### 5.4 SSE 流式接口测试方式（RTR-02 范式）

统一采用 **WebClient + StepVerifier**（不引入 okhttp-eventsource，理由：项目已有 reactor 栈，`Flux<ServerSentEvent>` 与 `ChatEvent` DTO 天然衔接；StepVerifier 提供带虚拟时间/超时的序列断言，是响应式流断言的事实标准）：

```kotlin
@Test
fun `chat stream should emit message deltas then complete event`() {
    val events: Flux<ChatEvent> = webClient.post()
        .uri("${env.baseUrl(ROUTER)}/api/router/agent/chat/stream")
        .header("X-Api-Key", env.credentials().apiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(chatRequest(sessionId, "hello"))
        .retrieve()
        .bodyToFlux(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
        .mapNotNull { it.data()?.let(json::toChatEvent) }

    StepVerifier.create(events)
        .expectNextMatches { it.type == "message_start" }
        .thenConsumeWhile { it.type == "content_delta" }   // 若干增量
        .expectNextMatches { it.type == "message_end" && it.content.contains(MOCK_LLM_REPLY) }
        .expectComplete()
        .verify(Duration.ofSeconds(60))
}
```

补充断言维度：事件顺序、增量拼接结果等于最终文本、`Content-Type: text/event-stream`、心跳/keep-alive 不破坏解析、超时行为。同场景另以 `routerClient.chatStream(request)`（Flow）+ `runTest` 收集断言一次，保证 **SDK 的 SSE 解析（SseEventParser/JdkSseStreamReader）与服务端行为一致**。

> 事件类型名以 harnax-protocol 的 `ChatEvent` 实际定义为准，上述 `message_start/content_delta/message_end` 仅为示意。

---

## 6. CI 集成

### 6.1 设计要点

- 新增 `it-test` job，放入现有 **test stage**（与 test-backend 并行，不新增 stage，避免拉长流水线关键路径）；
- 使用 **dind**（`docker:24-dind` service，与现有 docker-* job 一致），Maven 镜像内的 Testcontainers 通过 `DOCKER_HOST=tcp://docker:2375` 连接；
- **一条命令完成**：先全量 `install -DskipTests` 产出各模块 JAR → 脚本用 docker-new/Dockerfile.* 构建 5 个服务测试镜像（tag = `$CI_COMMIT_SHORT_SHA`）→ `mvn -pl harnax-it verify -Pintegration-test`；
- 复用现有 `.m2/repository` 缓存；JUnit 报告收集 failsafe-reports；
- 触发策略：MR 与 main 分支执行；提供 `IT_SKIP` 变量可临时跳过；`allow_failure: false`（IT 是准入门槛）；
- 另在 deploy 之后增加可选的 `smoke-external` 手动 job（方案 C），指向已部署环境跑 `@Tag("smoke")`。

### 6.2 YAML 片段示例

```yaml
# ==================== 集成测试（harnax-it）====================
it-test:
  stage: test
  image: maven:3.9-eclipse-temurin-21
  services:
    - name: docker:24-dind
      alias: docker
      command: ["--tls=false"]
  variables:
    DOCKER_HOST: tcp://docker:2375
    DOCKER_TLS_CERTDIR: ""
    TESTCONTAINERS_HOST_OVERRIDE: docker
    # Testcontainers 复用关闭（CI 环境一次性）
    TESTCONTAINERS_RYUK_DISABLED: "false"
  rules:
    - if: '$IT_SKIP == "true"'
      when: never
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
    - if: '$CI_COMMIT_BRANCH == "main"'
  before_script:
    - apt-get update -qq && apt-get install -y -qq docker.io  # docker cli，用于构建服务镜像
  script:
    # 1. 构建全部模块 JAR（不跑单测，单测由 test-backend 负责）
    - mvn -T 1C clean install -DskipTests -Dspotless.check.skip=true $MAVEN_OPTS
    # 2. 构建 5 个服务的测试镜像
    - ./harnax-it/scripts/build-test-images.sh $CI_COMMIT_SHORT_SHA
    # 3. 执行集成测试
    - mvn -pl harnax-it verify -Pintegration-test
        -Dharnax.it.imageTag=$CI_COMMIT_SHORT_SHA $MAVEN_OPTS
  artifacts:
    when: always
    reports:
      junit: harnax-it/target/failsafe-reports/TEST-*.xml
    paths:
      - harnax-it/target/failsafe-reports/
      - harnax-it/target/container-logs/       # 失败时的容器日志转储
    expire_in: 30 days
  cache:
    key: ${CI_COMMIT_REF_SLUG}
    paths:
      - .m2/repository/
    policy: pull
  tags:
    - docker

# ==================== 部署后冒烟（外部环境模式，手动）====================
smoke-external:
  stage: deploy
  image: maven:3.9-eclipse-temurin-21
  needs: ["deploy-personal"]
  when: manual
  script:
    - mvn -pl harnax-it verify -Pit-external $MAVEN_OPTS
  variables:
    HARNAX_IT_ADMIN_URL: "https://personal.harnax.example.com"
    HARNAX_IT_ROUTER_URL: "https://personal.harnax.example.com/router"
    # 凭证走 GitLab CI/CD protected variables：HARNAX_IT_API_KEY / HARNAX_IT_ADMIN_USER / HARNAX_IT_ADMIN_PASS
  artifacts:
    when: always
    reports:
      junit: harnax-it/target/failsafe-reports/TEST-*.xml
  tags:
    - docker
```

> 备注：若 GitLab Runner 为 shell/privileged docker executor，可改用挂载宿主 `/var/run/docker.sock` 的方式替代 dind，速度更快；两种模式 Testcontainers 均原生支持，spec 不锁死，由 T7 实施时按 Runner 实际情况选择。

---

## 7. 实施计划

每个任务独立可交付、可直接分派；除 T7 修改 `.gitlab-ci.yml`、T0 修改根 `pom.xml` 外，其余仅在 harnax-it 内新增文件。

### 阶段一：模块骨架与环境编排（约 3 人日）

**T0 模块骨架**
- 涉及文件：根 `pom.xml`（加 module）、`harnax-it/pom.xml`、`harnax-it/README.md`、`src/test/resources/junit-platform.properties`、`logback-test.xml`
- 验收：`mvn clean install` 全工程通过且 harnax-it 无测试执行；`mvn -pl harnax-it verify -Pintegration-test` 能进入 Failsafe（0 个用例通过）；Spotless 检查通过。

**T1 环境抽象与容器编排**
- 涉及文件：`infra/TestEnvironment.kt`、`ContainerizedEnvironment.kt`、`ExternalEnvironment.kt`、`HarnaxImages.kt`、`harnax-it/scripts/build-test-images.sh`
- 验收：本地执行 build-test-images.sh 后，一个临时 `EnvBootIT` 用例能拉起 MySQL/Redis/MinIO/WireMock + 5 服务并全部健康检查通过；`-Dharnax.it.env=external` 时不启动任何容器且能读取环境变量。

**T2 Mock LLM**
- 涉及文件：`mock/MockLlmStubs.kt`、`resources/wiremock/*.json`
- 验收：WireMock 容器提供 OpenAI 兼容非流式与 SSE 流式 stub；curl 验证两种响应格式正确；agent-service 容器以其为 provider 启动无报错。

### 阶段二：测试基础设施（约 3 人日）

**T3 认证辅助**
- 涉及文件：`auth/AdminAuthHelper.kt`、`InternalTokenHelper.kt`、`ApiKeyHelper.kt`
- 验收：容器模式下能自动完成 admin 登录获取 JWT（验证码方案落定并记录到 README）；能签发被 harnax-auth 接受的内部 JWT；能通过 API 创建可用的 X-Api-Key。

**T4 数据 Fixture 与基类**
- 涉及文件：`data/DbFixture.kt`、`RedisFixture.kt`、`data/seed/*.sql`、`infra/BaseIntegrationTest.kt`、`CapabilityExtension.kt`、`SseSupport.kt`
- 验收：示例用例可经 DbFixture 插入并清理带前缀数据；`@EnabledIfCapability(DB_ACCESS)` 在 external 模式下正确跳过；BaseIntegrationTest 提供的 adminApi/routerClient 可用。

### 阶段三：P0 用例（约 5 人日）

**T5a Admin P0 用例**：`admin/AuthApiIT.kt`、`AgentApiIT.kt`（ADM-01/02/03/05/10）
- 验收：用例通过；负向用例断言统一 ResultVo 错误契约；数据清理无残留。

**T5b Router + E2E P0 用例**：`router/ChatIT.kt`、`ChatStreamIT.kt`（RTR-01/02/06/07/08）、`e2e/ChatEndToEndIT.kt`、`SmokeIT.kt`（E2E-01、SMK-01）
- 验收：SSE 用例以 StepVerifier 断言完整事件序列且 60s 内稳定完成；同场景经 `SingleHarnaxClient.chatStream` 复验通过；smoke 用例打 `@Tag("smoke")` 且在 external 模式可独立运行。

### 阶段四：CI 与收尾（约 2 人日）

**T7 CI 集成**
- 涉及文件：`.gitlab-ci.yml`（新增 it-test、smoke-external）、`harnax-it/scripts/build-test-images.sh`（CI 适配）
- 验收：MR 流水线中 it-test 执行并展示 JUnit 报告；失败时 artifacts 含容器日志；连续 3 条流水线绿色。

**T8 P1 用例批次一**：ADM-04/06~09/11/12、RTR-03/04/05/09/10、AGT-02、CHN-01、SCH-01
- 验收：全部 P1 用例通过；全量 IT 在 CI ≤ 15 分钟，超时则先并发化用例类（junit-platform.properties 开 class 级并发）再评审。

**T9 稳定性验收**
- 验收：连续 10 条流水线无 flaky；输出《IT 运行手册》补充到 harnax-it/README.md（本地运行、调试容器日志、新增用例规范）。

---

## 8. 风险与开放问题

### 8.1 风险

| # | 风险 | 影响 | 缓解 |
|---|------|------|------|
| R1 | CI Runner 的 dind 资源不足（5 服务 + 4 中间件容器，内存峰值约 6-8GB） | it-test 频繁 OOM/超时 | 服务容器 JVM 限制 `-Xmx384m`；为 IT job 指定高配 Runner tag；必要时首期裁剪为 admin+router+agent 三服务最小环境，channel/scheduler 用例单独分组按需拉起 |
| R2 | SSE 用例受调度/网络抖动影响成为 flaky 主要来源 | 流水线信任度下降 | 超时给足余量（60s+）；断言"序列模式"而非精确增量条数；失败自动转储容器日志；引入 JUnit `@RetryingTest` 前先修根因（flaky 记录进看板） |
| R3 | 服务镜像构建拉长 CI 时间 | 反馈变慢 | Dockerfile 为"复制 JAR 单阶段"，构建本身秒级；主要耗时在 mvn install，与 test-backend 并行摊薄；后续可评估 build 阶段产物复用（needs+artifacts）省去重复编译 |
| R4 | 测试环境配置与 docker-new/docker-compose.yml 漂移 | IT 环境与生产编排脱节，测试失真 | `ContainerizedEnvironment` 中的环境变量集中在单一常量文件并注释对应 compose 行号；compose 变更纳入 code review checklist |
| R5 | Testcontainers 1.21.4 与 Spring Boot 4 生态版本演进 | 依赖冲突 | harnax-it 独立管理 testcontainers 版本（与现有模块一致）；升级时全工程统一 |
| R6 | 数据清理不彻底污染 external 环境 | 联调环境脏数据 | external 模式强制 `it_<runId>_` 前缀 + 仅 smoke（只读为主）；DESTRUCTIVE capability 硬性禁用 |

### 8.2 开放问题

| # | 问题 | 建议决策时点 |
|---|------|-------------|
| Q1 | **多版本（personal/enterprise/public）差异是否纳入 IT**：首期只测 personal 构建产物；enterprise/public 差异逻辑（如登录方式、租户能力）是否需要按 profile 各跑一遍 IT？ | T8 前评审 |
| Q2 | **验证码绕过机制**：admin 是否已有 `captcha.enabled` 类开关？若无，是加测试配置项（需改 harnax-admin，超出本模块边界，需单独审批）还是走 DbFixture 预置 token？ | T3 实施时 |
| Q3 | **agent-service 的 Docker sandbox**：容器内起服务时 sandbox 需要 Docker-in-Docker 权限，测试环境是否以"禁用 sandbox / local 执行模式"启动 agent-service？需确认其配置项支持 | T1 实施时 |
| Q4 | **harnax-springboot-client 是否也纳入验证**：当前仅用 single-client；SpringHarnaxClient 可在 P2 增加一个小型 SpringBootTest 形式的 SDK 兼容用例 | T8 后 |
| Q5 | **契约测试演进**：是否基于 `/v3/api-docs` 引入 OpenAPI 契约快照比对（openapi-diff），把 API 破坏性变更左移到 IT 之前 | 二期规划 |
| Q6 | **wechat-app / cli / tools-external 的链路**：微信登录（WechatLoginController）依赖外部平台，是否 WireMock 化纳入 P2 | 二期规划 |
| Q7 | **测试镜像仓库策略**：CI 中测试镜像仅本地构建不 push；若未来 IT 拆分多 job 并行，需要 push 到 `$CI_REGISTRY` 临时 tag 并设清理策略 | T7 实施时 |

---

## 附录 A：本地运行速查（目标形态）

```bash
# 1. 构建全部模块与服务测试镜像
mvn clean install -DskipTests
./harnax-it/scripts/build-test-images.sh local

# 2. 运行全量集成测试（需本机 Docker）
mvn -pl harnax-it verify -Pintegration-test -Dharnax.it.imageTag=local

# 3. 只跑某个用例类
mvn -pl harnax-it verify -Pintegration-test -Dit.test=ChatStreamIT

# 4. 指向外部环境跑冒烟
export HARNAX_IT_ADMIN_URL=http://localhost:8080
export HARNAX_IT_ROUTER_URL=http://localhost:8081
export HARNAX_IT_API_KEY=xxx HARNAX_IT_ADMIN_USER=admin HARNAX_IT_ADMIN_PASS=xxx
mvn -pl harnax-it verify -Pit-external
```

## 附录 B：命名与规范约定

- 用例类命名：`XxxIT.kt`（Failsafe 约定），禁止 `*Test.kt`（避免被 Surefire 约定混淆）；
- 用例方法：Kotlin 反引号可读命名，`@DisplayName` 中文描述，与现有 mapper 测试风格一致；
- 标签体系：`it`（全部）、`smoke`（外部环境安全）、`slow`（>30s）、`destructive`（禁止 external）；
- 所有 IT 产生的业务数据必须带 `it_<runId>_` 前缀。
