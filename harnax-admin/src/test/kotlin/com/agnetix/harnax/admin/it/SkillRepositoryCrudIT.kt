package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpMethod
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Skill repository CRUD regression: /api/admin/skill-repositories
 *
 * The fetch endpoint (/fetch/{id}) clones the repository with JGit; a real
 * remote is not available in the IT environment, so only the error branches
 * are exercised: unknown repository id and an invalid/unreachable git URL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillRepositoryCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val repoName = "it_skill_repo_$suffix"

    private var repoId: Long = -1

    private fun locateRepoId(): Long {
        if (repoId > 0) return repoId
        val record = findInPage("/api/admin/skill-repositories/page", "name=$repoName") {
            it["name"]?.asText() == repoName
        }
        assertNotNull(record, "created repository should be found in page result")
        repoId = record["id"].asLong()
        return repoId
    }

    @Test
    @Order(1)
    fun `create skill repository succeeds`() {
        val body = mapOf(
            "name" to repoName,
            "url" to "https://git.invalid/it/skills-$suffix.git",
            "branch" to "main",
            "description" to "IT skill repository",
            "status" to 1,
        )
        assertOk(postJson("/api/admin/skill-repositories", body))
    }

    @Test
    @Order(2)
    fun `page query finds created repository`() {
        assertTrue(locateRepoId() > 0)
    }

    @Test
    @Order(3)
    fun `get detail returns created repository`() {
        val data = assertOk(getJson("/api/admin/skill-repositories/${locateRepoId()}"))
        assertEquals(repoName, data["name"].asText())
        assertEquals("https://git.invalid/it/skills-$suffix.git", data["url"].asText())
        assertEquals("main", data["branch"].asText())
        assertEquals("IT skill repository", data["description"].asText())
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(4)
    fun `active repositories list contains created repository`() {
        val data = assertOk(getJson("/api/admin/skill-repositories/active"))
        assertTrue(data.isArray)
        assertTrue(data.any { it["id"].asLong() == locateRepoId() }, "enabled repository should be listed as active")
    }

    @Test
    @Order(5)
    fun `create repository with duplicate name fails`() {
        val body = mapOf(
            "name" to repoName,
            "url" to "https://git.invalid/it/dup.git",
            "branch" to "main",
        )
        assertErr(postJson("/api/admin/skill-repositories", body))
    }

    @Test
    @Order(6)
    fun `create repository with builtin reserved name fails`() {
        val body = mapOf(
            "name" to "builtin-cli-skills",
            "url" to "https://git.invalid/it/reserved.git",
            "branch" to "main",
        )
        assertErr(postJson("/api/admin/skill-repositories", body))
    }

    @Test
    @Order(7)
    fun `update repository changes url branch and description`() {
        val body = mapOf(
            "url" to "https://git.invalid/it/skills-$suffix-upd.git",
            "branch" to "develop",
            "description" to "IT skill repository updated",
        )
        assertOk(putJson("/api/admin/skill-repositories/update/${locateRepoId()}", body))

        val data = assertOk(getJson("/api/admin/skill-repositories/$repoId"))
        assertEquals("https://git.invalid/it/skills-$suffix-upd.git", data["url"].asText())
        assertEquals("develop", data["branch"].asText())
        assertEquals("IT skill repository updated", data["description"].asText())
    }

    @Test
    @Order(8)
    fun `update unknown repository fails`() {
        assertErr(putJson("/api/admin/skill-repositories/update/99999999", mapOf("description" to "nope")))
    }

    @Test
    @Order(9)
    fun `toggle repository status off removes it from active list`() {
        assertOk(putJson("/api/admin/skill-repositories/toggle/${locateRepoId()}?status=0"))
        var data = assertOk(getJson("/api/admin/skill-repositories/$repoId"))
        assertEquals(0, data["status"].asInt())

        val active = assertOk(getJson("/api/admin/skill-repositories/active"))
        assertTrue(active.none { it["id"].asLong() == repoId }, "disabled repository should not be active")

        assertOk(putJson("/api/admin/skill-repositories/toggle/$repoId?status=1"))
        data = assertOk(getJson("/api/admin/skill-repositories/$repoId"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(10)
    fun `toggle unknown repository fails`() {
        assertErr(putJson("/api/admin/skill-repositories/toggle/99999999?status=0"))
    }

    @Test
    @Order(11)
    fun `fetch remote skills of unknown repository fails`() {
        assertErr(getJson("/api/admin/skill-repositories/fetch/99999999"))
    }

    @Test
    @Order(12)
    fun `fetch remote skills with unreachable git url fails`() {
        // JGit clone against a non-resolvable host: the error branch is
        // exercised without any real remote repository.
        assertErr(getJson("/api/admin/skill-repositories/fetch/${locateRepoId()}"))
    }

    @Test
    @Order(13)
    fun `repository endpoints without token return 401`() {
        val page = exchange(HttpMethod.GET, "/api/admin/skill-repositories/page?pageNum=1&pageSize=1", token = null)
        assertEquals(401, page.statusCode.value())

        val create = exchange(HttpMethod.POST, "/api/admin/skill-repositories", mapOf("name" to "x"), token = null)
        assertEquals(401, create.statusCode.value())
    }

    @Test
    @Order(14)
    fun `delete repository then detail returns empty`() {
        assertOk(deleteJson("/api/admin/skill-repositories/${locateRepoId()}"))

        val node = getJson("/api/admin/skill-repositories/$repoId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull, "deleted repository should not be returned")

        val record = findInPage("/api/admin/skill-repositories/page", "name=$repoName") {
            it["name"]?.asText() == repoName
        }
        assertTrue(record == null, "deleted repository should not appear in page result")
    }
}
