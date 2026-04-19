# MyBatis XML 配置迁移说明

## 概述

已将 `SysUserMapper` 的 SQL 语句从注解方式迁移到 XML 配置文件方式，这是 MyBatis 推荐的最佳实践。

## 迁移内容

### 1. 创建 XML 配置文件

**文件路径**: `vipclaw-admin/src/main/resources/mapper/SysUserMapper.xml`

**包含内容**:
- ✅ ResultMap 映射配置
- ✅ 基础 CRUD 方法（selectById, insert, updateById, deleteById）
- ✅ 自定义查询方法（selectUserList, selectByUsername, selectActiveById）
- ✅ 更新操作（updateStatus, logicalDelete）

### 2. 简化 Mapper 接口

**文件路径**: `vipclaw-admin/src/main/kotlin/com/vipamp/vipclaw/admin/mapper/SysUserMapper.kt`

**变化**:
- ❌ 移除所有 SQL 注解（@Select, @Insert, @Update）
- ❌ 移除 @Options 注解
- ✅ 保留 @Mapper 和 @Param 注解
- ✅ 添加完整的 KDoc 注释

### 3. 更新配置文件

**文件路径**: `vipclaw-admin/src/main/resources/application.yml`

**新增配置**:
```yaml
mybatis:
  mapper-locations: classpath:mapper/*.xml  # XML 文件位置
  type-aliases-package: com.vipamp.vipclaw.admin.entity  # 实体类别名
  configuration:
    map-underscore-to-camel-case: true  # 下划线转驼峰
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl  # SQL 日志
```

**移除配置**:
```yaml
# 已移除 MyBatis-Plus 相关配置
mybatis-plus:
  global-config:
    db-config:
      id-type: auto
```

## XML 配置优势

### 1. **更好的可读性**
```xml
<!-- XML 方式 - 清晰的 SQL 结构 -->
<select id="selectUserList" resultMap="SysUserResultMap">
    SELECT * FROM sys_user
    WHERE active = 1
    <if test="keyword != null and keyword != ''">
        AND (username LIKE CONCAT('%', #{keyword}, '%') ...)
    </if>
</select>
```

vs

```kotlin
// 注解方式 - 需要 <script> 标签，容易出错
@Select("""
    <script>
    SELECT * FROM sys_user 
    WHERE active = 1
    <if test='keyword != null and keyword != ""'>
        AND (username LIKE CONCAT('%', #{keyword}, '%') ...)
    </if>
    </script>
""")
```

### 2. **更强大的动态 SQL 支持**
- 不需要 `<script>` 标签包裹
- 更清晰的 XML 语法
- 更好的 IDE 支持（XML 语法高亮、自动补全）

### 3. **ResultMap 复用**
```xml
<!-- 定义一次，多处复用 -->
<resultMap id="SysUserResultMap" type="com.vipamp.vipclaw.admin.entity.SysUser">
    <id column="id" property="id"/>
    <result column="username" property="username"/>
    <!-- ... -->
</resultMap>

<select id="selectById" resultMap="SysUserResultMap">
    <!-- 直接使用 -->
</select>
```

### 4. **更容易维护**
- SQL 与代码分离
- DBA 可以直接修改 SQL
- 版本控制更清晰

## 文件结构

```
vipclaw-admin/
├── src/main/
│   ├── kotlin/com/vipamp/vipclaw/admin/mapper/
│   │   └── SysUserMapper.kt          # Mapper 接口（仅保留方法签名）
│   └── resources/
│       ├── application.yml            # MyBatis 配置
│       └── mapper/
│           └── SysUserMapper.xml      # SQL 配置
└── src/test/
    └── kotlin/.../
        ├── mapper/
        │   └── SysUserMapperTest.kt   # 测试文件（无需修改）
        └── service/impl/
            └── SysUserServiceImplIntegrationTest.kt  # 集成测试（无需修改）
```

## 编译验证

✅ **编译成功**
```bash
cd /Users/heqingsong/code/my_project/vipclaw
mvn clean compile -pl vipclaw-admin -am
```

✅ **测试编译成功**
```bash
mvn clean test-compile -pl vipclaw-admin -am
```

## 运行测试

所有现有的测试无需修改，可以直接运行：

```bash
# 运行集成测试（需要 Docker）
mvn test -pl vipclaw-admin -Dtest=SysUserMapperIntegrationTest,SysUserServiceImplIntegrationTest

# 运行单元测试
mvn test -pl vipclaw-admin -Dtest=SysUserServiceImplTest,SysUserMapperUnitTest
```

## 后续建议

### 1. **其他 Mapper 也可以采用相同方式迁移**
- ModelMapper
- AgentMapper
- SessionMapper
- 等等...

### 2. **XML 文件组织建议**
```
resources/mapper/
├── SysUserMapper.xml
├── ModelMapper.xml
├── AgentMapper.xml
├── SessionMapper.xml
└── ...
```

### 3. **高级特性**
XML 配置还支持：
- `<sql>` 片段复用
- `<include>` 引用片段
- `<choose>/<when>/<otherwise>` 复杂条件
- `<foreach>` 循环
- `<bind>` 变量绑定

## 总结

这次迁移将 SQL 配置从代码注解分离到 XML 文件，带来了以下好处：

1. ✅ **更好的可读性** - SQL 语句更清晰
2. ✅ **更强的可维护性** - SQL 与代码分离
3. ✅ **更少的错误** - 不需要 `<script>` 标签
4. ✅ **更好的工具支持** - XML 编辑器、语法高亮
5. ✅ **团队协作** - DBA 可以直接修改 SQL
6. ✅ **向后兼容** - 所有测试无需修改

迁移完成！🎉
