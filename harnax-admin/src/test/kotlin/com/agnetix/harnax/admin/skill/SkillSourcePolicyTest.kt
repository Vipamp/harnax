package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.exception.BizException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The one normalisation both selective-install entry points share.
 *
 * `null` and an empty list are two different instructions, and the ceiling is the only thing standing
 * between one request and a sync report full of failure entries, so both are pinned here rather than
 * left to whatever each service happens to write.
 */
@DisplayName("SkillSourcePolicy selection normalisation")
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
}
