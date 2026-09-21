package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.SkillDetailDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import tools.jackson.databind.ObjectMapper

/**
 * Unit tests for SkillAdaptorImpl.
 *
 * The delivered spec is the only source: Admin decides what a session may load, and this service has
 * no business reading `skill` rows behind that decision.
 */
class SkillAdaptorImplTest {

    private lateinit var specContextHolder: AgentSpecContextHolder
    private lateinit var adaptor: SkillAdaptorImpl

    @BeforeEach
    fun setUp() {
        specContextHolder = mock(AgentSpecContextHolder::class.java)
        adaptor = SkillAdaptorImpl(specContextHolder, ObjectMapper())
    }

    private fun dto(
        id: Long,
        name: String,
        description: String = "desc of $name",
        skillmd: String = "# $name",
        resources: String = "",
    ) = SkillDetailDto(
        id = id,
        name = name,
        description = description,
        skillmd = skillmd,
        resources = resources,
        version = "1.0.0",
    )

    private fun stubContext(vararg skills: SkillDetailDto) {
        `when`(specContextHolder.get()).thenReturn(
            AgentSpecInfoResponse(
                agentId = 1L,
                agentName = "Test",
                description = "",
                systemPrompt = "",
                modelId = 1L,
                skillDetails = skills.toList(),
            ),
        )
    }

    @Nested
    @DisplayName("Skill id validation")
    inner class BasicValidation {

        @Test
        fun `getSkill should return null for invalid skillId zero`() {
            assertNull(adaptor.getSkill(0))
            verifyNoInteractions(specContextHolder)
        }

        @Test
        fun `getSkill should return null for negative skillId`() {
            assertNull(adaptor.getSkill(-1))
            verifyNoInteractions(specContextHolder)
        }
    }

    @Nested
    @DisplayName("Get Skill - From the delivered spec")
    inner class ContextFirstTests {

        @Test
        fun `getSkill should load from context when skill DTO found`() {
            stubContext(dto(id = 10L, name = "ctx-skill", skillmd = "# Context Skill"))

            val result = adaptor.getSkill(10L)

            assertNotNull(result)
            assertEquals("ctx-skill", result!!.name)
            assertEquals("# Context Skill", result.skillContent)
        }

        @Test
        fun `getSkill should parse DTO resources`() {
            stubContext(dto(id = 11L, name = "ctx-resources", resources = """{"ctx.txt":"from context"}"""))

            val result = adaptor.getSkill(11L)

            assertNotNull(result)
            assertEquals(1, result!!.resources.size)
            assertEquals("from context", result.resources["ctx.txt"])
        }

        @Test
        fun `getSkill should treat a blank resources column as no bundled files`() {
            stubContext(dto(id = 12L, name = "no-resources", resources = ""))

            val result = adaptor.getSkill(12L)

            assertNotNull(result)
            assertTrue(result!!.resources.isEmpty())
        }

        @Test
        fun `getSkill should load a skill whose resources JSON is unparseable without its files`() {
            // A legacy row can still hold a truncated paste: the loader warns and the skill keeps
            // working as far as its SKILL.md goes
            stubContext(dto(id = 13L, name = "bad-json", resources = "not valid json{{{"))

            val result = adaptor.getSkill(13L)

            assertNotNull(result)
            assertTrue(result!!.resources.isEmpty())
        }

        @Test
        fun `getSkill should not load a delivered row the runtime cannot build`() {
            // `AgentSkill` refuses a blank body. The row was delivered, so the only honest answer is
            // "nothing", and the log has to say it is unloadable rather than missing
            stubContext(dto(id = 14L, name = "no-content", skillmd = ""))

            assertNull(adaptor.getSkill(14L))
        }

        @Test
        fun `getSkill should load nothing for an id the delivery does not carry`() {
            // Admin holds back whatever the caller may not see — a deleted row, a disabled skill,
            // another tenant's private one — and this service reads no database of its own, so a skill
            // outside the delivered list has no other answer than "nothing".
            stubContext(dto(id = 11L, name = "delivered"))

            assertNull(adaptor.getSkill(1L))
        }

        @Test
        fun `getSkill should load nothing when the context carries no spec`() {
            `when`(specContextHolder.get()).thenReturn(null)

            assertNull(adaptor.getSkill(1L))
        }
    }
}
