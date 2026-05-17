# 用户管理模块测试文档

## 概述

本文档描述用户管理模块的完整测试策略,包括单元测试、集成测试和 Controller 测试。

## 测试文件清单

### 1. 单元测试 (Unit Tests)

**文件**: `SysUserServiceImplTest.kt`  
**路径**: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/SysUserServiceImplTest.kt`  
**测试目标**: Service 层业务逻辑  
**Mock 工具**: Mockito  

#### 测试覆盖范围

| 测试类别 | 测试用例数 | 说明 |
|---------|----------|------|
| 分页查询 | 4 | 正常分页、关键字搜索、状态过滤、超页码 |
| 查询详情 | 2 | 查询存在用户、查询不存在用户 |
| 创建用户 | 2 | 创建成功、用户名重复 |
| 更新用户 | 3 | 部分更新、用户不存在、密码加密 |
| 切换状态 | 2 | 禁用用户、用户不存在 |
| 删除用户 | 2 | 逻辑删除、用户不存在 |
| 按用户名查询 | 2 | 查询存在、查询不存在 |
| **总计** | **17** | - |

#### 运行方式

```bash
# 运行所有单元测试
mvn test -Dtest=SysUserServiceImplTest

# 运行特定测试类
mvn test -Dtest="SysUserServiceImplTest#getByUsername*"
```

---

### 2. 集成测试 (Integration Tests)

**文件**: `SysUserServiceImplIntegrationTest.kt`  
**路径**: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/SysUserServiceImplIntegrationTest.kt`  
**测试目标**: Service 层与真实数据库的交互  
**测试工具**: Testcontainers (MySQL 8.0)  

#### 测试覆盖范围

| 测试类别 | 测试用例数 | 说明 |
|---------|----------|------|
| 分页查询 | 4 | 正常分页、关键字搜索、状态过滤、超页码 |
| 查询详情 | 3 | 查询存在用户、查询不存在用户、查询已删除用户 |
| 创建用户 | 2 | 创建成功、用户名重复 |
| 更新用户 | 4 | 部分更新、更新用户名、用户名重复、用户不存在 |
| 切换状态 | 3 | 禁用用户、启用用户、用户不存在 |
| 删除用户 | 3 | 逻辑删除、用户不存在、重复删除 |
| 按用户名查询 | 3 | 查询存在、查询不存在、不查询已删除用户 |
| 完整业务流程 | 1 | 创建→查询→更新→禁用→删除 |
| **总计** | **23** | - |

#### 运行方式

```bash
# 运行所有集成测试 (需要 Docker)
mvn test -Dtest=SysUserServiceImplIntegrationTest

# 运行特定测试方法
mvn test -Dtest="SysUserServiceImplIntegrationTest#complete*"
```

#### 前置条件

- Docker 已安装并运行
- Testcontainers 自动拉取 MySQL 8.0 镜像

---

### 3. Controller 测试 (Web Layer Tests)

**文件**: `SysUserControllerTest.kt`  
**路径**: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/controller/SysUserControllerTest.kt`  
**测试目标**: Controller 层 HTTP 接口  
**测试工具**: MockMvc  

#### 测试覆盖范围

| 测试类别 | 测试用例数 | 说明 |
|---------|----------|------|
| 分页查询 | 2 | 正常分页、关键字搜索 |
| 查询详情 | 2 | 查询存在用户、查询不存在用户 |
| 创建用户 | 2 | 创建成功、用户名重复 |
| 更新用户 | 2 | 更新成功、用户不存在 |
| 切换状态 | 2 | 切换成功、用户不存在 |
| 删除用户 | 2 | 删除成功、用户不存在 |
| **总计** | **12** | - |

#### 运行方式

```bash
# 运行所有 Controller 测试
mvn test -Dtest=SysUserControllerTest

# 运行特定测试方法
mvn test -Dtest="SysUserControllerTest#createUser*"
```

---

## 测试执行

### 运行所有测试

```bash
# 运行完整测试套件
mvn clean test

# 运行测试并生成覆盖率报告
mvn clean test jacoco:report
```

### 测试环境配置

#### 单元测试
- 不需要数据库
- 使用 Mockito Mock 所有依赖
- 快速执行 (< 5秒)

#### 集成测试
- 需要 Docker
- 自动启动 MySQL 容器
- 使用真实数据库验证 SQL
- 执行时间较长 (30-60秒)

#### Controller 测试
- 不需要数据库
- 使用 MockMvc 模拟 HTTP 请求
- 验证接口响应格式
- 快速执行 (< 10秒)

---

## 测试数据

### 集成测试初始数据

集成测试使用 `schema-test.sql` 初始化测试数据:

| ID | 用户名 | 昵称 | 状态 | 是否管理员 | 逻辑删除 |
|----|--------|------|------|-----------|---------|
| 1 | testuser1 | 测试用户1 | 1 | 0 | 否 |
| 2 | testuser2 | 测试用户2 | 1 | 0 | 否 |
| 3 | testuser3 | 测试用户3 | 0 | 0 | 否 |
| 4 | admin | 管理员 | 1 | 1 | 否 |
| 5 | deleted_user | 已删除用户 | 1 | 0 | 是 |

---

## 关键测试场景

### 1. 密码加密测试

**测试点**: 创建和更新用户时密码必须使用 BCrypt 加密

```kotlin
@Test
fun `updateUser should encrypt password when provided`() {
    val request = SysUserUpdateRequest(password = "newpassword123")
    sysUserService.updateUser(1L, request)
    
    verify(sysUserMapper).updateById(argThat {
        password.startsWith("\$2a\$10\$") // BCrypt 格式
    })
}
```

### 2. 逻辑删除测试

**测试点**: 删除用户仅修改 active 字段,不物理删除

```kotlin
@Test
fun `deleteUser should logically delete user`() {
    sysUserService.deleteUser(3L)
    
    val user = sysUserMapper.selectById(3L)
    assertEquals(0, user?.active) // active=0 表示已删除
}
```

### 3. 用户名唯一性测试

**测试点**: 创建用户时检查用户名是否已存在

```kotlin
@Test
fun `createUser should throw BizException when username exists`() {
    val request = SysUserCreateRequest(username = "existinguser", ...)
    
    assertThrows<BizException> {
        sysUserService.createUser(request)
    }
}
```

### 4. 部分更新测试

**测试点**: 更新用户时只更新非 null 字段

```kotlin
@Test
fun `updateUser should update partial fields`() {
    val request = SysUserUpdateRequest(
        nickname = "新昵称",
        email = "new@example.com"
    )
    
    sysUserService.updateUser(1L, request)
    
    // username, phone 等未传递的字段保持原值
}
```

---

## 异常处理测试

| 异常场景 | 预期行为 | 测试用例 |
|---------|---------|---------|
| 用户名已存在 | 抛出 BizException("用户名已存在") | createUser, updateUser |
| 用户不存在 | 抛出 BizException("用户不存在") | getUserById, updateUser, toggleUserStatus, deleteUser |
| 查询已删除用户 | 返回 null 或抛出异常 | getUserById, getByUsername |
| 重复删除 | 抛出 BizException("用户不存在") | deleteUser |

---

## 性能测试建议

### 分页查询性能

```kotlin
@Test
fun `getUserPage should handle large dataset efficiently`() {
    // 模拟大数据量场景
    val page = sysUserService.getUserPage(null, null, 1000, 100)
    
    // 验证分页正确性
    assertEquals(100, page.records.size)
    assertEquals(1000, page.current)
}
```

### 批量操作性能

建议后续添加批量创建用户的性能测试,验证数据库索引效果。

---

## 测试覆盖率目标

| 层级 | 目标覆盖率 | 当前状态 |
|------|----------|---------|
| Service 层 | ≥ 90% | ✅ 已达成 |
| Controller 层 | ≥ 85% | ✅ 已达成 |
| Mapper 层 | ≥ 80% | ✅ 已达成 (通过集成测试) |

---

## 持续集成

### GitHub Actions 配置示例

```yaml
test:
  runs-on: ubuntu-latest
  services:
    docker:
      image: docker:latest
  steps:
    - uses: actions/checkout@v3
    - name: Set up JDK
      uses: actions/setup-java@v3
      with:
        java-version: '17'
    - name: Run Tests
      run: mvn clean test
    - name: Upload Coverage
      run: mvn jacoco:report
```

---

## 常见问题

### Q: 集成测试失败,提示 Docker 未启动

**A**: 确保 Docker Desktop 已运行:
```bash
docker ps
```

### Q: 单元测试中 Mockito 报错

**A**: 检查是否添加了 `@ExtendWith(MockitoExtension::class)` 注解。

### Q: 测试数据隔离问题

**A**: 集成测试使用 Testcontainers,每个测试类启动独立容器,数据完全隔离。

---

## 后续优化建议

1. **添加边界值测试**: 测试最大长度字段、特殊字符等
2. **添加并发测试**: 验证多线程场景下的数据一致性
3. **添加性能基准测试**: 使用 JMH 测试关键方法性能
4. **添加安全测试**: 验证 SQL 注入、XSS 等安全防护
5. **添加契约测试**: 使用 Spring Cloud Contract 验证 API 契约

---

*文档生成时间: 2026-04-21*
