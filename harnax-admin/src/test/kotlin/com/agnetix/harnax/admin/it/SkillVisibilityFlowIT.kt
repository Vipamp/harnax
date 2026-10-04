package com.agnetix.harnax.admin.it

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.entity.Cli
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillVisibilityPolicy
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import com.agnetix.harnax.mapper.SkillVisibilityPolicyMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode
import kotlin.random.Random

/**
 * A rollout rule from the operator's screen to the wire the runtime reads, on a real stack.
 *
 * The unit tests of `SkillVisibilityServiceImpl` mock the table and the tests of
 * `TenantSkillVisibilityFilter` hand the filter a DTO directly. Only this class can prove the two ends
 * agree: that a percentage PUT to `/api/admin/skill-visibility` lands in `skill_visibility_policy` in the
 * encoding `SkillVisibilityCodec` decodes, and that the decoded value arrives inside
 * `/api/admin/internal/agent-spec` — the one place the harness can get it, because the visibility filter
 * runs per conversation where no admin call may be made.
 *
 * Three states are contrasted on one spec read, because the runtime answers them differently: a skill with
 * a restrictive row, a skill with no row at all (absent from the wire, which reads as visible), and a skill
 * whose operator explicitly opened it again (a row saying ALL).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillVisibilityFlowIT : BaseAdminIT() {

    /** Must match admin.internal-api.secret in application-it.yml. */
    private val internalSecret = "it-internal-api-secret-0123456789abcdef"

    @Autowired
    private lateinit var skillMapper: SkillMapper

    @Autowired
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    @Autowired
    private lateinit var cliMapper: CliMapper

    @Autowired
    private lateinit var visibilityMapper: SkillVisibilityPolicyMapper

    private val suffix = Random.nextInt(100000, 999999)
    private val repoName = "it_vis_repo_$suffix"
    private val restrictedName = "it_vis_restricted_$suffix"
    private val plainName = "it_vis_plain_$suffix"
    private val switchName = "it_vis_switch_$suffix"
    private val cliName = "it_vis_cli_$suffix"
    private val cliSkillName = "it_vis_cli_skill_$suffix"
    private val agentName = "it_vis_agent_$suffix"
    private val sessionTitle = "it_vis_session_$suffix"

    private var repoId = -1L
    private var restrictedId = -1L
    private var plainId = -1L
    private var switchId = -1L
    private var cliSkillId = -1L
    private var sessionUuid = ""

    /** JUnit's `assertNotNull` keeps Kotlin's inferred type nullable, which is useless for chaining. */
    private fun <T : Any> expect(
        actual: T?,
        message: String,
    ): T = requireNotNull(actual) { message }

    private fun ensureRepository(): Long {
        if (repoId > 0) return repoId
        assertOk(
            postJson(
                "/api/admin/skill-repositories",
                mapOf(
                    "name" to repoName,
                    "url" to "https://example.com/it-visibility.git",
                    "branch" to "main",
                    "status" to 1,
                ),
            ),
        )
        val repo = findInPage("/api/admin/skill-repositories/page", "name=$repoName") { it["name"]?.asText() == repoName }
        repoId = expect(repo, "created repository should be found")["id"].asLong()
        return repoId
    }

    /** A skill of this tenant, which is what makes the visibility page writable at all. */
    private fun ensureSkill(name: String): Long {
        ensureRepository()
        assertOk(
            postJson(
                "/api/admin/skills",
                mapOf(
                    "name" to name,
                    "repositoryId" to repoId,
                    "description" to "IT visibility skill",
                    "skillmd" to "# $name",
                    "status" to 1,
                ),
            ),
        )
        val skill = findInPage("/api/admin/skills/page", "name=$name") { it["name"]?.asText() == name }
        return expect(skill, "created skill should be found")["id"].asLong()
    }

    private fun ensureSkills() {
        if (restrictedId > 0) return
        restrictedId = ensureSkill(restrictedName)
        plainId = ensureSkill(plainName)
        switchId = ensureSkill(switchName)
    }

    private fun putVisibility(
        skillId: Long,
        body: Map<String, Any?>,
    ): JsonNode = assertOk(putJson("/api/admin/skill-visibility/$skillId", body))

    private fun getVisibility(skillId: Long): JsonNode = assertOk(getJson("/api/admin/skill-visibility/$skillId"))

    /**
     * A CLI package whose shipped skill is restricted by environment, seeded through the mapper the
     * registrar uses: the IT environment has no package directory, and what is pinned here is the delivery
     * branch, whose write path the tests above already cover.
     */
    private fun ensureCliSkill(): Long {
        if (cliSkillId > 0) return cliSkillId
        val builtin = expect(skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS), "the managed CLI skill repository must exist")
        val skill = Skill().apply {
            tenantId = builtin.tenantId
            name = cliSkillName
            repositoryId = builtin.id
            description = "IT cli shipped skill"
            skillmd = "# $cliSkillName"
            resources = "{}"
            version = "1.0.0"
            status = 1
            isPublic = 1
            creator = "SYSTEM"
            active = 1
        }
        skillMapper.insert(skill)
        cliSkillId = skill.id

        visibilityMapper.upsert(
            SkillVisibilityPolicy().apply {
                this.skillId = cliSkillId
                tenantId = builtin.tenantId
                mode = SkillVisibilityPolicy.MODE_ENV
                environments = "staging"
            },
        )

        cliMapper.upsertCliPackage(
            Cli().apply {
                name = cliName
                description = "IT visibility cli"
                version = "1.0.0"
                checkCommand = "it-vis-cli --version"
                this.skillId = cliSkillId
                packageDigest = "c".repeat(64)
                payloadDigest = "d".repeat(64)
                packageObject = "$cliName/package.harnaxcli.zip"
                depsApt = """["curl"]"""
                runtimeEnv = "{}"
                envParams = "[]"
                status = 1
            },
        )
        return cliSkillId
    }

    private fun ensureSession() {
        if (sessionUuid.isNotEmpty()) return
        ensureSkills()
        ensureCliSkill()
        val cli = expect(cliMapper.selectByName(cliName), "the seeded package should be readable")
        assertOk(
            postJson(
                "/api/admin/agents",
                agentCreateBody(agentName) +
                    mapOf(
                        // Three states in one read: a rule, no row, and an explicit ALL
                        "skillList" to "$restrictedId,$plainId,$switchId",
                        "cliList" to listOf(mapOf("id" to cli.id)),
                    ),
            ),
        )
        val agent = expect(
            findInPage("/api/admin/agents/page", "name=$agentName") { it["name"]?.asText() == agentName },
            "the agent carrying the skills should exist",
        )

        assertOk(postJson("/api/admin/sessions", mapOf("title" to sessionTitle, "agentId" to agent["id"].asLong())))
        val session = findInPage("/api/admin/sessions/page", "keyword=$sessionTitle") { it["title"]?.asText() == sessionTitle }
        sessionUuid = expect(session, "the session the runtime asks about should exist")["sessionId"].asText()
    }

    private fun spec(): JsonNode {
        ensureSession()
        return assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/internal/agent-spec/$sessionUuid", token = internalSecret)))
    }

    private fun deliveredSkill(skillId: Long): JsonNode = spec()["skillDetails"].firstOrNull { it["id"].asLong() == skillId }
        ?: error("skill $skillId was not delivered to the agent: ${spec()["skillDetails"]}")

    @Test
    @Order(1)
    fun `a skill nobody has configured answers ALL instead of a missing page`() {
        ensureSkills()

        val response = getVisibility(plainId)

        assertEquals(SkillVisibilityPolicy.MODE_ALL, response["mode"].asText())
        assertTrue(response["editable"].asBoolean(), "this tenant owns the row")
        assertNull(visibilityMapper.selectBySkillId(plainId), "reading it must not create it")
    }

    @Test
    @Order(2)
    fun `a percentage set on the screen is stored where the runtime decodes it`() {
        ensureSkills()

        val response = putVisibility(restrictedId, mapOf("mode" to "CANARY", "canaryPct" to 20))

        assertEquals(SkillVisibilityPolicy.MODE_CANARY, response["mode"].asText())
        assertEquals(20, response["canaryPct"].asInt())

        val row = expect(visibilityMapper.selectBySkillId(restrictedId), "the CANARY row should exist")
        assertEquals(20, row.canaryPct)
        assertEquals(SkillVisibilityPolicy.MODE_CANARY, row.mode)
        // The columns the mode does not use stay empty: a leftover list would be delivered as a second answer
        assertNull(row.userIds)
        assertNull(row.environments)
    }

    @Test
    @Order(3)
    fun `an id the tenant does not contain is refused over http and changes nothing`() {
        ensureSkills()

        val response = putJson(
            "/api/admin/skill-visibility/$restrictedId",
            mapOf("mode" to "ALLOW_LIST", "userIds" to listOf(9_999_999L)),
        )

        assertErr(response)
        val row = expect(visibilityMapper.selectBySkillId(restrictedId), "the previous rule still stands")
        assertEquals(SkillVisibilityPolicy.MODE_CANARY, row.mode)
        assertEquals(20, row.canaryPct)
    }

    @Test
    @Order(4)
    fun `replacing a mode replaces its column, down to the row the runtime reads`() {
        ensureSkills()

        putVisibility(switchId, mapOf("mode" to SkillVisibilityPolicy.MODE_CANARY, "canaryPct" to 30))
        assertEquals(30, expect(visibilityMapper.selectBySkillId(switchId), "the CANARY row").canaryPct)

        putVisibility(switchId, mapOf("mode" to SkillVisibilityPolicy.MODE_ENV, "environments" to listOf("staging")))
        val envRow = expect(visibilityMapper.selectBySkillId(switchId), "the ENV row")
        assertEquals("staging", envRow.environments)
        assertNull(envRow.canaryPct, "the rollout number of the mode that was replaced must not survive it")

        putVisibility(switchId, mapOf("mode" to SkillVisibilityPolicy.MODE_ALL))
        val open = expect(visibilityMapper.selectBySkillId(switchId), "the ALL row")
        assertEquals(SkillVisibilityPolicy.MODE_ALL, open.mode)
        assertNull(open.environments)
    }

    @Test
    @Order(5)
    fun `the spec carries the rule, the explicit absence, and the explicit ALL`() {
        ensureSkills()

        val restricted = deliveredSkill(restrictedId)
        assertEquals(SkillVisibilityPolicy.MODE_CANARY, restricted["visibility"]["mode"].asText())
        assertEquals(20, restricted["visibility"]["canaryPct"].asInt())

        // Jackson drops the null key, so the runtime sees no field and reads the skill as visible
        assertFalse(deliveredSkill(plainId).has("visibility"), "no row means nothing on the wire")

        val opened = deliveredSkill(switchId)
        assertEquals(
            SkillVisibilityPolicy.MODE_ALL,
            opened["visibility"]["mode"].asText(),
            "an operator's ALL is delivered as ALL, not stripped into absence",
        )
    }

    @Test
    @Order(6)
    fun `a skill that arrives inside its CLI package carries its rule too`() {
        ensureSession()

        val shipped = spec()["cliDetails"].firstOrNull { it["skill"] != null && !it["skill"].isNull }
            ?: error("no CLI skill was delivered: ${spec()["cliDetails"]}")

        assertEquals(cliSkillName, shipped["skill"]["name"].asText())
        assertEquals(SkillVisibilityPolicy.MODE_ENV, shipped["skill"]["visibility"]["mode"].asText())
        assertEquals(
            "staging",
            shipped["skill"]["visibility"]["environments"][0].asText(),
            "the CSV column has to arrive as the list the filter compares",
        )
    }

    @Test
    @Order(7)
    fun `a deleted rule leaves the next spec without a visibility field`() {
        ensureSkills()
        visibilityMapper.deleteBySkillId(restrictedId)

        assertFalse(deliveredSkill(restrictedId).has("visibility"), "the spec is read fresh on every delivery")
    }

    /**
     * The list page is where an operator sees whether a skill is gated at all, so its rows have to answer
     * with the same rule the runtime got. It carries a summary rather than the lists themselves: the full
     * allow-list belongs to the form, not to a page of twenty rows.
     */
    @Test
    @Order(8)
    fun `the skill list page shows the rule the runtime enforces`() {
        putVisibility(restrictedId, mapOf("mode" to SkillVisibilityPolicy.MODE_CANARY, "canaryPct" to 40))

        val row = expect(
            findInPage("/api/admin/skills/page", "repositoryId=$repoId") { it["name"]?.asText() == restrictedName },
            "the skill should be listed in its own repository",
        )
        assertEquals(SkillVisibilityPolicy.MODE_CANARY, row["visibility"]["mode"].asText())
        assertEquals(40, row["visibility"]["canaryPct"].asInt())

        val plain = expect(
            findInPage("/api/admin/skills/page", "repositoryId=$repoId") { it["name"]?.asText() == plainName },
            "the unconfigured skill should be listed",
        )
        assertFalse(
            plain.has("visibility"),
            "a page row must not show a rollout for a skill nobody has gated, and must not invent a row to do it",
        )
    }
}
