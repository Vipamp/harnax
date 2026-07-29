package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import kotlin.random.Random
import kotlin.test.assertEquals
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
            "resources" to "[]",
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
            "resources" to "[]",
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
            "resources" to "[]",
        )
        assertErr(postJson("/api/admin/skills", body))
    }

    @Test
    @Order(9)
    fun `delete skill then detail lookup returns null data`() {
        assertOk(deleteJson("/api/admin/skills/${locateSkillId()}"))

        // Detail lookup for a missing skill returns code=200 with data=null (same as other modules)
        val node = getJson("/api/admin/skills/$skillId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull)

        val record = findInPage("/api/admin/skills/page", "name=$skillName") {
            it["name"]?.asText() == skillName
        }
        assertTrue(record == null, "deleted skill should not appear in page result")
    }

    @Test
    @Order(10)
    fun `cleanup delete skill repository`() {
        assertOk(deleteJson("/api/admin/skill-repositories/${locateRepoId()}"))
        val node = getJson("/api/admin/skill-repositories/$repoId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull)
    }
}
