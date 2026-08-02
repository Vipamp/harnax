package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Skill source CRUD regression: /api/admin/skill-sources
 *
 * Uses the ZIP source type exclusively so no network access (git clone / npm
 * install) is needed: a skill package zip is built on the fly in a temp dir.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillSourceCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val sourceName = "it_skill_source_$suffix"
    private val uploadName = "it_skill_upload_$suffix"
    private val skillDirName = "it-zip-skill-$suffix"

    private var sourceId: Long = -1
    private var uploadSourceId: Long = -1
    private var zipPath: Path? = null

    /** Build a zip containing <skillDirName>/SKILL.md (+ one resource file). */
    private fun buildSkillZip(): Path {
        zipPath?.let { return it }
        val dir = Files.createTempDirectory("it-skill-zip")
        val zip = dir.resolve("skills-$suffix.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("$skillDirName/SKILL.md"))
            out.write("# IT Zip Skill\n\nSkill installed from a zip during integration tests.\n".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("$skillDirName/resources/hello.txt"))
            out.write("hello from it\n".toByteArray())
            out.closeEntry()
        }
        zipPath = zip
        return zip
    }

    @Test
    @Order(1)
    fun `create ZIP skill source installs skills from archive`() {
        val body = mapOf(
            "name" to sourceName,
            "sourceType" to "ZIP",
            "sourceConfig" to mapOf("zipPath" to buildSkillZip().toString()),
            "description" to "IT zip skill source",
            "status" to 1,
        )
        val data = assertOk(postJson("/api/admin/skill-sources", body))
        sourceId = data["id"].asLong()
        assertTrue(sourceId > 0)
        assertEquals(sourceName, data["name"].asText())
        assertEquals("ZIP", data["sourceType"].asText())

        // The skill inside the zip has been installed
        val skill = findInPage("/api/admin/skills/page", "name=$skillDirName") {
            it["name"]?.asText() == skillDirName
        }
        assertNotNull(skill, "skill from zip should be installed")
    }

    @Test
    @Order(2)
    fun `page query finds created source with type filter`() {
        val record = findInPage("/api/admin/skill-sources/page", "name=$sourceName&sourceType=ZIP") {
            it["name"]?.asText() == sourceName
        }
        assertNotNull(record, "created source should be found in page result")
        assertEquals(sourceId, record["id"].asLong())
    }

    @Test
    @Order(3)
    fun `get detail returns source with config`() {
        val data = assertOk(getJson("/api/admin/skill-sources/$sourceId"))
        assertEquals(sourceName, data["name"].asText())
        assertEquals("ZIP", data["sourceType"].asText())
        assertEquals(buildSkillZip().toString(), data["sourceConfig"]["zipPath"].asText())
    }

    @Test
    @Order(4)
    fun `create source with duplicate name fails`() {
        val body = mapOf(
            "name" to sourceName,
            "sourceType" to "ZIP",
            "sourceConfig" to mapOf("zipPath" to buildSkillZip().toString()),
        )
        assertErr(postJson("/api/admin/skill-sources", body))
    }

    @Test
    @Order(5)
    fun `create ZIP source without zipPath fails validation`() {
        val body = mapOf(
            "name" to "it_bad_source_$suffix",
            "sourceType" to "ZIP",
            "sourceConfig" to emptyMap<String, Any>(),
        )
        assertErr(postJson("/api/admin/skill-sources", body))
    }

    @Test
    @Order(6)
    fun `update source changes description and verify`() {
        val body = mapOf("description" to "IT zip source updated", "version" to "1.0.1")
        assertOk(putJson("/api/admin/skill-sources/$sourceId", body))

        val data = assertOk(getJson("/api/admin/skill-sources/$sourceId"))
        assertEquals("IT zip source updated", data["description"].asText())
        assertEquals("1.0.1", data["version"].asText())
    }

    @Test
    @Order(7)
    fun `fetch skills lists the skill inside the zip`() {
        val data = assertOk(getJson("/api/admin/skill-sources/$sourceId/fetch"))
        assertTrue(data.isArray)
        val names = data.map { it["name"].asText() }
        assertTrue(skillDirName in names, "expected $skillDirName in fetched skills: $names")
    }

    @Test
    @Order(8)
    fun `upload zip via multipart installs skills`() {
        // Multipart upload cannot reuse the JSON helper: build a dedicated request
        val form = LinkedMultiValueMap<String, Any>()
        form.add("file", FileSystemResource(buildSkillZip()))
        form.add("name", uploadName)

        val headers = authHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }
        val response = RestTemplate().postForEntity(
            url("/api/admin/skill-sources/upload"),
            HttpEntity(form, headers),
            String::class.java,
        )
        val node = json.readTree(response.body)
        val data = assertOk(node)
        uploadSourceId = data["id"].asLong()
        assertTrue(uploadSourceId > 0)
        assertEquals(uploadName, data["name"].asText())
        assertEquals("ZIP", data["sourceType"].asText())
    }

    @Test
    @Order(9)
    fun `delete unknown source fails`() {
        val node = parseBody(exchange(HttpMethod.DELETE, "/api/admin/skill-sources/99999999"))
        assertErr(node)
    }

    @Test
    @Order(10)
    fun `delete sources also removes installed skills`() {
        assertOk(deleteJson("/api/admin/skill-sources/$sourceId"))
        if (uploadSourceId > 0) {
            assertOk(deleteJson("/api/admin/skill-sources/$uploadSourceId"))
        }

        assertErr(getJson("/api/admin/skill-sources/$sourceId"))

        val skill = findInPage("/api/admin/skills/page", "name=$skillDirName") {
            it["name"]?.asText() == skillDirName
        }
        assertTrue(skill == null, "skills installed from the deleted source should be removed")

        zipPath?.let { Files.deleteIfExists(it) }
    }
}
