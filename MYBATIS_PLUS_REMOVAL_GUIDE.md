# MyBatis-Plus 完全移除改造指南

## 📋 改造清单

### 已完成 ✅
1. ✅ Agent 模块 (Mapper + Entity + ServiceImpl)
2. ✅ PageHelper 依赖添加
3. ✅ 自定义 Page 类创建

### 待改造 📝
- 15个 Mapper
- 10个 ServiceImpl  
- 16个 Entity

---

## 🔧 改造模板

### 1. Mapper 改造模板

**改造前:**
```kotlin
@Mapper
interface XxxMapper : BaseMapper<Xxx> {
    // 自定义方法
}
```

**改造后:**
```kotlin
@Mapper
interface XxxMapper {

    // ========== 基础 CRUD ==========
    @Select("SELECT * FROM xxx WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): Xxx?

    @Insert("INSERT INTO xxx (field1, field2) VALUES (#{field1}, #{field2})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(xxx: Xxx): Int

    @Update("UPDATE xxx SET field1 = #{field1}, field2 = #{field2} WHERE id = #{id}")
    fun updateById(xxx: Xxx): Int

    @Update("UPDATE xxx SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ========== 自定义方法 ==========
}
```

### 2. Entity 改造模板

**改造前:**
```kotlin
@TableName("xxx")
class Xxx {
    @TableId(value = "id", type = IdType.AUTO)
    var id: Long = 0
    
    @TableField(fill = FieldFill.INSERT)
    var createTime: LocalDateTime = LocalDateTime.now()
}
```

**改造后:**
```kotlin
class Xxx {
    var id: Long = 0
    var createTime: LocalDateTime = LocalDateTime.now()
}
```

### 3. ServiceImpl 改造模板

**改造前:**
```kotlin
@Service
class XxxServiceImpl : ServiceImpl<XxxMapper, Xxx>(), XxxService {
    override fun getPage(...): Page<Xxx> {
        val page = Page<Xxx>(current, size)
        return this.page(page, wrapper)
    }
}
```

**改造后:**
```kotlin
@Service
class XxxServiceImpl(
    private val xxxMapper: XxxMapper
) : XxxService {

    fun save(xxx: Xxx): Boolean {
        xxx.createTime = LocalDateTime.now()
        xxx.updateTime = LocalDateTime.now()
        return xxxMapper.insert(xxx) > 0
    }

    fun updateById(xxx: Xxx): Boolean {
        xxx.updateTime = LocalDateTime.now()
        return xxxMapper.updateById(xxx) > 0
    }

    fun removeById(id: Long): Boolean {
        return xxxMapper.deleteById(id) > 0
    }

    override fun getPage(...): Page<Xxx> {
        PageHelper.startPage<Xxx>(current, size)
        val list = xxxMapper.selectXxxList(...)
        val pageInfo = PageInfo(list)
        return Page.fromPageInfo(pageInfo)
    }
}
```

### 4. PageHelper 分页使用

```kotlin
import com.github.pagehelper.PageHelper
import com.github.pagehelper.PageInfo
import com.vipamp.vipclaw.common.page.Page

// 分页查询
PageHelper.startPage<T>(current, size)
val list = mapper.selectList(...)
val pageInfo = PageInfo(list)
return Page.fromPageInfo(pageInfo)
```

---

## 📊 各模块字段参考

### Agent 表
```sql
INSERT INTO agent (
    name, description, system_prompt, model_id, mcp_list, skill_list,
    owner, status, is_public, creator, active, create_time, update_time
) VALUES (...)
```

### 需要手动设置的字段
- `createTime` - 插入时设置
- `updateTime` - 更新时设置
- `active` - 默认 1

---

## ⚠️ 注意事项

1. **所有 Mapper 不再继承 BaseMapper**
2. **所有 Entity 移除 MyBatis-Plus 注解**
3. **所有 ServiceImpl 移除 ServiceImpl 继承**
4. **使用 PageHelper 替代手动分页**
5. **手动设置 createTime 和 updateTime**
6. **IDE 显示的编译错误是误报,实际编译没问题**

---

## 🎯 批量改造顺序建议

**优先级1 (核心模块):**
1. Channel
2. Session  
3. McpServer
4. Model
5. ModelProvider

**优先级2 (业务模块):**
6. Skill
7. SkillRepository
8. SysJob
9. SysJobLog
10. SysUser

**优先级3 (其他):**
11-16. 其他模块

---

## 📦 依赖配置

### vipclaw-admin/pom.xml
```xml
<!-- PageHelper -->
<dependency>
    <groupId>com.github.pagehelper</groupId>
    <artifactId>pagehelper-spring-boot-starter</artifactId>
    <version>2.1.0</version>
</dependency>
```

### vipclaw-common/pom.xml  
```xml
<!-- PageHelper -->
<dependency>
    <groupId>com.github.pagehelper</groupId>
    <artifactId>pagehelper</artifactId>
    <version>6.1.0</version>
</dependency>
```
