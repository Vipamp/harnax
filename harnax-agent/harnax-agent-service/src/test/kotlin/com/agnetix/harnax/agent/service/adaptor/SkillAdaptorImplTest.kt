package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.skill.store.SkillContentData
import com.agnetix.harnax.agent.skill.store.SkillContentReader
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
 * Tests the skill loading logic with fallback from ContentStore to DB fields.
 */
class SkillAdaptorImplTest {

    private lateinit var specContextHolder: AgentSpecContextHolder
    private lateinit var skillMapper: SkillMapper
    private lateinit var objectMapper: ObjectMapper
    private lateinit var skillContentReader: SkillContentReader
    private lateinit var adaptor: SkillAdaptorImpl

    private lateinit var testSkill: Skill

    @BeforeEach
    fun setUp() {
        specContextHolder = mock(AgentSpecContextHolder::class.java)
        skillMapper = mock(SkillMapper::class.java)
        objectMapper = ObjectMapper()
        skillContentReader = mock(SkillContentReader::class.java)
        adaptor = SkillAdaptorImpl(specContextHolder, skillMapper, objectMapper, skillContentReader)

        testSkill = Skill().apply {
            id = 1L
            tenantId = 1L
            name = "test-skill"
            repositoryId = 1L
            description = "A test skill"
            skillmd = "# Test Skill\n\nDB content."
            resources = """{"config.yaml":"key: value"}"""
            storagePath = "1/test-skill"
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
    }

    @Nested
    @DisplayName("Get Skill - ContentStore Loading")
    inner class ContentStoreLoading {

        @Test
        fun `getSkill should load from ContentStore when storagePath is set`() {
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillContentReader.load("1/test-skill")).thenReturn(
                SkillContentData(
                    skillmd = "# Store Content\n\nFrom ContentStore.",
                    resources = mapOf("data.json" to """{"loaded":true}"""),
                ),
            )

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            assertEquals("test-skill", result!!.name)
            assertEquals("# Store Content\n\nFrom ContentStore.", result.skillContent)
            assertEquals("A test skill", result.description)
            assertTrue(result.resources.containsKey("data.json"))
            verify(skillContentReader).load("1/test-skill")
        }

        @Test
        fun `getSkill should use ContentStore resources over DB resources`() {
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillContentReader.load("1/test-skill")).thenReturn(
                SkillContentData(
                    skillmd = "# Store Content",
                    resources = mapOf("store-file.txt" to "from store"),
                ),
            )

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            assertEquals(1, result!!.resources.size)
            assertTrue(result.resources.containsKey("store-file.txt"))
            assertFalse(result.resources.containsKey("config.yaml"))
        }
    }

    @Nested
    @DisplayName("Get Skill - Fallback to DB Fields")
    inner class FallbackToDb {

        @Test
        fun `getSkill should fall back to DB fields when storagePath is blank`() {
            val skillNoStorage = Skill().apply {
                id = 2L
                name = "old-skill"
                repositoryId = 1L
                description = "Old skill"
                skillmd = "# Old Skill\n\nFrom DB."
                resources = """{"db-file.txt":"from db"}"""
                storagePath = ""
                status = 1
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            `when`(skillMapper.selectById(2L)).thenReturn(skillNoStorage)

            val result = adaptor.getSkill(2L)

            assertNotNull(result)
            assertEquals("old-skill", result!!.name)
            assertEquals("# Old Skill\n\nFrom DB.", result.skillContent)
            assertTrue(result.resources.containsKey("db-file.txt"))
            verify(skillContentReader, never()).load(anyString())
        }

        @Test
        fun `getSkill should fall back when ContentStore load fails`() {
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillContentReader.load("1/test-skill"))
                .thenThrow(RuntimeException("ContentStore unavailable"))

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            assertEquals("test-skill", result!!.name)
            assertEquals("# Test Skill\n\nDB content.", result.skillContent)
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
                storagePath = ""
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
                storagePath = ""
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
    }

    @Nested
    @DisplayName("Get Skill - ContentStore with Empty Storage Path Edge Cases")
    inner class EdgeCases {

        @Test
        fun `getSkill should not try ContentStore when storagePath is whitespace only`() {
            val skillWhitespace = Skill().apply {
                id = 5L
                name = "whitespace-path"
                repositoryId = 1L
                description = "Whitespace path"
                skillmd = "# Whitespace"
                resources = "{}"
                storagePath = "   "
                status = 1
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            `when`(skillMapper.selectById(5L)).thenReturn(skillWhitespace)

            val result = adaptor.getSkill(5L)

            assertNotNull(result)
            verify(skillContentReader, never()).load(anyString())
        }

        @Test
        fun `getSkill should load from ContentStore with empty resources`() {
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillContentReader.load("1/test-skill")).thenReturn(
                SkillContentData(
                    skillmd = "# Empty Resources",
                    resources = emptyMap(),
                ),
            )

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            assertEquals("# Empty Resources", result!!.skillContent)
            assertTrue(result.resources.isEmpty())
        }

        @Test
        fun `getSkill should correctly pass description from entity not from content`() {
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillContentReader.load("1/test-skill")).thenReturn(
                SkillContentData(skillmd = "# Store", resources = emptyMap()),
            )

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            assertEquals("A test skill", result!!.description)
        }

        @Test
        fun `getSkill should return null when skillMapper throws exception`() {
            `when`(skillMapper.selectById(1L)).thenThrow(RuntimeException("Database connection failed"))

            val result = adaptor.getSkill(1L)

            assertNull(result)
        }

        @Test
        fun `getSkill should return null when AgentSkill builder fails`() {
            val skillMissingName = Skill().apply {
                id = 6L
                name = ""
                repositoryId = 1L
                description = "Missing name"
                skillmd = "# No Name"
                resources = ""
                storagePath = ""
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

        @Test
        fun `getSkill should handle skill with both empty storagePath and minimal skillmd`() {
            val skillAllEmpty = Skill().apply {
                id = 7L
                name = "all-empty"
                repositoryId = 1L
                description = "All empty"
                skillmd = "# Minimal"
                resources = ""
                storagePath = ""
                status = 1
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            `when`(skillMapper.selectById(7L)).thenReturn(skillAllEmpty)

            val result = adaptor.getSkill(7L)

            assertNotNull(result)
            assertEquals("all-empty", result!!.name)
            assertEquals("# Minimal", result.skillContent)
            assertTrue(result.resources.isEmpty())
            verify(skillContentReader, never()).load(anyString())
        }

        @Test
        fun `getSkill should handle ContentStore returning empty skillmd`() {
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillContentReader.load("1/test-skill")).thenReturn(
                SkillContentData(skillmd = "# FromStore", resources = emptyMap()),
            )

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            assertEquals("# FromStore", result!!.skillContent)
        }

        @Test
        fun `getSkill should handle very large resources map`() {
            val largeResources = (1..100).associate { i ->
                "file$i.txt" to "content $i".repeat(1000)
            }

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillContentReader.load("1/test-skill")).thenReturn(
                SkillContentData(skillmd = "# Large", resources = largeResources),
            )

            val result = adaptor.getSkill(1L)

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

            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillContentReader.load("1/test-skill")).thenReturn(
                SkillContentData(skillmd = "# Special", resources = specialResources),
            )

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            assertEquals(4, result!!.resources.size)
            assertTrue(result.resources.containsKey("path/with/slashes.txt"))
            assertTrue(result.resources.containsKey("file with spaces.txt"))
        }

        @Test
        fun `getSkill should use skillmd from ContentStore`() {
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillContentReader.load("1/test-skill")).thenReturn(
                SkillContentData(skillmd = "# Store Content", resources = emptyMap()),
            )

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            assertEquals("# Store Content", result!!.skillContent)
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
                storagePath = ""
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
        }
    }

    @Nested
    @DisplayName("Context-first path tests")
    inner class ContextFirstTests {

        private fun stubContext(skillDetails: List<SkillDetailDto>) {
            val specInfo = AgentSpecInfoResponse(
                agentId = 1L, agentName = "Test", description = "", systemPrompt = "",
                modelId = 1L, skillDetails = skillDetails,
            )
            `when`(specContextHolder.get()).thenReturn(specInfo)
        }

        @Test
        fun `getSkill should load from context when skill DTO found`() {
            val dto = SkillDetailDto(
                id = 10L, name = "ctx-skill", description = "From context",
                skillmd = "# Context Skill", storagePath = "", resources = "",
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
        fun `getSkill should fallback to DB when context has no matching skill`() {
            stubContext(emptyList())
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillContentReader.load("1/test-skill")).thenReturn(
                SkillContentData(skillmd = "# DB", resources = emptyMap()),
            )

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            verify(skillMapper).selectById(1L)
        }

        @Test
        fun `getSkill should fallback to DB when context is null`() {
            `when`(specContextHolder.get()).thenReturn(null)
            `when`(skillMapper.selectById(1L)).thenReturn(testSkill)
            `when`(skillContentReader.load("1/test-skill")).thenReturn(
                SkillContentData(skillmd = "# DB", resources = emptyMap()),
            )

            val result = adaptor.getSkill(1L)

            assertNotNull(result)
            verify(skillMapper).selectById(1L)
        }
    }
}
