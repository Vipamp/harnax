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
 * Unit tests for TeamController
 *
 * The team domain had no test class at all, so the detail endpoint's 404 contract had nowhere to be asserted;
 * this at least pins both answers: the row can be read, and it cannot.
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
        // Stub MessageUtil to echo the message code: assertions check the key only, not bundle text
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
    @DisplayName("getTeam - returns the details when the row can be read")
    fun `getTeam should return team when found`() {
        `when`(teamService.getTeam(1L)).thenReturn(testTeam)
        `when`(teamService.convertToResponse(testTeam)).thenReturn(testResponse)

        val result = controller.getTeam(1L)

        assertTrue(result.isSuccess())
        assertNotNull(result.data)
        assertEquals("Research report team", result.data?.name)
    }

    @Test
    @DisplayName("getTeam - returns a named 404 when the row cannot be read")
    fun `getTeam should report not found with a named 404`() {
        `when`(teamService.getTeam(999L)).thenReturn(null)

        val result = controller.getTeam(999L)

        assertFalse(result.isSuccess(), "a row that cannot be read must not count as a success response")
        assertEquals(404, result.code)
        assertEquals("error.team.notfound", result.message)
        assertNull(result.data)
    }
}
