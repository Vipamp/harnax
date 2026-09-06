package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Skill source regression: /api/admin/skill-sources
 *
 * Everything runs against a ZIP source built on the fly, so no git clone or npm install reaches
 * the network. A ZIP source is created by uploading the archive — POSTing a server-side `zipPath`
 * is refused — and that archive is deleted once the request finished, which is what makes the
 * "cannot be refreshed" answers asserted below the intended behaviour rather than a gap.
 *
 * Creating a source answers with `{source, install}`. The install half is the only place a
 * per-skill failure is reported, since a partial failure still returns HTTP 200, so it is
 * asserted alongside the source itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillSourceCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val sourceName = "it_skill_source_$suffix"
    private val zipFileName = "skills-$suffix.zip"
    private val skillDirName = "it-zip-skill-$suffix"

    private var sourceId: Long = -1
    private var zipPath: Path? = null

    /** Build a zip containing <skillDirName>/SKILL.md (+ one resource file). */
    private fun buildSkillZip(): Path {
        zipPath?.let { return it }
        val dir = Files.createTempDirectory("it-skill-zip")
        val zip = dir.resolve(zipFileName)
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
    fun `upload a zip creates the source and installs the skill inside`() {
        val data = assertOk(uploadSkillZip(buildSkillZip(), sourceName))
        val source = data["source"]
        val install = data["install"]
        assertNotNull(source, "upload should answer with the source it created: $data")
        assertNotNull(install, "upload should answer with what the install did: $data")

        sourceId = source["id"].asLong()
        assertTrue(sourceId > 0)
        assertEquals(sourceName, source["name"].asText())
        assertEquals("ZIP", source["sourceType"].asText())
        assertEquals(1, source["status"].asInt())

        // The archive held exactly one skill, and a per-skill failure would not have changed the
        // status code, so this half is what proves the skill really landed
        assertTrue(install["complete"].asBoolean(), "install should report no failure: $install")
        assertEquals(1, install["savedCount"].asInt())
        assertEquals(listOf(skillDirName), install["installed"].map { it.asText() })

        val skill = findInPage("/api/admin/skills/page", "name=$skillDirName") {
            it["name"]?.asText() == skillDirName
        }
        assertNotNull(skill, "the skill from the zip should be queryable")
    }

    @Test
    @Order(2)
    fun `page query finds the uploaded source with a type filter`() {
        val record = findInPage("/api/admin/skill-sources/page", "name=$sourceName&sourceType=ZIP") {
            it["name"]?.asText() == sourceName
        }
        assertNotNull(record, "uploaded source should be found in the page result")
        assertEquals(sourceId, record["id"].asLong())
    }

    @Test
    @Order(3)
    fun `detail keeps the server side zip path out of the config`() {
        val data = assertOk(getJson("/api/admin/skill-sources/$sourceId"))
        assertEquals(sourceName, data["name"].asText())
        assertEquals("ZIP", data["sourceType"].asText())
        assertEquals("Uploaded ZIP: $zipFileName", data["description"].asText())

        val config = data["sourceConfig"]
        assertNotNull(config, "an uploaded source stores the file name it came from")
        assertEquals(zipFileName, config["originalFilename"].asText())
        // The temp file behind `zipPath` is deleted before the request returns, so storing or
        // echoing it would hand the caller a path that does not exist
        assertNull(config["zipPath"], "the server side archive path must not leave the server")
    }

    @Test
    @Order(4)
    fun `creating a zip source through the json endpoint points at the upload one`() {
        val node = postJson(
            "/api/admin/skill-sources",
            mapOf(
                "name" to "it_bad_source_$suffix",
                "sourceType" to "ZIP",
                "sourceConfig" to mapOf("zipPath" to buildSkillZip().toString()),
            ),
        )
        assertErr(node)
        // Naming the endpoint to use instead is the whole point: the loader's own
        // "ZIP source config requires 'zipPath'" left the caller guessing
        assertTrue(
            node["message"].asText().contains("/api/admin/skill-sources/upload"),
            "the refusal should name the upload endpoint: ${node["message"].asText()}",
        )
    }

    @Test
    @Order(5)
    fun `creating a zip source without a config is refused the same way`() {
        val node = postJson(
            "/api/admin/skill-sources",
            mapOf("name" to "it_empty_zip_$suffix", "sourceType" to "ZIP", "sourceConfig" to emptyMap<String, Any>()),
        )
        assertErr(node)
        // The refusal keys off the source type, not off what the config happens to contain
        assertTrue(node["message"].asText().contains("/api/admin/skill-sources/upload"))
    }

    @Test
    @Order(6)
    fun `uploading a second source under the same name fails`() {
        val node = uploadSkillZip(buildSkillZip(), sourceName)
        assertErr(node)
        assertTrue(node["message"].asText().contains("already exists"))
    }

    @Test
    @Order(7)
    fun `update source changes description and version`() {
        assertOk(putJson("/api/admin/skill-sources/$sourceId", mapOf("description" to "IT zip source updated", "version" to "1.0.1")))

        val data = assertOk(getJson("/api/admin/skill-sources/$sourceId"))
        assertEquals("IT zip source updated", data["description"].asText())
        assertEquals("1.0.1", data["version"].asText())
    }

    @Test
    @Order(8)
    fun `fetching a zip source explains that an upload is one shot`() {
        val node = getJson("/api/admin/skill-sources/$sourceId/fetch")
        assertErr(node)
        assertTrue(
            node["message"].asText().contains("installed once at upload time"),
            "the refusal should say why a zip cannot be re-read: ${node["message"].asText()}",
        )
    }

    @Test
    @Order(9)
    fun `re-installing a zip source is refused for the same reason`() {
        val node = postJson("/api/admin/skill-sources/$sourceId/install")
        assertErr(node)
        assertTrue(node["message"].asText().contains("installed once at upload time"))
    }

    @Test
    @Order(10)
    fun `delete unknown source fails`() {
        assertErr(deleteJson("/api/admin/skill-sources/99999999"))
    }

    @Test
    @Order(11)
    fun `delete the source also removes the skills it installed`() {
        assertOk(deleteJson("/api/admin/skill-sources/$sourceId"))
        assertErr(getJson("/api/admin/skill-sources/$sourceId"))

        val skill = findInPage("/api/admin/skills/page", "name=$skillDirName") {
            it["name"]?.asText() == skillDirName
        }
        assertNull(skill, "the skills installed from a deleted source should be removed with it")

        zipPath?.let { Files.deleteIfExists(it) }
    }
}
