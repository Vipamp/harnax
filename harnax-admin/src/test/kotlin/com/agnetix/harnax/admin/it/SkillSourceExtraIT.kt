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
import kotlin.test.assertTrue

/**
 * Skill source toggle regression: /api/admin/skill-sources/toggle/{id}
 *
 * SkillSourceCrudIT covers every other /api/admin/skill-sources endpoint (page, detail, create,
 * update, delete, fetch, install, upload); the status toggle is the one it misses, so it is
 * exercised here against a ZIP source that needs no network access.
 *
 * The two boundary cases belong here rather than in a unit test because both are about what the
 * endpoint stores: an out-of-range status would silently read as "disabled" forever, since every
 * consumer compares with `== 1`, and the builtin repository is shared by all tenants.
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
    fun `upload a zip source to toggle`() {
        val data = assertOk(uploadSkillZip(buildSkillZip(), sourceName))
        val source = data["source"]
        assertNotNull(source, "upload should answer with the source it created: $data")
        sourceId = source["id"].asLong()
        assertTrue(sourceId > 0)
        assertEquals(1, source["status"].asInt())
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
    fun `toggle with an out of range status is refused and stores nothing`() {
        val node = putJson("/api/admin/skill-sources/toggle/$sourceId?status=99")
        assertErr(node)
        assertTrue(
            node["message"].asText().contains("must be 0 (disabled) or 1 (enabled)"),
            "the refusal should say what the two states are: ${node["message"].asText()}",
        )
        // A stored 99 would read as disabled everywhere without ever failing loudly, and the UI
        // switch could no longer bring the row back
        assertEquals(1, assertOk(getJson("/api/admin/skill-sources/$sourceId"))["status"].asInt())
    }

    @Test
    @Order(4)
    fun `toggle unknown skill source fails`() {
        assertErr(putJson("/api/admin/skill-sources/toggle/99999999?status=0"))
    }

    @Test
    @Order(5)
    fun `toggle the builtin repository is refused`() {
        val builtin = findInPage("/api/admin/skill-sources/page", "name=builtin-cli-skills") {
            it["name"]?.asText() == "builtin-cli-skills"
        }
        assertNotNull(builtin, "the platform seeds the builtin repository, so it should be listed")

        val node = putJson("/api/admin/skill-sources/toggle/${builtin["id"].asLong()}?status=0")
        assertErr(node)
        assertTrue(
            node["message"].asText().contains("read-only"),
            "the refusal should say the repository is read-only: ${node["message"].asText()}",
        )
        // Switching it off would hide every CLI skill from every tenant at once
        assertEquals(1, builtin["status"].asInt())
    }

    @Test
    @Order(6)
    fun `cleanup delete skill source`() {
        assertOk(deleteJson("/api/admin/skill-sources/$sourceId"))
        assertErr(getJson("/api/admin/skill-sources/$sourceId"))
        zipPath?.let { Files.deleteIfExists(it) }
    }
}
