package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.exception.BizException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The rules both skill-source entry points share, so that neither of them can drift from the other.
 *
 * `null` and an empty list are two different instructions, and the ceiling is the only thing standing
 * between one request and a sync report full of failure entries, so both are pinned here rather than
 * left to whatever each service happens to write. The name rules are here for the same reason: the
 * platform's own filing names have to be refused on every path that creates a source, including the
 * legacy one.
 */
@DisplayName("SkillSourcePolicy shared source rules")
class SkillSourcePolicyTest {

    @Test
    @DisplayName("an absent selection stays absent instead of becoming an empty one")
    fun `an absent selection stays absent`() {
        assertNull(SkillSourcePolicy.normalizeSelection(null))
    }

    @Test
    @DisplayName("names are trimmed, blanks dropped and duplicates collapsed")
    fun `names are trimmed and deduplicated`() {
        assertEquals(
            listOf("pdf", "git"),
            SkillSourcePolicy.normalizeSelection(listOf(" pdf ", "pdf", "", "  ", "git")),
        )
    }

    @Test
    @DisplayName("a selection over the ceiling is refused with the shared message")
    fun `an oversized selection is refused`() {
        val names = (1..SkillSourcePolicy.MAX_SKILLS_PER_REQUEST + 1).map { "skill-$it" }

        val exception = assertThrows<BizException> { SkillSourcePolicy.normalizeSelection(names) }

        assertTrue(exception.message!!.contains("at most ${SkillSourcePolicy.MAX_SKILLS_PER_REQUEST}"), exception.message)
    }

    @Test
    @DisplayName("a selection exactly at the ceiling passes")
    fun `a selection at the ceiling passes`() {
        val names = (1..SkillSourcePolicy.MAX_SKILLS_PER_REQUEST).map { "skill-$it" }

        assertEquals(SkillSourcePolicy.MAX_SKILLS_PER_REQUEST, SkillSourcePolicy.normalizeSelection(names)?.size)
    }

    @Nested
    @DisplayName("name rules")
    inner class NameRules {

        @Test
        @DisplayName("the names the platform files skills under cannot be taken by a source an operator makes")
        fun `every reserved name is refused`() {
            // Walked off the set rather than spelled out, so a fourth reserved name added to BuiltinRepository
            // has to be refused here too, and this is the only place that says so.
            for (reserved in BuiltinRepository.RESERVED_NAMES) {
                val refused = assertThrows<BizException> { SkillSourcePolicy.requireUsableName(reserved, "Source") }
                assertTrue(
                    refused.message!!.contains(reserved),
                    "the refusal has to name the name it refused: ${refused.message}",
                )
            }
        }

        @Test
        @DisplayName("a name is trimmed before the reservation is checked, so padding buys no second key")
        fun `padding does not get past the reservation`() {
            val padded = "  ${BuiltinRepository.AGENT_SKILLS}  "

            assertThrows<BizException> { SkillSourcePolicy.requireUsableName(padded, "Source") }
            assertEquals("my-skills", SkillSourcePolicy.requireUsableName(" my-skills ", "Source"))
        }
    }
}
