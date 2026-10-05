package com.agnetix.harnax.harness.skill

import com.agnetix.harnax.entity.SkillVisibilityPolicy
import com.agnetix.harnax.entity.dto.SkillVisibilityDto
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.skill.AgentSkill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which of the delivered skills reach the model's list, for one conversation.
 *
 * The rollout numbers here are not invented for the test. Upstream `CanaryFilter` buckets on
 * `userId + "|" + skillName` with a 32-bit rolling hash, and this filter has to answer the same way or one
 * user gets two answers for one percentage. So each percentage and user id pair below was computed with that
 * definition against real `int` arithmetic — for the skill named `report`, user 46 lands in bucket 10, user 5
 * in 60, user 70 in 0 and user 52 in 99.
 */
class TenantSkillVisibilityFilterTest {

    private fun skill(name: String) = AgentSkill.builder()
        .name(name)
        .description("description of $name")
        .skillContent("# $name")
        .build()

    /** The names one filter run kept, in the order it received them. */
    private fun kept(
        policies: Map<String, SkillVisibilityDto>,
        userId: Long?,
        vararg names: String,
        environment: String = TenantSkillVisibilityFilter.DEFAULT_ENVIRONMENT,
        ctx: RuntimeContext? = null,
    ): List<String> = TenantSkillVisibilityFilter(policies, userId, environment)
        .filter(names.map(::skill), ctx)
        .mapNotNull { it.name }

    private fun canary(percent: Int?) = SkillVisibilityDto(
        mode = SkillVisibilityPolicy.MODE_CANARY,
        canaryPct = percent,
    )

    @Test
    fun `an allow list shows the skill to the users it names and to nobody else`() {
        val policies = mapOf(
            "report" to SkillVisibilityDto(
                mode = SkillVisibilityPolicy.MODE_ALLOW_LIST,
                userIds = listOf(42L),
            ),
        )

        assertEquals(listOf("report"), kept(policies, 42L, "report"))
        // The acceptance rule of this whole tier: one skill, one agent, two people, opposite answers.
        assertTrue(kept(policies, 43L, "report").isEmpty(), "user 43 is not on the list")
    }

    @Test
    fun `a conversation with no user behind it does not get a user-restricted skill`() {
        // Channel traffic (Feishu, WeCom) reaches the runtime with no harnax identity. Showing it a skill
        // scoped to named users would make the guard depend on who happened to be watching.
        val allowList = mapOf(
            "report" to SkillVisibilityDto(mode = SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = listOf(42L)),
        )
        val restricted = TenantSkillVisibilityFilter(allowList, null).filter(listOf(skill("report")), null)

        assertTrue(restricted.isEmpty(), "no identity means no membership proof")
    }

    @Test
    fun `a canary buckets by the user the run was built for, not by the runtime context`() {
        // Deliberate: upstream reads `RuntimeContext.userId`, which this deployment keeps empty because the
        // persisted agent-state slot is derived from it. Filling it in to satisfy a filter would move every
        // existing session's state bucket (design section 4.1).
        val policies = mapOf("report" to canary(20))
        val contextOfAnotherUser = RuntimeContext.builder().sessionId("web-1").userId("5").build()

        // User 46 is in bucket 10, so 20% admits them; the context naming bucket-60 user 5 must not overturn it.
        assertEquals(listOf("report"), kept(policies, 46L, "report", ctx = contextOfAnotherUser))
        // And the reverse: a context naming the admitted user must not admit a run that is not theirs.
        assertTrue(kept(policies, 5L, "report", ctx = RuntimeContext.builder().userId("46").build()).isEmpty())
    }

    @Test
    fun `the percentage is the boundary it names`() {
        val policies20 = mapOf("report" to canary(20))

        // Bucket 10 is inside 20%, bucket 60 is not.
        assertEquals(listOf("report"), kept(policies20, 46L, "report"))
        assertTrue(kept(policies20, 5L, "report").isEmpty())

        // 10% means buckets 0..9: user 46's own bucket is the strict boundary, and `<` rather than `<=` is
        // what upstream does.
        val policies10 = mapOf("report" to canary(10))
        assertTrue(kept(policies10, 46L, "report").isEmpty(), "10% must not admit bucket 10")
        val policies11 = mapOf("report" to canary(11))
        assertEquals(listOf("report"), kept(policies11, 46L, "report"))
    }

    @Test
    fun `zero hides everybody and a hundred shows everybody`() {
        val zero = mapOf("report" to canary(0))
        // User 70 sits in bucket 0, which the harness's own rule still holds out of a 0% rollout.
        assertTrue(kept(zero, 70L, "report").isEmpty(), "0% is nobody, not everybody")

        val hundred = mapOf("report" to canary(100))
        assertEquals(listOf("report"), kept(hundred, 52L, "report"), "100% is everybody, even bucket 99")
    }

    @Test
    fun `an out of range percentage means what the harness would read it as`() {
        // Upstream clamps a percentage into 0..100 when it builds the filter, so 150 is a full rollout and a
        // negative is none. Reading them as unreadable instead would give a user two answers depending on
        // which of the two filters happened to run.
        assertEquals(listOf("report"), kept(mapOf("report" to canary(150)), 5L, "report"))
        assertTrue(kept(mapOf("report" to canary(-5)), 70L, "report").isEmpty())
    }

    @Test
    fun `a canary row that carries no percentage delivers the skill`() {
        // Half-written policy: the mode says a rollout without saying how far. An unreadable rule costs a
        // guard rather than a working skill, and the control plane shows the row as it is.
        assertEquals(listOf("report"), kept(mapOf("report" to canary(null)), 46L, "report"))
    }

    @Test
    fun `one restricted skill does not take its neighbour out of the list`() {
        val policies = mapOf(
            "report" to SkillVisibilityDto(mode = SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = listOf(42L)),
            "pdf-tools" to SkillVisibilityDto(mode = SkillVisibilityPolicy.MODE_ALL),
        )

        assertEquals(listOf("pdf-tools"), kept(policies, 43L, "report", "pdf-tools"))
    }

    @Test
    fun `a skill with no policy row is left alone`() {
        // Absence is the ordinary state: a skill nobody configured stays visible, or a first deploy of this
        // tier would empty every agent's list.
        val policies = mapOf("other" to canary(0))

        assertEquals(listOf("report"), kept(policies, 42L, "report"))
    }

    @Test
    fun `a mode this runtime does not know leaves the skill in`() {
        // Admin is free to add a mode before the agent-service image carries it.
        val newer = mapOf("report" to SkillVisibilityDto(mode = "GRAY", userIds = listOf(1L), canaryPct = 0))
        val blank = mapOf("report" to SkillVisibilityDto(mode = ""))

        assertEquals(listOf("report"), kept(newer, 42L, "report"))
        assertEquals(listOf("report"), kept(blank, 42L, "report"))
    }

    @Test
    fun `an allow list that names nobody restricts nobody`() {
        // Same reading as an unknown mode: the column is empty, so there is no rule to apply. The control
        // plane refuses to save this shape, which is why it only arrives here through a hand-edited row.
        val policies = mapOf(
            "report" to SkillVisibilityDto(mode = SkillVisibilityPolicy.MODE_ALLOW_LIST, userIds = emptyList()),
        )

        assertEquals(listOf("report"), kept(policies, 42L, "report"))
    }

    @Test
    fun `the environment rule ignores case and padding`() {
        val policies = mapOf(
            "report" to SkillVisibilityDto(
                mode = SkillVisibilityPolicy.MODE_ENV,
                environments = listOf(" Staging ", "canary"),
            ),
        )

        assertEquals(listOf("report"), kept(policies, 42L, "report", environment = "staging"))
        assertEquals(listOf("report"), kept(policies, 42L, "report", environment = "CANARY"))
        assertTrue(kept(policies, 42L, "report", environment = "prod").isEmpty())
    }

    @Test
    fun `an environment policy that names no environment applies to every one`() {
        val policies = mapOf(
            "report" to SkillVisibilityDto(mode = SkillVisibilityPolicy.MODE_ENV, environments = emptyList()),
        )

        assertEquals(listOf("report"), kept(policies, 42L, "report", environment = "prod"))
    }

    @Test
    fun `the skills that came in are the instances that go out`() {
        // Upstream matches what a filter returned against what it handed over through an IdentityHashMap, so
        // an equal copy would drop the skill from the prompt rather than keep it.
        val delivered = listOf(skill("report"), skill("pdf-tools"))
        val policies = mapOf("report" to canary(0))

        val keptItems = TenantSkillVisibilityFilter(policies, 42L).filter(delivered, null)

        assertEquals(1, keptItems.size)
        assertSame(delivered[1], keptItems[0])
    }

    @Test
    fun `an empty delivered set and an empty policy map both answer without work`() {
        val policies = mapOf("report" to canary(0))

        assertTrue(TenantSkillVisibilityFilter(policies, 42L).filter(null, null).isEmpty(), "nothing in, nothing out")
        assertEquals(
            listOf("report"),
            kept(emptyMap(), 42L, "report"),
            "a runtime with no policies must not drop anything",
        )
        assertTrue(TenantSkillVisibilityFilter(policies, 42L).filter(emptyList(), null).isEmpty())
    }

    @Test
    fun `an evaluation that throws delivers every skill unchanged`() {
        // The guard is worth less than the skill: upstream itself passes everything through when a filter
        // fails, so this must not be the thing that empties a prompt.
        val readable = mapOf("report" to canary(50))
        val exploding = object : Map<String, SkillVisibilityDto> by readable {
            override fun get(key: String): SkillVisibilityDto? = throw IllegalStateException("a policy that cannot be read")
        }
        val delivered = listOf(skill("report"), skill("pdf-tools"))

        assertEquals(delivered, TenantSkillVisibilityFilter(exploding, 42L).filter(delivered, null))
    }
}
