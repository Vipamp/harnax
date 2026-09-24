package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.TeamResponse
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.TeamService
import com.agnetix.harnax.entity.Team
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

/**
 * TeamController 单元测试
 *
 * 团队域此前没有任何测试类，详情端点的 404 契约便无处可断；这里至少把「读到」和「读不到」两种回答钉住。
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamControllerTest {

    @Mock
    private lateinit var teamService: TeamService

    @Mock
    private lateinit var messageUtil: MessageUtil

    @InjectMocks
    private lateinit var controller: TeamController

    private lateinit var testTeam: Team
    private lateinit var testResponse: TeamResponse

    @BeforeEach
    fun setUp() {
        // MessageUtil 桩成回显消息码：断言只看键，不依赖 bundle 文案
        `when`(messageUtil.getMessage(anyString())).thenAnswer { invocation -> invocation.arguments[0] as String }
        testTeam = Team().apply {
            id = 1L
            name = "Research report team"
            tenantId = 1L
            status = 1
        }
        testResponse = TeamResponse(id = 1L, name = "Research report team")
    }

    @Test
    @DisplayName("getTeam - 读得到时返回详情")
    fun `getTeam should return team when found`() {
        `when`(teamService.getTeam(1L)).thenReturn(testTeam)
        `when`(teamService.convertToResponse(testTeam)).thenReturn(testResponse)

        val result = controller.getTeam(1L)

        assertTrue(result.isSuccess())
        assertNotNull(result.data)
        assertEquals("Research report team", result.data?.name)
    }

    @Test
    @DisplayName("getTeam - 读不到时返回具名 404")
    fun `getTeam should report not found with a named 404`() {
        `when`(teamService.getTeam(999L)).thenReturn(null)

        val result = controller.getTeam(999L)

        assertFalse(result.isSuccess(), "读不到的行不能算成功响应")
        assertEquals(404, result.code)
        assertEquals("error.team.notfound", result.message)
        assertNull(result.data)
    }
}
