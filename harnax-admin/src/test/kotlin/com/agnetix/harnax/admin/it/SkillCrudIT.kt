package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpMethod
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Skill CRUD regression: /api/admin/skills
 * A skill repository is created first via /api/admin/skill-repositories.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val repoName = "it_skill_repo_$suffix"
    private val skillName = "it_skill_$suffix"

    private var repoId: Long = -1
    private var skillId: Long = -1

    private fun locateRepoId(): Long {
        if (repoId > 0) return repoId
        val record = findInPage("/api/admin/skill-repositories/page", "name=$repoName") {
            it["name"]?.asText() == repoName
        }
        assertNotNull(record, "created skill repository should be found in page result")
        repoId = record["id"].asLong()
        return repoId
    }

    private fun locateSkillId(): Long {
        if (skillId > 0) return skillId
        val record = findInPage("/api/admin/skills/page", "name=$skillName") {
            it["name"]?.asText() == skillName
        }
        assertNotNull(record, "created skill should be found in page result")
        skillId = record["id"].asLong()
        return skillId
    }

    @Test
    @Order(1)
    fun `create skill repository succeeds`() {
        val body = mapOf(
            "name" to repoName,
            "url" to "https://example.com/it-skills.git",
            "branch" to "main",
            "description" to "IT skill repository",
            "status" to 1,
        )
        assertOk(postJson("/api/admin/skill-repositories", body))
        assertTrue(locateRepoId() > 0)
    }

    @Test
    @Order(2)
    fun `create skill succeeds`() {
        val body = mapOf(
            "name" to skillName,
            "repositoryId" to locateRepoId(),
            "description" to "IT skill",
            "skillmd" to "# IT Skill\n\nHello from integration test.",
            "resources" to "{}",
            "status" to 1,
        )
        assertOk(postJson("/api/admin/skills", body))
    }

    @Test
    @Order(3)
    fun `page query finds created skill`() {
        assertTrue(locateSkillId() > 0)
    }

    @Test
    @Order(4)
    fun `get detail returns skill with repository info`() {
        val data = assertOk(getJson("/api/admin/skills/${locateSkillId()}"))
        assertEquals(skillName, data["name"].asText())
        assertEquals(locateRepoId(), data["repositoryId"].asLong())
        assertEquals(repoName, data["repositoryName"].asText())
    }

    @Test
    @Order(5)
    fun `update skill and verify`() {
        val body = mapOf(
            "name" to skillName,
            "description" to "IT skill updated",
            "skillmd" to "# IT Skill v2",
        )
        assertOk(putJson("/api/admin/skills/update/${locateSkillId()}", body))

        val data = assertOk(getJson("/api/admin/skills/${locateSkillId()}"))
        assertEquals("IT skill updated", data["description"].asText())
        assertEquals("# IT Skill v2", data["skillmd"].asText())
    }

    @Test
    @Order(6)
    fun `toggle skill status off and on`() {
        assertOk(putJson("/api/admin/skills/toggle/${locateSkillId()}?status=0"))
        var record = findInPage("/api/admin/skills/page", "name=$skillName") {
            it["name"]?.asText() == skillName
        }
        assertNotNull(record)
        assertEquals(0, record["status"].asInt())

        assertOk(putJson("/api/admin/skills/toggle/${locateSkillId()}?status=1"))
        record = findInPage("/api/admin/skills/page", "name=$skillName") {
            it["name"]?.asText() == skillName
        }
        assertNotNull(record)
        assertEquals(1, record["status"].asInt())
    }

    @Test
    @Order(7)
    fun `create skill with duplicate name in same repository fails`() {
        val body = mapOf(
            "name" to skillName,
            "repositoryId" to locateRepoId(),
            "description" to "dup",
            "skillmd" to "# dup",
            "resources" to "{}",
        )
        assertErr(postJson("/api/admin/skills", body))
    }

    @Test
    @Order(8)
    fun `create skill with name too long returns 400`() {
        val body = mapOf(
            "name" to "x".repeat(101),
            "repositoryId" to locateRepoId(),
            "description" to "bad",
            "skillmd" to "# bad",
            "resources" to "{}",
        )
        assertErr(postJson("/api/admin/skills", body))
    }

    @Test
    @Order(9)
    fun `detail of another tenant's skill answers exactly like a missing id`() {
        // AGENT-27: this read threw a tenant refusal, which the controller flattened into a 500 after
        // confirming the skill exists — and a skill row carries its SKILL.md and resources.
        val unknownId = 999_999_998L
        val asOtherTenant = exchange(HttpMethod.GET, "/api/admin/skills/${locateSkillId()}", tenantId = 940_005L)
        val hidden = parseBody(asOtherTenant)
        val asMissingId = getJson("/api/admin/skills/$unknownId")

        assertEquals(200, asOtherTenant.statusCode.value(), "an invisible skill must answer HTTP 200 with an envelope 404")
        assertEquals(404, hidden["code"].asInt(), "another tenant's skill must not read as a success: $hidden")
        assertTrue(hidden["data"] == null || hidden["data"].isNull, "a refusal must not carry the row")
        assertEquals(asMissingId["message"].asText(), hidden["message"].asText(), "a foreign skill has to answer like a missing one")
        assertNotEquals("error.skill.notfound", hidden["message"].asText(), "the refusal must be the bundle's text, not the key")

        // The owning tenant still reads it, so nothing about the legitimate path changed
        assertEquals(skillName, assertOk(getJson("/api/admin/skills/${locateSkillId()}"))["name"].asText())
    }

    @Test
    @Order(10)
    fun `delete skill then detail lookup reports not found`() {
        assertOk(deleteJson("/api/admin/skills/${locateSkillId()}"))

        // Same contract as every other detail endpoint: a read that misses is a 404 with no data.
        val node = getJson("/api/admin/skills/$skillId")
        assertEquals(404, node["code"].asInt(), "a row we cannot read must not answer as success")
        assertTrue(node["data"] == null || node["data"].isNull)

        val record = findInPage("/api/admin/skills/page", "name=$skillName") {
            it["name"]?.asText() == skillName
        }
        assertTrue(record == null, "deleted skill should not appear in page result")
    }

    @Test
    @Order(11)
    fun `cleanup delete skill repository`() {
        assertOk(deleteJson("/api/admin/skill-repositories/${locateRepoId()}"))
        val node = getJson("/api/admin/skill-repositories/$repoId")
        assertEquals(404, node["code"].asInt(), "a row we cannot read must not answer as success")
        assertTrue(node["data"] == null || node["data"].isNull)
    }
}
