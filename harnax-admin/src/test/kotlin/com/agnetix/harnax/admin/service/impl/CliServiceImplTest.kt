package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.Cli
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.github.pagehelper.PageHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDateTime

/**
 * CliServiceImpl Unit Tests
 *
 * Covers the read side and the operator's enable/disable switch. There are no create/update/delete
 * cases left to write: a row now only exists because a package was dropped in the plugin directory,
 * so `CliPackageAutoRegistrar` is the only writer (its own tests cover that).
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CliServiceImplTest {

    @Mock
    private lateinit var cliMapper: CliMapper

    @Mock
    private lateinit var skillMapper: SkillMapper

    @Mock
    private lateinit var secretFieldEncryptor: SecretFieldEncryptor

    private var objectMapper: ObjectMapper = jacksonObjectMapper()

    private lateinit var testCli: Cli
    private lateinit var shippedSkill: Skill

    @BeforeEach
    fun setUp() {
        testCli = Cli().apply {
            id = 1L
            name = "harnax-cli"
            description = "Harnax command line"
            version = "1.4.0"
            checkCommand = "harnax --version"
            skillId = 100L
            packageDigest = "a".repeat(64)
            payloadDigest = "b".repeat(64)
            packageObject = "harnax-cli/$packageDigest.harnaxcli.zip"
            depsApt = """["curl"]"""
            runtimeEnv = """{"HARNAX_URL":"${'$'}{platform.adminUrl}"}"""
            envParams = """[{"envParamName":"HARNAX_TOKEN"}]"""
            status = 1
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        shippedSkill = Skill().apply {
            id = 100L
            tenantId = 1L
            repositoryId = 10L
            name = "harnax-cli"
            description = "How to drive harnax-cli"
            status = 1
            active = 1
        }
    }

    @AfterEach
    fun tearDown() {
        PageHelper.clearPage()
    }

    private fun service(): CliServiceImpl = CliServiceImpl(
        cliMapper = cliMapper,
        skillMapper = skillMapper,
        secretFieldEncryptor = secretFieldEncryptor,
        objectMapper = objectMapper,
    )

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageQueryTests {

        @Test
        @DisplayName("page - unfiltered query reaches the mapper as-is")
        fun pageShouldQueryWithoutScoping() {
            `when`(cliMapper.selectCliList(null, null)).thenReturn(listOf(testCli))

            val page = service().page(null, null, 1, 10)

            assertEquals(1, page.records.size)
            assertEquals("harnax-cli", page.records[0].name)
            verify(cliMapper).selectCliList(null, null)
        }

        @Test
        @DisplayName("page - name and status filters pass through")
        fun pageShouldPassFiltersThrough() {
            `when`(cliMapper.selectCliList("harnax", 0)).thenReturn(emptyList())

            val page = service().page("harnax", 0, 1, 10)

            assertTrue(page.records.isEmpty())
            verify(cliMapper).selectCliList("harnax", 0)
        }
    }

    @Nested
    @DisplayName("Get CLI Tests")
    inner class GetCliTests {

        @Test
        @DisplayName("getCli - returns the registered row")
        fun getCliShouldReturnRow() {
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)

            assertEquals("1.4.0", service().getCli(1L)?.version)
        }

        @Test
        @DisplayName("getCli - returns null for an unknown id")
        fun getCliShouldReturnNullWhenAbsent() {
            `when`(cliMapper.selectById(999L)).thenReturn(null)

            assertNull(service().getCli(999L))
        }
    }

    @Nested
    @DisplayName("Toggle Status Tests")
    inner class ToggleStatusTests {

        @Test
        @DisplayName("toggleCliStatus - disabling is refused by nothing, bindings included")
        fun disableShouldSucceedRegardlessOfBindings() {
            // D9: the switch exists to stop a CLI that turned out to be a problem. Rejecting it while
            // agents still bind the CLI would leave the operator unable to act in one step — the page
            // shows the blast radius instead of gating on it.
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateStatus(1L, 0)).thenReturn(1)
            `when`(skillMapper.updateStatus(100L, 0)).thenReturn(1)

            assertTrue(service().toggleCliStatus(1L, 0))

            verify(cliMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleCliStatus - the shipped skill follows the CLI status")
        fun toggleShouldCascadeToSkill() {
            // I5: a disabled CLI must not leave its SKILL.md teaching agents a command their sandbox
            // no longer has.
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateStatus(1L, 0)).thenReturn(1)
            `when`(skillMapper.updateStatus(100L, 0)).thenReturn(1)

            assertTrue(service().toggleCliStatus(1L, 0))

            verify(skillMapper).updateStatus(100L, 0)
        }

        @Test
        @DisplayName("toggleCliStatus - re-enabling follows the skill back to enabled")
        fun enableShouldCascadeToSkill() {
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateStatus(1L, 1)).thenReturn(1)
            `when`(skillMapper.selectById(100L)).thenReturn(shippedSkill.apply { skillmd = "Run `harnax --help`." })
            `when`(skillMapper.updateStatus(100L, 1)).thenReturn(1)

            assertTrue(service().toggleCliStatus(1L, 1))

            verify(skillMapper).updateStatus(100L, 1)
        }

        @Test
        @DisplayName("toggleCliStatus - a CLI whose skill row is gone still switches")
        fun toggleShouldSurviveMissingSkillRow() {
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateStatus(1L, 0)).thenReturn(1)
            `when`(skillMapper.updateStatus(100L, 0)).thenReturn(0)

            assertTrue(service().toggleCliStatus(1L, 0))
        }

        @Test
        @DisplayName("toggleCliStatus - a package without a skill skips the cascade")
        fun toggleShouldSkipCascadeWithoutSkill() {
            testCli.skillId = null
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateStatus(1L, 0)).thenReturn(1)

            assertTrue(service().toggleCliStatus(1L, 0))

            verify(skillMapper, never()).updateStatus(any(), any())
        }

        @Test
        @DisplayName("toggleCliStatus - throws when the CLI is not registered")
        fun toggleShouldThrowWhenNotFound() {
            `when`(cliMapper.selectById(999L)).thenReturn(null)

            val exception = assertThrows<BizException> { service().toggleCliStatus(999L, 0) }

            assertEquals("CLI not found", exception.message)
            verify(cliMapper, never()).updateStatus(any(), any())
        }

        @Test
        @DisplayName("toggleCliStatus - reports false when no row was updated")
        fun toggleShouldReportFalseWhenNothingChanged() {
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateStatus(1L, 0)).thenReturn(0)
            `when`(skillMapper.updateStatus(100L, 0)).thenReturn(1)

            assertFalse(service().toggleCliStatus(1L, 0))
        }

        @Test
        @DisplayName("toggleCliStatus - a status outside the two states is refused before anything is written")
        fun toggleShouldRefuseAnUnknownStatus() {
            // Every reader compares the column with 1, so a 99 stored here would read as "disabled"
            // forever — and the switch that caused it could no longer bring the row back.
            val exception = assertThrows<BizException> { service().toggleCliStatus(1L, 99) }

            assertTrue(exception.message!!.contains("Status must be 0"), "said nothing useful: ${exception.message}")
            verify(cliMapper, never()).updateStatus(any(), any())
            verify(skillMapper, never()).updateStatus(any(), any())
        }

        @Test
        @DisplayName("toggleCliStatus - re-enabling does not lift a skill the content scan quarantined")
        fun enableShouldNotLiftContentScanQuarantine() {
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateStatus(1L, 1)).thenReturn(1)
            `when`(skillMapper.selectById(100L))
                .thenReturn(shippedSkill.apply { skillmd = "Wipe it with `rm -rf /` first." })
            `when`(skillMapper.updateStatus(100L, 0)).thenReturn(1)

            assertTrue(service().toggleCliStatus(1L, 1))

            // The binary goes back into the sandbox; the text that tells an agent to run something
            // destructive stays off until a human enables it from the skill page.
            verify(skillMapper).updateStatus(100L, 0)
            verify(skillMapper, never()).updateStatus(100L, 1)
        }

        @Test
        @DisplayName("toggleCliStatus - skill content that cannot be read stays off")
        fun enableShouldKeepUnreadableContentOff() {
            `when`(cliMapper.selectById(1L)).thenReturn(testCli)
            `when`(cliMapper.updateStatus(1L, 1)).thenReturn(1)
            `when`(skillMapper.selectById(100L)).thenReturn(shippedSkill.apply { resources = "{ not json" })
            `when`(skillMapper.updateStatus(100L, 0)).thenReturn(1)

            service().toggleCliStatus(1L, 1)

            verify(skillMapper).updateStatus(100L, 0)
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - carries the package digest and its shipped skill")
        fun convertShouldCarrySkill() {
            `when`(skillMapper.selectById(100L)).thenReturn(shippedSkill)

            val response = service().convertToResponse(testCli)

            assertEquals(testCli.id, response.id)
            assertEquals(testCli.packageDigest, response.packageDigest)
            assertEquals(100L, response.skill?.skillId)
            assertEquals("harnax-cli", response.skill?.skillName)
            assertEquals("How to drive harnax-cli", response.skill?.skillDescription)
        }

        @Test
        @DisplayName("convertToResponse - no skill when the package ships none")
        fun convertShouldTolerateNoSkill() {
            testCli.skillId = null

            val response = service().convertToResponse(testCli)

            assertNull(response.skill)
            verify(skillMapper, never()).selectById(any())
        }

        @Test
        @DisplayName("convertToResponse - tolerates a skill row deleted from under the CLI")
        fun convertShouldTolerateDanglingSkillId() {
            `when`(skillMapper.selectById(100L)).thenReturn(null)

            assertNull(service().convertToResponse(testCli).skill)
        }

        @Test
        @DisplayName("convertToResponse - masks a secret env param default")
        fun convertShouldMaskSecret() {
            val entries = listOf(
                ToolEnvParamEntry(envParamName = "HARNAX_TOKEN", secret = true, defaultValue = "cipher-text"),
            )
            testCli.envParams = objectMapper.writeValueAsString(entries)
            `when`(skillMapper.selectById(100L)).thenReturn(shippedSkill)
            `when`(secretFieldEncryptor.decrypt("cipher-text")).thenReturn("super-secret-token")

            val response = service().convertToResponse(testCli)

            assertEquals("sup****oken", response.envParams?.single()?.defaultValue)
        }
    }
}
