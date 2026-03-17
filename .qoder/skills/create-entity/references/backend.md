## 后端服务开发文档

### 代码规范

- 所有类都需要加上类的注释，作者为 `vipamp`，日期为 `yyyy-MM-dd`，例如：

```java
/**
 * <类描述>
 *
 * @author vipamp
 * @since 2026-03-05
 */
```

- 整个项目禁止使用 `BeanUtils.copyProperties` 方法

### 1. 定义实体类

- 1.1 根据分析出来的 schema 信息，在 `entity` 目录下定义实体类，实体类包含如下字段

```text

- `id`：主键，自增，long 类型
- `status`：是否启用（0:禁用，1:启用），tinyint 类型
- `active`：是否可用（0:被删除，1:可用），tinyint 类型
- `create_time`：创建时间，datetime 类型
- `update_time`：更新时间，datetime 类型

```

- 1.2 创建的实体类有如下要点
    - 使用 `@Data` 注解生成 getter 和 setter 方法
    - 使用 `@EqualsAndHashCode(callSuper = false)` 注解生成 `equals` 和 `hashCode` 方法
    - 使用 `@TableName("table_name")` 注解指定数据库表名
    - 使用 `@Schema(description = "description")` 注解生成实体类描述，用于生成 doc 文档
    - 上述要求的5个字段：`id`、`status`、`active`、`create_time`、`update_time`，对应使用 `@TableId`、`@TableLogic`、
      `@TableField`
      注解进行配置，这是固定的写法。

- 1.3 示例：

```java
import java.io.Serial;
import java.io.Serializable;

/**
 * 用户实体类
 *
 * @author vipamp
 * @since 2026-03-05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("sys_user")
@Schema(description = "用户实体类")
public class SysUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "用户 ID")
    private Long id;

    /**
     * 用户名
     */
    @Schema(description = "用户名")
    private String username;

    /**
     * 密码
     */
    @Schema(description = "密码")
    private String password;

    /**
     * 昵称
     */
    @Schema(description = "昵称")
    private String nickname;

    /**
     * 邮箱
     */
    @Schema(description = "邮箱")
    private String email;

    /**
     * 手机号
     */
    @Schema(description = "手机号")
    private String phone;

    /**
     * 性别 (0:女 1:男 2:未知)
     */
    @Schema(description = "性别 (0:女 1:男 2:未知)")
    private Integer gender;

    /**
     * 头像 URL
     */
    @Schema(description = "头像 URL")
    private String avatar;

    /**
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）")
    private Integer status;

    /**
     * 是否可用（0:被删除，1:可用）
     */
    @Schema(description = "是否可用（0:被删除，1:可用）")
    @TableLogic(value = "1", delval = "0")
    private Integer active;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

### 2. 定义 dto 类

- 根据分析出来的 schema 信息，在 `dto` 目录下定义 dto 类，主要包含三个类
    - 实体的 CreateRequest 类
    - 实体的 UpdateRequest 类
    - 实体的 Response 类

#### 2.1 实体的 CreateRequest 类

- 要点
    - 不包含 id、active、create_time、update_time 字段，包含 status 字段
    - 类和字段需要加上 `@Schema` 注解
    - 必要时加上一定的参数校验逻辑和不能为空的限制

- 示例：

```java
/**
 * 用户创建请求 DTO
 *
 * @author vipamp
 * @since 2026-03-05
 */
@Data
@Schema(description = "用户创建请求对象")
public class SysUserCreateRequest {

    /**
     * 用户名
     */
    @Schema(description = "用户名", example = "zhangsan", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度必须在 3-50 之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    /**
     * 密码
     */
    @Schema(description = "密码", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度必须在 6-100 之间")
    private String password;

    /**
     * 昵称
     */
    @Schema(description = "昵称", example = "张三")
    private String nickname;

    /**
     * 邮箱
     */
    @Schema(description = "邮箱", example = "zhangsan@example.com")
    @Email(message = "邮箱格式不正确")
    private String email;

    /**
     * 手机号
     */
    @Schema(description = "手机号", example = "13800138000")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 性别 (0:女 1:男 2:未知)
     */
    @Schema(description = "性别 (0:女 1:男 2:未知)", example = "2")
    private Integer gender;

    /**
     * 头像 URL
     */
    @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
    private String avatar;

    /**
     * 状态 (0:禁用 1:正常)
     **/
    @Schema(description = "状态 (0:禁用 1:正常)", example = "1")
    private Integer status;
}
```

#### 2.2 实体的 UpdateRequest 类

- 要点
    - 不包含 active、create_time、update_time 字段，需要包含 id 和 status
    - 类和字段需要加上 `@Schema` 注解
    - 必要时加上一定的参数校验逻辑和不能为空的限制
- 示例

```java

@Data
@Schema(description = "用户更新请求对象")
public class SysUserUpdateRequest {

    /**
     * 用户 ID（更新时必须）
     */
    @Schema(description = "用户 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    /**
     * 用户名（更新时不可修改）
     */
    @Schema(description = "用户名", example = "zhangsan", accessMode = Schema.AccessMode.READ_ONLY)
    private String username;

    /**
     * 密码
     */
    @Schema(description = "密码", example = "123456")
    @Size(min = 6, max = 100, message = "密码长度必须在 6-100 之间")
    private String password;

    /**
     * 昵称
     */
    @Schema(description = "昵称", example = "张三")
    @Size(max = 50, message = "昵称长度不能超过 50 个字符")
    private String nickname;

    /**
     * 邮箱
     */
    @Schema(description = "邮箱", example = "zhangsan@example.com")
    @Email(message = "邮箱格式不正确")
    private String email;

    /**
     * 手机号
     */
    @Schema(description = "手机号", example = "13800138000")
    @Pattern(regexp = "^1[3-9]\\d{9}$|^$", message = "手机号格式不正确")
    private String phone;

    /**
     * 性别 (0:女 1:男 2:未知)
     */
    @Schema(description = "性别 (0:女 1:男 2:未知)", example = "2")
    private Integer gender;

    /**
     * 头像 URL
     */
    @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
    private String avatar;

    /**
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    private Integer status;
}
```

#### 2.3 实体的 Response 类

- 要点
    - 包含实体的全部字段，敏感字段不包含
    - 类和字段需要加上 `@Schema` 注解
    - 必要时加上一定的参数校验逻辑和不能为空的限制
    - 添加静态方法，将实体类转换为响应类，方法名为 `fromEntity`
- 示例

```java
/**
 * 用户响应 DTO
 *
 * @author vipamp
 * @since 2026-03-05
 */
@Data
@Schema(description = "用户响应对象")
public class SysUserResponse {

    /**
     * 用户 ID
     */
    @Schema(description = "用户 ID", example = "1")
    private Long id;

    /**
     * 用户名
     */
    @Schema(description = "用户名", example = "zhangsan")
    private String username;

    /**
     * 昵称
     */
    @Schema(description = "昵称", example = "张三")
    private String nickname;

    /**
     * 邮箱
     */
    @Schema(description = "邮箱", example = "zhangsan@example.com")
    private String email;

    /**
     * 手机号
     */
    @Schema(description = "手机号", example = "13800138000")
    private String phone;

    /**
     * 性别 (0:女 1:男 2:未知)
     */
    @Schema(description = "性别 (0:女 1:男 2:未知)", example = "2")
    private Integer gender;

    /**
     * 头像 URL
     */
    @Schema(description = "头像 URL", example = "https://example.com/avatar.jpg")
    private String avatar;

    /**
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    private Integer status;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2026-03-05 12:00:00")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2026-03-05 12:00:00")
    private LocalDateTime updateTime;


    /**
     * 从实体对象转换
     *
     * @param user 用户实体
     * @return 用户响应对象
     */
    public static SysUserResponse fromEntity(SysUser user) {
        if (user == null) {
            return null;
        }
        SysUserResponse response = new SysUserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setGender(user.getGender());
        response.setAvatar(user.getAvatar());
        response.setStatus(user.getStatus());
        response.setCreateTime(user.getCreateTime());
        response.setUpdateTime(user.getUpdateTime());
        return response;
    }
}
```

### 3. 创建 Mapper 类

在 `mapper` 目录下创建 Mapper 类，继承 `BaseMapper` 类，指定实体类

```java
/**
 * 用户 Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-06
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
```

### 4. 创建 Service 接口类

在 `service` 目录下创建实体的 Service 接口

- 分页查询接口：
    - 方法名：get实体Page
    - 参数：
        - keyword：String，模糊查询字段，nullable
        - status：Integer，状态筛选字段，nullable
        - current: Integer，当前页码
        - size: Integer，每页大小
    - 返回值：Page<实体>

- 获取单个实体详情接口：
    - 方法名：get实体ById
    - 参数：
        - id：Long，主键字段
    - 返回值：实体

- 创建实体
    - 方法名：create实体
    - 参数：
        - request：实体的CreateRequest类
    - 返回值：是否创建成功

- 更新实体
    - 方法名：update实体
    - 参数：
        - request：实体的UpdateRequest类
    - 返回值：是否更新成功

- 切换用户启用状态
    - 方法名：toggleUserStatus
    - 参数：
        - id：Long，主键字段
        - status：Integer，启用状态（0:禁用，1:启用）
    - 返回值：是否更新成功

- 删除实体
    - 方法名：delete实体
    - 参数：
        - id：Long，主键字段
    - 返回值：是否删除成功

- 示例

```java
/**
 * 用户服务接口
 *
 * @author vipamp
 * @since 2026-03-05
 */
public interface SysUserService extends IService<SysUser> {

    /**
     * 分页查询用户列表
     *
     * @param keyword 模糊查询字段
     * @param status  状态筛选字段
     * @param current 当前页码
     * @param size    每页大小
     * @return 分页结果
     */
    Page<SysUser> getUserPage(@Nullable String keyword, @Nullable Integer status, Integer current, Integer size);

    /**
     * 获取单个用户详情
     *
     * @param id 用户 ID
     * @return 用户实体
     */
    SysUser getUserById(Long id);

    /**
     * 创建用户
     *
     * @param request 用户创建请求对象
     * @return 创建结果
     */
    boolean createUser(SysUserCreateRequest request);

    /**
     * 更新用户
     *
     * @param id      用户 ID
     * @param request 用户更新请求对象
     * @return 更新结果
     */
    boolean updateUser(Long id, SysUserUpdateRequest request);

    /**
     * 切换用户启用状态
     *
     * @param id     用户 ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    boolean toggleUserStatus(Long id, Integer status);

    /**
     * 删除用户
     *
     * @param id 用户 ID
     * @return 删除结果
     */
    boolean deleteUser(Long id);

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户实体
     */
    SysUser getByUsername(String username);
}
```

### 5. 创建 Service 的实现逻辑

在 `service/impl` 目录下创建实体的 Service 实现类，实现 Service 接口，每个接口调用时先打印日志

#### 5.1 分页查询

- 如果 keyword 不为空，则进行模糊查询
- 如果 status 不为空，则进行状态筛选
- 必须加上 active 字段筛选，即只返回 active 为 true 的记录
- 将返回结果按照修改时间降序排列

#### 5.2 获取单个实体详情

- 根据 id 查询实体，如果该实体不存在则报错

#### 5.3 创建实体

- 如果有唯一约束字段，需要进行唯一性校验（只考虑 active=1 的记录）
- 判断有无 status，如果有值则按照传入的值，如果没有值，则设置为 1
- 设置 active 字段为 true
- 创建完成后，打印成功或者失败的消息到日志

#### 5.4 更新实体

- 先通过 id 判断该实体是否存在，如果不存在则报错
- 判断修改的值是否满足唯一性约束（只考虑 active=1 的记录）
- 选择性更新字段，只更新传入参数中的不是 null 的字段，如果值是空字符串，正常更新
- 更新完成后，打印成功或者失败的消息到日志

#### 5.5 切换实体启用状态

- 先通过 id 判断该实体是否存在，如果不存在则报错
- 更新 status 字段的值（只考虑 active=1 的记录）
- 注意，要用 `this.update(LambdaUpdateWrapper)` 方法，不要先 get 然后 update 的方式更新 `status`
- 更新完成后，打印成功或者失败的消息到日志

#### 5.6 删除实体

- 先通过 id 判断该实体是否存在，如果不存在则报错
- 更新 active 字段的值为 0（只考虑 active=1 的记录）
- 注意，要用 `this.update(LambdaUpdateWrapper)` 方法，不要先 get 然后 update 的方式更新 `active`，也不要用 remove 的方式物理删除
- 删除完成后，打印成功或者失败的消息到日志

#### 5.7 ServiceImpl 实现示例

```java

@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    @Override
    public Page<SysUser> getUserPage(@Nullable String keyword,
                                     @Nullable Integer status,
                                     Integer current,
                                     Integer size) {
        log.info("分页查询用户列表，current: {}, size: {}, keyword: {}, status: {}", current, size, keyword, status);

        Page<SysUser> page = new Page<>(current, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();

        // 模糊查询
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getNickname, keyword)
                    .or().like(SysUser::getEmail, keyword)
                    .or().like(SysUser::getPhone, keyword));
        }

        // 状态筛选
        if (status != null) {
            wrapper.eq(SysUser::getStatus, status);
        }

        wrapper.eq(SysUser::getActive, 1);
        wrapper.orderByDesc(SysUser::getUpdateTime);
        return this.page(page, wrapper);
    }

    @Override
    public SysUser getUserById(Long id) {
        log.info("查询用户详情，id: {}", id);
        SysUser user = this.getById(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        return user;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createUser(SysUserCreateRequest request) {
        log.info("创建用户，username: {}", request.getUsername());

        // 检查用户名是否存在
        SysUser existUser = getByUsername(request.getUsername());
        if (existUser != null) {
            throw new BizException("用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword());
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setGender(request.getGender() != null ? request.getGender() : 2);
        user.setStatus(request.getStatus() != null ? request.getStatus() : 1); // 默认启用
        user.setActive(1);  // 默认生效
        user.setAvatar(request.getAvatar());

        boolean success = this.save(user);
        log.info("用户创建{}，userId: {}", success ? "成功" : "失败", user.getId());
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateUser(Long id, SysUserUpdateRequest request) {
        log.info("更新用户，id: {}", id);

        SysUser user = this.getById(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }

        // 如果请求中包含用户名且与当前用户名不同，检查新用户名是否已被使用
        if (request.getUsername() != null && !request.getUsername().equals(user.getUsername())) {
            SysUser existUser = getByUsername(request.getUsername());
            if (existUser != null) {
                throw new BizException("用户名已存在");
            }
            user.setUsername(request.getUsername());
        }

        // 选择性更新字段
        if (StringUtils.hasText(request.getPassword())) {
            user.setPassword(request.getPassword());
        }
        if (request.getNickname() != null) {
            user.setNickname(request.getNickname());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getGender() != null) {
            user.setGender(request.getGender());
        }
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }

        boolean success = this.updateById(user);
        log.info("用户更新{}，id: {}", success ? "成功" : "失败", id);
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleUserStatus(Long id, Integer status) {
        log.info("切换用户状态，id: {}, status: {}", id, status);

        SysUser user = this.getById(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }

        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(SysUser::getStatus, status)
                .eq(SysUser::getId, id);
        return this.update(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteUser(Long id) {
        log.info("删除用户，id: {}", id);

        SysUser user = this.getById(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }

        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(SysUser::getActive, 0)
                .eq(SysUser::getId, id);
        return this.update(wrapper);
    }

    @Override
    public SysUser getByUsername(String username) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, username)
                .eq(SysUser::getActive, 1);
        wrapper.last("LIMIT 1");
        return getOne(wrapper);
    }
}
```

### 6. 创建 Controller

TODO

```java
/**
 * 用户管理控制器
 * @author vipclaw
 *
 */
@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "用户管理", description = "用户相关接口")
public class SysUserController {

    private final SysUserService sysUserService;

    @GetMapping("/page")
    @Operation(summary = "分页获取用户列表", description = "分页查询用户信息")
    public Result<Page<SysUserResponse>> getUserPage(
            @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @Parameter(description = "模糊查询字段") @RequestParam(required = false) String keyword,
            @Parameter(description = "状态筛选字段") @RequestParam(required = false) Integer status) {
        try {
            Page<SysUser> page = sysUserService.getUserPage(keyword, status, pageNum, pageSize);
            Page<SysUserResponse> responsePage = convertToResponsePage(page);
            return Result.success(responsePage);
        } catch (Exception e) {
            log.error("获取用户列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取用户详情", description = "根据用户 ID 获取用户信息")
    public Result<SysUserResponse> getUserById(
            @Parameter(description = "用户 ID") @PathVariable Long id) {
        try {
            SysUser user = sysUserService.getUserById(id);
            return Result.success(SysUserResponse.fromEntity(user));
        } catch (Exception e) {
            log.error("获取用户详情失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "创建用户", description = "新增用户信息")
    public Result<Void> createUser(
            @Valid @RequestBody SysUserCreateRequest request) {
        try {
            return sysUserService.createUser(request) ? Result.success() : Result.error("创建用户失败");
        } catch (Exception e) {
            log.error("创建用户失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/update/{userId}")
    @Operation(summary = "更新用户", description = "根据用户 ID 更新用户信息")
    public Result<Void> updateUser(
            @Parameter(description = "用户 ID") @PathVariable Long userId,
            @Valid @RequestBody SysUserUpdateRequest request) {
        try {
            request.setId(userId);
            return sysUserService.updateUser(userId, request) ? Result.success() : Result.error("更新用户失败");
        } catch (Exception e) {
            log.error("更新用户失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/toggle/{userId}")
    @Operation(summary = "更新用户", description = "根据用户 ID 更新用户信息")
    public Result<Void> toggleUser(
            @Parameter(description = "用户 ID") @PathVariable Long userId,
            @Parameter(description = "用户状态") @RequestParam Integer status) {
        try {
            return sysUserService.toggleUserStatus(userId, status) ? Result.success() : Result.error("更新用户失败");
        } catch (Exception e) {
            log.error("更新用户失败", e);
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "删除用户", description = "根据用户 ID 删除用户")
    public Result<Void> deleteUser(
            @Parameter(description = "用户 ID") @PathVariable Long userId) {
        try {
            return sysUserService.deleteUser(userId) ? Result.success() : Result.error("删除用户失败");
        } catch (Exception e) {
            log.error("删除用户失败", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 分页结果转换
     */
    private Page<SysUserResponse> convertToResponsePage(Page<SysUser> page) {
        Page<SysUserResponse> responsePage = new Page<>(page.getCurrent(), page.getSize());
        responsePage.setTotal(page.getTotal());
        responsePage.setSize(page.getSize());
        responsePage.setCurrent(page.getCurrent());
        responsePage.setPages(page.getPages());
        responsePage.setRecords(page.getRecords().stream()
                .map(SysUserResponse::fromEntity)
                .toList());
        return responsePage;
    }
}
```
