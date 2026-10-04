package com.agnetix.harnax.admin.it

import com.agnetix.harnax.mapper.SkillUsageMapper
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The skill usage path on a real stack: the literal JSON the runtime posts, the tenant admin reads out of
 * that session rather than out of the caller, and the window the analytics endpoint answers with.
 *
 * Unit tests can assert the service's arithmetic; only this can assert that admin parses the body
 * `AdminApiClient.reportSkillUsage` builds. The two ends live in different modules and share nothing but
 * this wire shape, so a renamed field would otherwise surface as an analytics page full of zeros.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillUsageFlowIT : BaseAdminIT() {

    /** Must match admin.internal-api.secret in application-it.yml. */
    private val internalSecret = "it-internal-api-secret-0123456789abcdef"

    @Autowired
    private lateinit var skillUsageMapper: SkillUsageMapper

    private val suffix = Random.nextInt(100000, 999999)
    private val repoName = "it_usage_repo_$suffix"
    private val skillName = "it_usage_skill_$suffix"
    private val sessionTitle = "it_usage_session_$suffix"

    private var repoId: Long = -1
    private var skillId: Long = -1
    private var sessionUuid: String = ""

    /** The repository and skill the counts get filed against, created on first need. */
    private fun ensureSkill(): Long {
        if (skillId > 0) return skillId
        assertOk(
            postJson(
                "/api/admin/skill-repositories",
                mapOf(
                    "name" to repoName,
                    "url" to "https://example.com/it-usage.git",
                    "branch" to "main",
                    "status" to 1,
                ),
            ),
        )
        val repo = findInPage("/api/admin/skill-repositories/page", "name=$repoName") {
            it["name"]?.asText() == repoName
        }
        assertNotNull(repo, "created repository should be found")
        repoId = repo["id"].asLong()

        assertOk(
            postJson(
                "/api/admin/skills",
                mapOf(
                    "name" to skillName,
                    "repositoryId" to repoId,
                    "description" to "IT usage skill",
                    "skillmd" to "# IT usage skill",
                    "status" to 1,
                ),
            ),
        )
        val skill = findInPage("/api/admin/skills/page", "name=$skillName") { it["name"]?.asText() == skillName }
        assertNotNull(skill, "created skill should be found")
        skillId = skill["id"].asLong()
        return skillId
    }

    /** A `web-` session of tenant 1 — what the intake resolves the tenant from, never the request body. */
    private fun ensureSession(): String {
        if (sessionUuid.isNotEmpty()) return sessionUuid
        val agentName = "it_usage_agent_$suffix"
        assertOk(postJson("/api/admin/agents", agentCreateBody(agentName)))
        val agent = findInPage("/api/admin/agents/page", "name=$agentName") { it["name"]?.asText() == agentName }
        assertNotNull(agent, "prerequisite agent should exist")

        assertOk(postJson("/api/admin/sessions", mapOf("title" to sessionTitle, "agentId" to agent["id"].asLong())))
        val session = findInPage("/api/admin/sessions/page", "keyword=$sessionTitle") { it["title"]?.asText() == sessionTitle }
        assertNotNull(session, "prerequisite session should exist")
        sessionUuid = session["sessionId"].asText()
        return sessionUuid
    }

    /**
     * Posts to the intake endpoint with the body written out as wire text, in the exact shape
     * `AdminApiClient.reportSkillUsage` serialises: `{"sessionId":…,"events":[{skillId,event}]}`.
     *
     * @return the number of events admin filed
     */
    private fun report(
        sessionId: String,
        vararg events: Pair<Long, String>,
    ): Int {
        val eventsJson = events.joinToString(",") { """{"skillId":${it.first},"event":"${it.second}"}""" }
        val response = exchange(
            HttpMethod.POST,
            "/api/admin/internal/skills/usage",
            body = """{"sessionId":"$sessionId","events":[$eventsJson]}""",
            token = internalSecret,
        )
        return assertOk(parseBody(response)).asInt()
    }

    private fun viewsOf(skillId: Long): Int = skillUsageMapper
        .selectUsageByTenant(1L, LocalDateTime.now().minusDays(1), null)
        .first { it.skillId == skillId }
        .viewCount

    @Test
    @Order(1)
    fun `the body the runtime posts parses and files one event`() {
        val skill = ensureSkill()
        val session = ensureSession()

        assertEquals(1, report(session, skill to "VIEW"))

        // Read back through the aggregate the page is built on, so the write and the read are one fact.
        val rows = assertOk(getJson("/api/admin/skill-usage/summary?days=1"))["rows"]
        val row = rows.first { it["skillId"].asLong() == skill }
        assertEquals(1, row["viewCount"].asInt(), "the load just filed should be counted")
        assertEquals(0, row["useCount"].asInt(), "nothing on this path can produce a use event")
        assertEquals(repoName, row["repositoryName"].asText())
        assertEquals("human", row["origin"].asText(), "a skill admin created is not agent-written")
    }

    @Test
    @Order(2)
    fun `each report is its own event`() {
        val skill = ensureSkill()
        val session = ensureSession()
        val before = viewsOf(skill)

        assertEquals(1, report(session, skill to "VIEW"))

        assertEquals(
            before + 1,
            viewsOf(skill),
            "the recorder upstream is what throttles; the intake counts what arrives",
        )
    }

    @Test
    @Order(3)
    fun `a skill this tenant cannot see files nothing`() {
        // The tenant comes from the session, so a skill id the reporter's tenant has no business with is
        // dropped rather than filed where the number was pointed at.
        assertEquals(0, report(ensureSession(), 9_999_999L to "VIEW"))
    }

    @Test
    @Order(4)
    fun `a session admin cannot resolve files nothing instead of failing the report`() {
        // The runtime's reporter logs a non-200 and drops the batch; an unknown session has to answer as an
        // accepted no-op, or a deleted session turns into a warning on every turn that reads its skills.
        assertEquals(0, report("web-00000000-0000-0000-0000-000000000000", ensureSkill() to "VIEW"))
    }

    @Test
    @Order(5)
    fun `another tenant's window does not list this tenant's skill`() {
        val skill = ensureSkill()
        report(ensureSession(), skill to "VIEW")

        val asOtherTenant = exchange(HttpMethod.GET, "/api/admin/skill-usage/summary?days=1", tenantId = 940_005L)
        val rows = assertOk(parseBody(asOtherTenant))["rows"]
        assertTrue(rows.none { it["skillId"].asLong() == skill }, "tenant 940_005 must not see skill $skill")
    }

    @Test
    @Order(6)
    fun `an unknown event kind is refused while the view beside it is kept`() {
        val skill = ensureSkill()

        assertEquals(1, report(ensureSession(), skill to "HOVER", skill to "VIEW"))
    }
}
