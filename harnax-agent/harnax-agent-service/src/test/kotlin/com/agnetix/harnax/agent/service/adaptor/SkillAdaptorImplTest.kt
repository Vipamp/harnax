package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.SkillDetailDto
import com.agnetix.harnax.mapper.SkillMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

/**
 * Unit tests for SkillAdaptorImpl.
 * Skill content lives in MySQL, so content comes from the skillmd/resources columns.
 */
class SkillAdaptorImplTest {

    private lateinit var specContextHolder: AgentSpecContextHolder
    private lateinit var skillMapper: SkillMapper
    private lateinit var objectMapper: ObjectMapper
    private lateinit var adaptor: SkillAdaptorImpl

    private lateinit var testSkill: Skill

    @BeforeEach
    fun setUp() {
        specContextHolder = mock(AgentSpecContextHolder::class.java)
        skillMapper = mock(SkillMapper::class.java)
        objectMapper = ObjectMapper()
        adaptor = SkillAdaptorImpl(specContextHolder, skillMapper, objectMapper)

        testSkill = Skill().apply {
            id = 1L
            tenantId = 1L
            name = "test-skill"
            repositoryId = 1L
            description = "A test skill"
            skillmd = "# Test Skill\n\nDB content."
            resources = """{"config.yaml":"key: value"}"""
            version = "1.0.0"
            status = 1
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
    }

    @Nested
    @DisplayName("Get Skill - Basic Validation")
    inner class BasicValidation {

        @Test
        fun `getSkill should return null for invalid skillId zero`() {
            val result = adaptor.getSkill(0)
            assertNull(result)
        }

        @Test
        fun `getSkill should return null for negative skillId`() {
            val result = adaptor.getSkill(-1)
            assertNull(result)
        }

        @Test
        fun `getSkill should return null when skill not found in DB`() {
            `when`(skillMapper.selectById(999L)).thenReturn(null)

            val result = adaptor.getSkill(999L)

            assertNull(result)
            verify(skillMapper).selectById(999L)
        }

        @Test
        fun `getSkill should return null when the DB fallback lands on a disabled skill`() {
            // selectById 只过滤 active，不看 status，停用标记只能在这里认。admin 的每条下发路径
            // （/builtin-skills、agent 直接绑定、CLI 关联合并）都拦了停用技能，DB 回退不能成为
            // 唯一一条把已停用技能装进 harness 的路
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill.apply { status = 0 })

            val result = adaptor.getSkill(1L)

            assertNull(result)
            verify(skillMapper).selectById(1L)
        }

        @Test
        fun `getSkill should return null when skillMapper throws exception`() {
            `when`(skillMapper.selectById(1L)).thenThrow(RuntimeException("Database connection failed"))

            val result = adaptor.getSkill(1L)

            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Get Skill - Content From DB Columns")
    inner class ContentFromDb {

        @Test
        fun `getSkill should build skill from DB columns`() {
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            assertEquals("test-skill", result!!.name)
            assertEquals("# Test Skill\n\nDB content.", result.skillContent)
            assertEquals("A test skill", result.description)
            assertTrue(result.resources.containsKey("config.yaml"))
        }

        @Test
        fun `getSkill should handle empty resources JSON gracefully`() {
            val skillNoResources = Skill().apply {
                id = 3L
                name = "no-resources"
                repositoryId = 1L
                description = "No resources"
                skillmd = "# No Resources"
                resources = ""
                status = 1
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            `when`(skillMapper.selectById(3L)).thenReturn(skillNoResources)

            val result = adaptor.getSkill(3L)

            assertNotNull(result)
            assertEquals("no-resources", result!!.name)
            assertTrue(result.resources.isEmpty())
        }

        @Test
        fun `getSkill should handle malformed resources JSON gracefully`() {
            val skillBadJson = Skill().apply {
                id = 4L
                name = "bad-json"
                repositoryId = 1L
                description = "Bad JSON"
                skillmd = "# Bad JSON"
                resources = "not valid json{{{"
                status = 1
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            `when`(skillMapper.selectById(4L)).thenReturn(skillBadJson)

            val result = adaptor.getSkill(4L)

            assertNotNull(result)
            assertEquals("bad-json", result!!.name)
            assertEquals("# Bad JSON", result.skillContent)
            assertTrue(result.resources.isEmpty())
        }

        @Test
        fun `getSkill should treat empty JSON object as no resources`() {
            val skillEmptyJson = Skill().apply {
                id = 5L
                name = "empty-json"
                repositoryId = 1L
                description = "Empty JSON"
                skillmd = "# Empty JSON"
                resources = "{}"
                status = 1
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            `when`(skillMapper.selectById(5L)).thenReturn(skillEmptyJson)

            val result = adaptor.getSkill(5L)

            assertNotNull(result)
            assertTrue(result!!.resources.isEmpty())
        }

        @Test
        fun `getSkill should handle very large resources map`() {
            val largeResources = (1..100).associate { i ->
                "file$i.txt" to "content $i"
            }
            val skillLarge = Skill().apply {
                id = 9L
                name = "large-skill"
                repositoryId = 1L
                description = "Large"
                skillmd = "# Large"
                resources = ObjectMapper().writeValueAsString(largeResources)
                status = 1
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            `when`(skillMapper.selectById(9L)).thenReturn(skillLarge)

            val result = adaptor.getSkill(9L)

            assertNotNull(result)
            assertEquals(100, result!!.resources.size)
            assertTrue(result.resources.containsKey("file1.txt"))
            assertTrue(result.resources.containsKey("file100.txt"))
        }

        @Test
        fun `getSkill should handle resources with special characters in keys`() {
            val specialResources = mapOf(
                "path/with/slashes.txt" to "content",
                "file with spaces.txt" to "content",
                "file-with-dashes.txt" to "content",
                "file_with_underscores.txt" to "content",
            )
            val skillSpecial = Skill().apply {
                id = 10L
                name = "special-skill"
                repositoryId = 1L
                description = "Special"
                skillmd = "# Special"
                resources = ObjectMapper().writeValueAsString(specialResources)
                status = 1
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            `when`(skillMapper.selectById(10L)).thenReturn(skillSpecial)

            val result = adaptor.getSkill(10L)

            assertNotNull(result)
            assertEquals(4, result!!.resources.size)
            assertTrue(result.resources.containsKey("path/with/slashes.txt"))
            assertTrue(result.resources.containsKey("file with spaces.txt"))
        }

        @Test
        fun `getSkill should handle skill with minimal fields`() {
            val skillMinimal = Skill().apply {
                id = 8L
                name = "minimal-skill"
                repositoryId = 1L
                description = "Minimal desc"
                skillmd = "# Minimal"
                resources = ""
                status = 1
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            `when`(skillMapper.selectById(8L)).thenReturn(skillMinimal)

            val result = adaptor.getSkill(8L)

            assertNotNull(result)
            assertEquals("minimal-skill", result!!.name)
            assertEquals("Minimal desc", result.description)
            assertTrue(result.resources.isEmpty())
        }

        @Test
        fun `getSkill should handle skill with blank name gracefully`() {
            val skillMissingName = Skill().apply {
                id = 6L
                name = ""
                repositoryId = 1L
                description = "Missing name"
                skillmd = "# No Name"
                resources = ""
                status = 1
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            `when`(skillMapper.selectById(6L)).thenReturn(skillMissingName)

            // Builder may or may not throw depending on AgentSkill implementation
            val result = adaptor.getSkill(6L)

            // Should handle gracefully, either return null or valid object
            if (result != null) {
                assertEquals("", result.name)
            }
        }
    }

    @Nested
    @DisplayName("Context-first path tests")
    inner class ContextFirstTests {

        private fun stubContext(skillDetails: List<SkillDetailDto>) {
            val specInfo = AgentSpecInfoResponse(
                agentId = 1L,
                agentName = "Test",
                description = "",
                systemPrompt = "",
                modelId = 1L,
                skillDetails = skillDetails,
            )
            `when`(specContextHolder.get()).thenReturn(specInfo)
        }

        @Test
        fun `getSkill should load from context when skill DTO found`() {
            val dto = SkillDetailDto(
                id = 10L,
                name = "ctx-skill",
                description = "From context",
                skillmd = "# Context Skill",
                resources = "",
                version = "1.0.0",
            )
            stubContext(listOf(dto))

            val result = adaptor.getSkill(10L)

            assertNotNull(result)
            assertEquals("ctx-skill", result!!.name)
            assertEquals("# Context Skill", result.skillContent)
            verify(skillMapper, never()).selectById(10L)
        }

        @Test
        fun `getSkill should parse context DTO resources`() {
            val dto = SkillDetailDto(
                id = 11L,
                name = "ctx-resources",
                description = "With resources",
                skillmd = "# Context",
                resources = """{"ctx.txt":"from context"}""",
                version = "1.0.0",
            )
            stubContext(listOf(dto))

            val result = adaptor.getSkill(11L)

            assertNotNull(result)
            assertEquals(1, result!!.resources.size)
            assertEquals("from context", result.resources["ctx.txt"])
        }

        @Test
        fun `getSkill should fallback to DB when context has no matching skill`() {
            stubContext(emptyList())
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            verify(skillMapper).selectById(1L)
        }

        @Test
        fun `getSkill should fallback to DB when context is null`() {
            `when`(specContextHolder.get()).thenReturn(null)
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            verify(skillMapper).selectById(1L)
        }

        @Test
        fun `getSkill should still honour the disable flag when falling back past a non-empty context`() {
            // 上下文非空但未命中是常见情况（例如 CLI 合并阶段已把停用技能丢掉，skillDetails 里没它，
            // 而 harness 仍按 skillId 来取），回退到 DB 时不能把刚被丢掉的那一个又装回来
            stubContext(
                listOf(
                    SkillDetailDto(
                        id = 11L,
                        name = "other-skill",
                        description = "",
                        skillmd = "# Other",
                        resources = "",
                    ),
                ),
            )
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill.apply { status = 0 })

            val result = adaptor.getSkill(1L)

            assertNull(result)
            verify(skillMapper).selectById(1L)
        }
    }
}
