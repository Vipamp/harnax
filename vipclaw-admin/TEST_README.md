# 用户管理模块单元测试说明

## 概述

本文档说明了用户管理模块（SysUser）的测试文件结构、测试覆盖范围以及如何运行测试。

**重要**: 本测试套件使用 **Testcontainers** 启动真实的 MySQL 容器进行测试，确保对数据库的实际操作进行验证。

## 测试文件结构

```
vipclaw-admin/src/test/kotlin/com/vipamp/vipclaw/admin/
├── service/impl/
│   ├── SysUserServiceImplTest.kt          # Service层单元测试（Mock方式）
│   └── SysUserServiceImplIntegrationTest.kt  # Service层集成测试（真实数据库）
└── mapper/
    └── SysUserMapperTest.kt               # Mapper层测试（集成测试+单元测试）
```

## 测试类型说明

### 1. 集成测试 (Integration Tests)
**使用 Testcontainers 启动真实 MySQL 容器**

- `SysUserMapperIntegrationTest` - Mapper层数据库集成测试
- `SysUserServiceImplIntegrationTest` - Service层数据库集成测试

特点：
- ✅ 测试真实的数据库操作
- ✅ 验证SQL语句正确性
- ✅ 测试事务管理
- ✅ 验证业务逻辑与数据库的交互

### 2. 单元测试 (Unit Tests)
**使用 Mock 框架模拟依赖**

- `SysUserServiceImplTest` - Service层Mock测试
- `SysUserMapperUnitTest` - Mapper接口结构验证

特点：
- ✅ 测试速度快
- ✅ 不依赖外部资源
- ✅ 隔离测试业务逻辑

## 测试覆盖范围

### 1. SysUserMapperIntegrationTest (Mapper集成测试)

**测试类**: `SysUserMapperIntegrationTest`

#### 基础 CRUD 测试 (BasicCrudTests)
- ✅ selectById - 查询存在的用户
- ✅ selectById - 查询不存在的用户
- ✅ insert - 插入新用户并验证自增ID
- ✅ updateById - 更新用户信息
- ✅ deleteById - 逻辑删除用户（设置active=0）

#### 自定义查询测试 (CustomQueryTests)
- ✅ selectUserList - 无条件查询所有活跃用户
- ✅ selectUserList - 关键字搜索（username/nickname/email/phone）
- ✅ selectUserList - 状态过滤
- ✅ selectUserList - 关键字和状态组合过滤
- ✅ selectByUsername - 根据用户名查询
- ✅ selectByUsername - 查询不存在的用户名
- ✅ selectByUsername - 不查询已删除用户（active=0）
- ✅ selectActiveById - 查询活跃用户
- ✅ selectActiveById - 不查询已删除用户
- ✅ updateStatus - 更新用户状态
- ✅ logicalDelete - 逻辑删除用户（设置active=0）
- ✅ logicalDelete - 重复删除应该失败

#### 边界和异常场景测试 (EdgeCaseTests)
- ✅ insert - 插入重复用户名应该失败（唯一约束）
- ✅ updateById - 更新不存在的用户返回0
- ✅ updateStatus - 更新已删除用户的状态应该失败

**测试总数**: 19个集成测试用例

---

### 2. SysUserServiceImplIntegrationTest (Service集成测试)

**测试类**: `SysUserServiceImplIntegrationTest`

#### 分页查询测试 (PaginationTests)
- ✅ getUserPage - 正常分页查询
- ✅ getUserPage - 关键字搜索
- ✅ getUserPage - 状态过滤
- ✅ getUserPage - 超出范围的页码

#### 查询用户详情测试 (GetUserByIdTests)
- ✅ getUserById - 查询存在的用户
- ✅ getUserById - 查询不存在的用户应该抛出异常
- ✅ getUserById - 查询已删除用户应该抛出异常

#### 创建用户测试 (CreateUserTests)
- ✅ createUser - 创建成功
- ✅ createUser - 用户名已存在应该抛出异常

#### 更新用户测试 (UpdateUserTests)
- ✅ updateUser - 更新部分字段
- ✅ updateUser - 更新用户名
- ✅ updateUser - 用户名已存在应该抛出异常
- ✅ updateUser - 用户不存在应该抛出异常

#### 切换用户状态测试 (ToggleUserStatusTests)
- ✅ toggleUserStatus - 禁用用户
- ✅ toggleUserStatus - 启用用户
- ✅ toggleUserStatus - 用户不存在应该抛出异常

#### 删除用户测试 (DeleteUserTests)
- ✅ deleteUser - 逻辑删除成功
- ✅ deleteUser - 删除不存在的用户应该抛出异常
- ✅ deleteUser - 删除已删除用户应该抛出异常

#### 根据用户名查询测试 (GetByUsernameTests)
- ✅ getByUsername - 查询存在的用户
- ✅ getByUsername - 查询不存在的用户返回null
- ✅ getByUsername - 不查询已删除用户

#### 完整业务流程测试 (BusinessFlowTests)
- ✅ 完整流程：创建 -> 查询 -> 更新 -> 禁用 -> 删除

**测试总数**: 22个集成测试用例

---

### 3. SysUserServiceImplTest (Service单元测试)

**测试类**: `SysUserServiceImplTest`

使用 Mock 方式测试 Service 层逻辑，覆盖 16 个测试用例。

### 4. SysUserMapperUnitTest (Mapper单元测试)

**测试类**: `SysUserMapperUnitTest`

验证 Mapper 接口结构和 SysUser 实体，覆盖 12 个测试用例。

测试方法覆盖了以下场景:

#### Mapper接口验证
- ✅ @Mapper注解存在性验证
- ✅ selectById方法存在性及返回类型验证
- ✅ insert方法存在性及返回类型验证
- ✅ updateById方法存在性及返回类型验证
- ✅ deleteById方法存在性及返回类型验证
- ✅ selectUserList方法存在性及返回类型验证
- ✅ selectByUsername方法存在性及返回类型验证
- ✅ selectActiveById方法存在性及返回类型验证
- ✅ updateStatus方法存在性及返回类型验证
- ✅ logicalDelete方法存在性及返回类型验证

#### 实体类验证
- ✅ SysUser实体默认值验证
- ✅ SysUser实体字段可变性验证

**测试总数**: 12个测试用例

## 测试依赖

测试使用了以下依赖库:

### 集成测试依赖
- **Testcontainers MySQL (1.19.3)**: 启动真实 MySQL 容器
- **Testcontainers JUnit Jupiter (1.19.3)**: Testcontainers JUnit 5 支持
- **Spring Boot Test**: Spring 集成测试支持

### 单元测试依赖
- **JUnit 5**: 测试框架
- **Mockito-Kotlin (5.4.0)**: Mock框架，用于模拟Mapper依赖
- **Kotlin Test**: Kotlin测试支持

依赖配置已添加到 `pom.xml`:

```xml
<!-- Testcontainers for integration tests -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <version>1.19.3</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>1.19.3</version>
    <scope>test</scope>
</dependency>

<!-- Mockito for unit tests -->
<dependency>
    <groupId>org.mockito.kotlin</groupId>
    <artifactId>mockito-kotlin</artifactId>
    <version>5.4.0</version>
    <scope>test</scope>
</dependency>
```

## 运行测试

### 前置条件

运行集成测试需要:
1. **Docker** 已安装并运行（Testcontainers需要）
2. 首次运行会自动下载 MySQL 8.0 镜像

### 运行所有测试

```bash
cd /Users/heqingsong/code/my_project/vipclaw
mvn clean test -pl vipclaw-admin
```

### 运行集成测试（真实数据库）

```bash
# 运行所有集成测试
mvn test -pl vipclaw-admin -Dtest=SysUserMapperIntegrationTest,SysUserServiceImplIntegrationTest

# 只运行 Mapper 集成测试
mvn test -pl vipclaw-admin -Dtest=SysUserMapperIntegrationTest

# 只运行 Service 集成测试
mvn test -pl vipclaw-admin -Dtest=SysUserServiceImplIntegrationTest
```

### 运行单元测试（Mock方式）

```bash
# 运行所有单元测试
mvn test -pl vipclaw-admin -Dtest=SysUserServiceImplTest,SysUserMapperUnitTest

# 只运行 Service 单元测试
mvn test -pl vipclaw-admin -Dtest=SysUserServiceImplTest

# 只运行 Mapper 单元测试
mvn test -pl vipclaw-admin -Dtest=SysUserMapperUnitTest
```

### 运行单个测试方法

```bash
mvn test -pl vipclaw-admin -Dtest=SysUserMapperIntegrationTest#selectById should return user when exists
```

## 测试技术说明

### Testcontainers 集成测试

**工作原理**:
```kotlin
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SysUserMapperIntegrationTest {
    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("vipclaw_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")  // 自动执行初始化SQL
            
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            // 动态注入数据库连接信息
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }
}
```

**优点**:
- ✅ 测试真实的数据库操作
- ✅ 验证SQL语句的正确性
- ✅ 测试数据库约束（唯一键、外键等）
- ✅ 验证事务管理
- ✅ 测试与数据库版本相关的行为
- ✅ 每次测试使用全新数据库，隔离性好

**测试流程**:
1. Testcontainers 启动 MySQL 8.0 容器
2. 自动执行 `schema-test.sql` 初始化表结构和测试数据
3. 动态注入数据库连接信息到 Spring 配置
4. 执行测试用例
5. 测试完成后自动销毁容器

### Mock 单元测试

**工作原理**:
```kotlin
@BeforeEach
fun setUp() {
    sysUserMapper = mock()  // 创建 Mock 对象
    sysUserService = SysUserServiceImpl(sysUserMapper)
}

@Test
fun `createUser should create user successfully`() {
    // 配置 Mock 行为
    whenever(sysUserMapper.selectByUsername("newuser")).thenReturn(null)
    whenever(sysUserMapper.insert(any())).thenReturn(1)
    
    // 执行测试
    val result = sysUserService.createUser(request)
    
    // 验证结果
    assertTrue(result)
    verify(sysUserMapper).insert(any<SysUser>())
}
```

**优点**:
- ✅ 测试速度快（不启动数据库）
- ✅ 不依赖外部资源
- ✅ 隔离测试 Service 层业务逻辑
- ✅ 可以模拟各种边界情况

## 测试覆盖率

### 代码覆盖

**集成测试** - 100% 方法覆盖:
- **SysUserMapper**: 所有 9 个方法都有集成测试 ✅
- **SysUserServiceImpl**: 所有 7 个方法都有集成测试 ✅

**单元测试** - 100% 方法覆盖:
- **SysUserServiceImpl**: 所有方法都有 Mock 测试 ✅
- **SysUserMapper**: 接口结构验证 ✅

### 场景覆盖

- ✅ 正常流程测试
- ✅ 异常流程测试
- ✅ 边界条件测试
- ✅ 业务规则验证

## 后续改进建议

1. **性能测试**: 添加大数据量下的分页查询性能测试
2. **并发测试**: 添加多线程环境下的并发操作测试
3. **覆盖率工具**: 集成 JaCoCo 代码覆盖率工具，生成覆盖率报告
4. **测试数据工厂**: 创建测试数据工厂类，统一管理测试数据
5. **CI/CD 集成**: 在 CI/CD 流程中自动运行测试

## 注意事项

1. **集成测试需要 Docker**: 运行集成测试前请确保 Docker 已安装并运行
2. **首次运行较慢**: 首次运行会自动下载 MySQL 8.0 镜像（约 500MB）
3. **测试隔离**: 每个测试类使用独立的数据库容器，测试之间相互独立
4. **测试命名**: 所有测试方法采用 BDD 风格（Given-When-Then）
5. **测试结构**: 遵循 AAA 模式（Arrange-Act-Assert）
6. **资源清理**: Testcontainers 会在测试完成后自动销毁容器

## 相关文件

- Service实现: `vipclaw-admin/src/main/kotlin/com/vipamp/vipclaw/admin/service/impl/SysUserServiceImpl.kt`
- Mapper接口: `vipclaw-admin/src/main/kotlin/com/vipamp/vipclaw/admin/mapper/SysUserMapper.kt`
- 实体类: `vipclaw-admin/src/main/kotlin/com/vipamp/vipclaw/admin/entity/SysUser.kt`
- DTO类: 
  - `SysUserCreateRequest.kt`
  - `SysUserUpdateRequest.kt`
  - `SysUserResponse.kt`

## 作者与日期

- 作者: vipamp
- 创建日期: 2026-04-19
- 最后更新: 2026-04-19
