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
import kotlin.test.assertTrue

/**
 * Skill source toggle regression: /api/admin/skill-sources/toggle/{id}
 *
 * SkillSourceCrudIT covers every other /api/admin/skill-sources endpoint
 * (page, detail, create, update, delete, fetch, upload); the status toggle is
 * the single one it misses, so it is exercised here against a ZIP source that
 * needs no network access.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillSourceExtraIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val sourceName = "it_skill_toggle_src_$suffix"
    private val skillDirName = "it-toggle-skill-$suffix"

    private var sourceId: Long = -1
    private var zipPath: Path? = null

    /** Build a zip containing <skillDirName>/SKILL.md. */
    private fun buildSkillZip(): Path {
        zipPath?.let { return it }
        val dir = Files.createTempDirectory("it-skill-toggle-zip")
        val zip = dir.resolve("skills-$suffix.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("$skillDirName/SKILL.md"))
            out.write("# IT Toggle Skill\n\nSkill used by the toggle IT.\n".toByteArray())
            out.closeEntry()
        }
        zipPath = zip
        return zip
    }

    @Test
    @Order(1)
    fun `create ZIP skill source as toggle target`() {
        val body = mapOf(
            "name" to sourceName,
            "sourceType" to "ZIP",
            "sourceConfig" to mapOf("zipPath" to buildSkillZip().toString()),
            "description" to "IT toggle skill source",
            "status" to 1,
        )
        val data = assertOk(postJson("/api/admin/skill-sources", body))
        sourceId = data["id"].asLong()
        assertTrue(sourceId > 0)
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(2)
    fun `toggle skill source status off and on`() {
        assertOk(putJson("/api/admin/skill-sources/toggle/$sourceId?status=0"))
        var data = assertOk(getJson("/api/admin/skill-sources/$sourceId"))
        assertEquals(0, data["status"].asInt())

        assertOk(putJson("/api/admin/skill-sources/toggle/$sourceId?status=1"))
        data = assertOk(getJson("/api/admin/skill-sources/$sourceId"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(3)
    fun `toggle unknown skill source fails`() {
        assertErr(putJson("/api/admin/skill-sources/toggle/99999999?status=0"))
    }

    @Test
    @Order(4)
    fun `cleanup delete skill source`() {
        assertOk(deleteJson("/api/admin/skill-sources/$sourceId"))
        assertErr(getJson("/api/admin/skill-sources/$sourceId"))
        zipPath?.let { Files.deleteIfExists(it) }
    }
}
