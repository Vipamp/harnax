package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest
import com.vipamp.vipclaw.admin.entity.SysUser
import com.vipamp.vipclaw.admin.entity.UserTenantEntity
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SysUserMapper
import com.vipamp.vipclaw.admin.mapper.UserTenantMapper
import com.vipamp.vipclaw.admin.context.TenantContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mindrot.jbcrypt.BCrypt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import java.time.LocalDateTime

/**
 * SysUserServiceImpl 单元测试
 * 使用 Mockito 模拟 Mapper 层依赖
 *
 * @author vipamp
 * @since 2026-04-22
 */
@ExtendWith(MockitoExtension::class)
class SysUserServiceImplTest {

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @Mock
    private lateinit var userTenantMapper: UserTenantMapper

    @InjectMocks
    private lateinit var sysUserService: SysUserServiceImpl

    private lateinit var testUser: SysUser

    @BeforeEach
    fun setUp() {
        testUser = SysUser().apply {
            id = 1L
            username = "testuser"
            password = BCrypt.hashpw("password123", BCrypt.gensalt())
            nickname = "测试用户"
            email = "test@example.com"
            phone = "13800138000"
            gender = 1
            avatar = "https://example.com/avatar.jpg"
            status = 1
            isAdmin = 0
            lastLoginTime = null  // 新用户未登录，应为 null
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
    }

    @Nested
    @DisplayName("分页查询测试")
    inner class GetUserPageTests {

        @Test
        @DisplayName("getUserPage - 正常分页查询")
        fun `getUserPage should return paginated results`() {
            // Given
            val user2 = SysUser().apply {
                id = 2L
                username = "user2"
                password = BCrypt.hashpw("password123", BCrypt.gensalt())
                nickname = "测试用户2"
                email = "user2@example.com"
                phone = "13800138001"
                gender = 1
                avatar = "https://example.com/avatar2.jpg"
                status = 1
                isAdmin = 0
                lastLoginTime = null  // 新用户未登录
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }
            val users = listOf(testUser, user2)
            `when`(sysUserMapper.selectUserList(null, null)).thenReturn(users)

            // When
            val page = sysUserService.page(null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertEquals(2, page.total)
            assertEquals(1, page.pageNum)
            assertEquals(10, page.pageSize)
            assertEquals(2, page.records.size)
            verify(sysUserMapper).selectUserList(null, null)
        }

        @Test
        @DisplayName("getUserPage - 关键字搜索")
        fun `getUserPage should filter by keyword`() {
            // Given
            val users = listOf(testUser)
            `when`(sysUserMapper.selectUserList("test", null)).thenReturn(users)

            // When
            val page = sysUserService.page("test", null, 1, 10)

            // Then
            assertEquals(1, page.total)
            assertEquals("testuser", page.records[0].username)
            verify(sysUserMapper).selectUserList("test", null)
        }

        @Test
        @DisplayName("getUserPage - 状态过滤")
        fun `getUserPage should filter by status`() {
            // Given
            val users = listOf(testUser)
            `when`(sysUserMapper.selectUserList(null, 1)).thenReturn(users)

            // When
            val page = sysUserService.page(null, 1, 1, 10)

            // Then
            assertEquals(1, page.total)
            assertEquals(1, page.records[0].status)
            verify(sysUserMapper).selectUserList(null, 1)
        }

        @Test
        @DisplayName("getUserPage - 超出范围的页码返回空列表")
        fun `getUserPage should return empty list when page out of range`() {
            // Given
            val users = listOf(testUser)
            `when`(sysUserMapper.selectUserList(null, null)).thenReturn(users)

            // When
            val page = sysUserService.page(null, null, 10, 10)

            // Then
            assertTrue(page.records.isEmpty())
        }
    }

    @Nested
    @DisplayName("查询用户详情测试")
    inner class GetUserByIdTests {

        @Test
        @DisplayName("getUserById - 查询存在的用户")
        fun `getUserById should return user when exists`() {
            // Given
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)

            // When
            val user = sysUserService.getSysUser(1L)

            // Then
            assertNotNull(user)
            assertEquals(1L, user?.id)
            assertEquals("testuser", user?.username)
            verify(sysUserMapper).selectById(1L)
        }

        @Test
        @DisplayName("getUserById - 查询不存在的用户应该返回 null")
        fun `getUserById should return null when user not found`() {
            // Given
            `when`(sysUserMapper.selectById(999L)).thenReturn(null)

            // When
            val result = sysUserService.getSysUser(999L)

            // Then
            assertNull(result)
            verify(sysUserMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("创建用户测试")
    inner class CreateUserTests {

        @Test
        @DisplayName("createUser - 创建成功")
        fun `createUser should create user successfully`() {
            // Given
            val request = SysUserCreateRequest(
                username = "newuser",
                password = "password123",
                nickname = "新用户",
                email = "new@example.com",
                phone = "13900139000",
                gender = 1,
                avatar = "https://example.com/new.jpg"
            )
            `when`(sysUserMapper.selectByUsername("newuser")).thenReturn(null)
            `when`(sysUserMapper.insert(any<SysUser>())).thenReturn(1)

            // When
            val result = sysUserService.createUser(request, isPersonal = false)

            // Then
            assertTrue(result)
            verify(sysUserMapper).selectByUsername("newuser")
            verify(sysUserMapper).insert(argThat { user ->
                user!!.username == "newuser" &&
                        user.status == 1 &&  // 默认启用
                        user.isAdmin == 0 &&  // 默认非管理员
                        user.active == 1 &&   // 默认生效
                        user.password.startsWith("\$2a\$10\$")  // 密码已加密
            })
        }

        @Test
        @DisplayName("createUser - 用户名已存在应该抛出异常")
        fun `createUser should throw BizException when username exists`() {
            // Given
            val request = SysUserCreateRequest(
                username = "existinguser",
                password = "password123",
                nickname = "重复用户",
                email = "dup@example.com",
                phone = "13900139001",
                gender = 1,
                avatar = ""
            )
            `when`(sysUserMapper.selectByUsername("existinguser")).thenReturn(testUser)

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.createUser(request, isPersonal = false)
            }
            assertEquals("用户名已存在", exception.message)
            // 使用 nullable 版本的 any
            verify(sysUserMapper, never()).insert(any<SysUser>())
        }

        @Test
        @DisplayName("createUser - 性别为 null 时使用默认值")
        fun `createUser should use default gender when null`() {
            // Given
            val request = SysUserCreateRequest(
                username = "nogender",
                password = "password123",
                nickname = "无性别用户",
                email = "nogender@example.com",
                phone = "13900139002",
                gender = null,
                avatar = ""
            )
            `when`(sysUserMapper.selectByUsername("nogender")).thenReturn(null)
            `when`(sysUserMapper.insert(any<SysUser>())).thenReturn(1)

            // When
            val result = sysUserService.createUser(request, isPersonal = false)

            // Then
            assertTrue(result)
            verify(sysUserMapper).insert(argThat { user ->
                user!!.gender == 2  // 默认性别为 2(未知)
            })
        }

        @Test
        @DisplayName("createUser - 新用户的 lastLoginTime 应为 null")
        fun `createUser should set lastLoginTime to null for new user`() {
            // Given
            val request = SysUserCreateRequest(
                username = "newuser2",
                password = "password123",
                nickname = "新用户2",
                email = "new2@example.com",
                phone = "13900139003",
                gender = 1,
                avatar = ""
            )
            `when`(sysUserMapper.selectByUsername("newuser2")).thenReturn(null)
            `when`(sysUserMapper.insert(any<SysUser>())).thenReturn(1)

            // When
            val result = sysUserService.createUser(request, isPersonal = false)

            // Then
            assertTrue(result)
            verify(sysUserMapper).insert(argThat { user ->
                user!!.lastLoginTime == null  // 新用户未登录，lastLoginTime 应为 null
            })
        }

        @Test
        @DisplayName("createUser - 手机号已存在应该抛出异常")
        fun `createUser should throw BizException when phone exists`() {
            // Given
            val request = SysUserCreateRequest(
                username = "newuser3",
                password = "password123",
                nickname = "新用户3",
                email = "new3@example.com",
                phone = "13800138000",  // 已存在的手机号
                gender = 1,
                avatar = ""
            )
            `when`(sysUserMapper.selectByUsername("newuser3")).thenReturn(null)
            `when`(sysUserMapper.selectByPhone("13800138000")).thenReturn(testUser)

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.createUser(request, isPersonal = false)
            }
            assertEquals("手机号已存在", exception.message)
            verify(sysUserMapper, never()).insert(any<SysUser>())
        }

        @Test
        @DisplayName("createUser - 邮箱已存在应该抛出异常")
        fun `createUser should throw BizException when email exists`() {
            // Given
            val request = SysUserCreateRequest(
                username = "newuser4",
                password = "password123",
                nickname = "新用户4",
                email = "test@example.com",  // 已存在的邮箱
                phone = "13900139004",
                gender = 1,
                avatar = ""
            )
            `when`(sysUserMapper.selectByUsername("newuser4")).thenReturn(null)
            `when`(sysUserMapper.selectByPhone("13900139004")).thenReturn(null)
            `when`(sysUserMapper.selectByEmail("test@example.com")).thenReturn(testUser)

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.createUser(request, isPersonal = false)
            }
            assertEquals("邮箱已存在", exception.message)
            verify(sysUserMapper, never()).insert(any<SysUser>())
        }

        @Test
        @DisplayName("createUser - 企业版邮箱为空应该抛出异常")
        fun `createUser should throw BizException when email is empty in enterprise mode`() {
            // Given
            val request = SysUserCreateRequest(
                username = "newuser5",
                password = "password123",
                nickname = "新用户5",
                email = null,
                phone = "13900139005",
                gender = 1,
                avatar = ""
            )

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.createUser(request, isPersonal = false)
            }
            assertEquals("邮箱不能为空", exception.message)
            verify(sysUserMapper, never()).insert(any<SysUser>())
        }

        @Test
        @DisplayName("createUser - 企业版手机号为空应该抛出异常")
        fun `createUser should throw BizException when phone is empty in enterprise mode`() {
            // Given
            val request = SysUserCreateRequest(
                username = "newuser6",
                password = "password123",
                nickname = "新用户6",
                email = "new6@example.com",
                phone = null,
                gender = 1,
                avatar = ""
            )

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.createUser(request, isPersonal = false)
            }
            assertEquals("手机号不能为空", exception.message)
            verify(sysUserMapper, never()).insert(any<SysUser>())
        }

        @Test
        @DisplayName("createUser - 企业版邮箱格式不正确应该抛出异常")
        fun `createUser should throw BizException when email format is invalid in enterprise mode`() {
            // Given
            val request = SysUserCreateRequest(
                username = "newuser7",
                password = "password123",
                nickname = "新用户7",
                email = "invalid-email",
                phone = "13900139007",
                gender = 1,
                avatar = ""
            )

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.createUser(request, isPersonal = false)
            }
            assertEquals("邮箱格式不正确", exception.message)
            verify(sysUserMapper, never()).insert(any<SysUser>())
        }

        @Test
        @DisplayName("createUser - 企业版手机号格式不正确应该抛出异常")
        fun `createUser should throw BizException when phone format is invalid in enterprise mode`() {
            // Given
            val request = SysUserCreateRequest(
                username = "newuser8",
                password = "password123",
                nickname = "新用户8",
                email = "new8@example.com",
                phone = "12345678901",  // 不合法的手机号
                gender = 1,
                avatar = ""
            )

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.createUser(request, isPersonal = false)
            }
            assertEquals("手机号格式不正确", exception.message)
            verify(sysUserMapper, never()).insert(any<SysUser>())
        }

        @Test
        @DisplayName("createUser - 个人版邮箱和手机号可为空")
        fun `createUser should allow empty email and phone in personal mode`() {
            // Given
            val request = SysUserCreateRequest(
                username = "newuser9",
                password = "password123",
                nickname = "新用户9",
                email = null,
                phone = null,
                gender = 1,
                avatar = ""
            )
            `when`(sysUserMapper.selectByUsername("newuser9")).thenReturn(null)
            `when`(sysUserMapper.insert(any<SysUser>())).thenReturn(1)

            // When
            val result = sysUserService.createUser(request, isPersonal = true)

            // Then
            assertTrue(result)
            verify(sysUserMapper).insert(argThat { user ->
                user!!.username == "newuser9" &&
                        user.email == null &&
                        user.phone == null
            })
        }
    }

    @Nested
    @DisplayName("更新用户测试")
    inner class UpdateUserTests {

        @Test
        @DisplayName("updateUser - 更新部分字段")
        fun `updateUser should update partial fields`() {
            // Given
            val request = SysUserUpdateRequest(
                nickname = "更新后的昵称",
                email = "updated@example.com"
            )
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(sysUserMapper.updateById(any<SysUser>())).thenReturn(1)

            // When
            val result = sysUserService.updateUser(1L, request)

            // Then
            assertTrue(result)
            verify(sysUserMapper).selectById(1L)
            verify(sysUserMapper).updateById(any<SysUser>())
        }

        @Test
        @DisplayName("updateUser - 用户不存在应该抛出异常")
        fun `updateUser should throw BizException when user not found`() {
            // Given
            val request = SysUserUpdateRequest(nickname = "新昵称")
            `when`(sysUserMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.updateUser(999L, request)
            }
            assertEquals("用户不存在", exception.message)
            verify(sysUserMapper, never()).updateById(any<SysUser>())
        }

        @Test
        @DisplayName("updateUser - 手机号已存在应该抛出异常")
        fun `updateUser should throw BizException when phone exists`() {
            // Given
            val request = SysUserUpdateRequest(phone = "13800138001")  // 其他用户的手机号
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(sysUserMapper.selectByPhone("13800138001")).thenReturn(SysUser().apply { id = 2L })

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.updateUser(1L, request)
            }
            assertEquals("手机号已存在", exception.message)
            verify(sysUserMapper, never()).updateById(any<SysUser>())
        }

        @Test
        @DisplayName("updateUser - 邮箱已存在应该抛出异常")
        fun `updateUser should throw BizException when email exists`() {
            // Given
            val request = SysUserUpdateRequest(email = "other@example.com")  // 其他用户的邮箱
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(sysUserMapper.selectByEmail("other@example.com")).thenReturn(SysUser().apply { id = 2L })

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.updateUser(1L, request)
            }
            assertEquals("邮箱已存在", exception.message)
            verify(sysUserMapper, never()).updateById(any<SysUser>())
        }

        @Test
        @DisplayName("updateUser - 设置为自己的手机号应该成功")
        fun `updateUser should succeed when setting own phone`() {
            // Given
            val request = SysUserUpdateRequest(phone = "13800138000")  // 用户自己的手机号
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(sysUserMapper.updateById(any<SysUser>())).thenReturn(1)

            // When
            val result = sysUserService.updateUser(1L, request)

            // Then
            assertTrue(result)
            // 不会调用 selectByPhone，因为是用户自己的手机号
            verify(sysUserMapper, never()).selectByPhone(anyString())
            verify(sysUserMapper).updateById(any<SysUser>())
        }

        @Test
        @DisplayName("updateUser - 空密码不更新")
        fun `updateUser should not update when password is empty`() {
            // Given
            val request = SysUserUpdateRequest(
                nickname = "新昵称"
            )
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(sysUserMapper.updateById(any<SysUser>())).thenReturn(1)

            // When
            val result = sysUserService.updateUser(1L, request)

            // Then
            assertTrue(result)
            verify(sysUserMapper).updateById(argThat { user ->
                user!!.password == testUser.password &&  // 密码不变
                        user.nickname == "新昵称"
            })
        }

        @Test
        @DisplayName("updateUser - 更新 isAdmin 字段")
        fun `updateUser should update isAdmin field`() {
            // Given
            val request = SysUserUpdateRequest(isAdmin = 1)
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(sysUserMapper.updateById(any<SysUser>())).thenReturn(1)

            // When
            val result = sysUserService.updateUser(1L, request)

            // Then
            assertTrue(result)
            verify(sysUserMapper).updateById(argThat { user ->
                user!!.isAdmin == 1
            })
        }

        @Test
        @DisplayName("updateUser - null 字段不应该更新")
        fun `updateUser should ignore null fields`() {
            // Given
            val originalEmail = testUser.email
            val request = SysUserUpdateRequest(
                nickname = "新昵称"
                // email 等其他字段为 null
            )
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(sysUserMapper.updateById(any<SysUser>())).thenReturn(1)

            // When
            val result = sysUserService.updateUser(1L, request)

            // Then
            assertTrue(result)
            verify(sysUserMapper).updateById(argThat { user ->
                user!!.nickname == "新昵称" &&
                        user.email == originalEmail  // email 保持不变
            })
        }
    }

    @Nested
    @DisplayName("切换用户状态测试")
    inner class ToggleUserStatusTests {

        @Test
        @DisplayName("toggleUserStatus - 禁用用户")
        fun `toggleUserStatus should disable user`() {
            // Given
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(sysUserMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = sysUserService.toggleUserStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(sysUserMapper).selectById(1L)
            verify(sysUserMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleUserStatus - 启用用户")
        fun `toggleUserStatus should enable user`() {
            // Given
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(sysUserMapper.updateStatus(1L, 1)).thenReturn(1)

            // When
            val result = sysUserService.toggleUserStatus(1L, 1)

            // Then
            assertTrue(result)
            verify(sysUserMapper).updateStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleUserStatus - 用户不存在应该抛出异常")
        fun `toggleUserStatus should throw BizException when user not found`() {
            // Given
            `when`(sysUserMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.toggleUserStatus(999L, 0)
            }
            assertEquals("用户不存在", exception.message)
        }
    }

    @Nested
    @DisplayName("删除用户测试")
    inner class DeleteUserTests {

        @Test
        @DisplayName("deleteUser - 逻辑删除成功")
        fun `deleteUser should logically delete user`() {
            // Given
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(sysUserMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = sysUserService.deleteUser(1L)

            // Then
            assertTrue(result)
            verify(sysUserMapper).selectById(1L)
            verify(sysUserMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteUser - 用户不存在应该抛出异常")
        fun `deleteUser should throw BizException when user not found`() {
            // Given
            `when`(sysUserMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.deleteUser(999L)
            }
            assertEquals("用户不存在", exception.message)
            verify(sysUserMapper, never()).deleteById(any<Long>())
        }

        @Test
        @DisplayName("deleteUser - 重复删除应该抛出异常")
        fun `deleteUser should throw BizException when user already deleted`() {
            // Given
            `when`(sysUserMapper.selectById(1L)).thenReturn(null)  // 已删除的用户查询不到

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.deleteUser(1L)
            }
            assertEquals("用户不存在", exception.message)
        }

        @Test
        @DisplayName("deleteUser - 删除租户普通成员应该从租户中移除")
        fun `deleteUser should remove member from tenant`() {
            // Given
            val currentTenantId = 1L
            TenantContext.setTenantId(currentTenantId)
            
            val userTenant = UserTenantEntity().apply {
                id = 1L
                userId = 1L
                tenantId = currentTenantId
                role = "member"
                status = 1
            }
            
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, currentTenantId)).thenReturn(userTenant)
            `when`(userTenantMapper.deleteByUserIdAndTenantId(1L, currentTenantId)).thenReturn(1)

            // When
            val result = sysUserService.deleteUser(1L)

            // Then
            assertTrue(result)
            verify(sysUserMapper).selectById(1L)
            verify(userTenantMapper).selectByUserIdAndTenantId(1L, currentTenantId)
            verify(userTenantMapper).deleteByUserIdAndTenantId(1L, currentTenantId)
            verify(sysUserMapper, never()).deleteById(any())
            
            // Clean up
            TenantContext.clear()
        }

        @Test
        @DisplayName("deleteUser - 删除租户管理员应该抛出异常")
        fun `deleteUser should throw BizException when deleting tenant admin`() {
            // Given
            val currentTenantId = 1L
            TenantContext.setTenantId(currentTenantId)
            
            val userTenant = UserTenantEntity().apply {
                id = 1L
                userId = 1L
                tenantId = currentTenantId
                role = "admin"
                status = 1
            }
            
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, currentTenantId)).thenReturn(userTenant)

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.deleteUser(1L)
            }
            assertEquals("该用户是当前租户的管理员，不允许删除", exception.message)
            verify(sysUserMapper).selectById(1L)
            verify(userTenantMapper).selectByUserIdAndTenantId(1L, currentTenantId)
            verify(userTenantMapper, never()).deleteByUserIdAndTenantId(any(), any())
            verify(sysUserMapper, never()).deleteById(any())
            
            // Clean up
            TenantContext.clear()
        }

        @Test
        @DisplayName("deleteUser - 删除全局管理员应该抛出异常")
        fun `deleteUser should throw BizException when deleting global admin`() {
            // Given
            val adminUser = SysUser().apply {
                id = 2L
                username = "admin"
                password = BCrypt.hashpw("password123", BCrypt.gensalt())
                nickname = "管理员"
                email = "admin@example.com"
                phone = "13800138000"
                gender = 1
                status = 1
                isAdmin = 1  // 全局管理员
                active = 1
            }
            `when`(sysUserMapper.selectById(2L)).thenReturn(adminUser)

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.deleteUser(2L)
            }
            assertEquals("不允许删除全局管理员用户", exception.message)
            verify(sysUserMapper).selectById(2L)
            verify(sysUserMapper, never()).deleteById(any())
        }
    }

    @Nested
    @DisplayName("根据用户名查询测试")
    inner class GetByUsernameTests {

        @Test
        @DisplayName("getByUsername - 查询存在的用户")
        fun `getByUsername should return user when exists`() {
            // Given
            `when`(sysUserMapper.selectByUsername("testuser")).thenReturn(testUser)

            // When
            val user = sysUserService.getByUsername("testuser")

            // Then
            assertNotNull(user)
            assertEquals("testuser", user?.username)
            verify(sysUserMapper).selectByUsername("testuser")
        }

        @Test
        @DisplayName("getByUsername - 查询不存在的用户返回null")
        fun `getByUsername should return null when user not exists`() {
            // Given
            `when`(sysUserMapper.selectByUsername("nonexistent")).thenReturn(null)

            // When
            val user = sysUserService.getByUsername("nonexistent")

            // Then
            assertNull(user)
        }
    }
}
