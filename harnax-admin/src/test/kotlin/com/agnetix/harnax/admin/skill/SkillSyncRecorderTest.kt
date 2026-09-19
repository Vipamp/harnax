package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.dto.SkillInstallResponse
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

/**
 * Every write path used to end in a log line and a 200, so a day later nothing on the source said
 * whether its last sync had stored 12 skills or none. The four paths will not each re-derive that
 * answer, so this pins the one place that decides it.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SkillSyncRecorder Tests")
class SkillSyncRecorderTest {

    @Mock
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    private val objectMapper = ObjectMapper()

    private class Recorded(
        val status: String,
        val detail: JsonNode,
        val time: LocalDateTime,
        val repository: SkillRepository,
    )

    private fun record(install: SkillInstallResponse): Recorded = record(install, SkillRepository().apply { id = 7L })

    private fun record(
        install: SkillInstallResponse,
        repository: SkillRepository,
    ): Recorded {
        SkillSyncRecorder(skillRepositoryMapper).record(repository, install)

        val statusCaptor = argumentCaptor<String>()
        val detailCaptor = argumentCaptor<String>()
        val timeCaptor = argumentCaptor<LocalDateTime>()
        verify(skillRepositoryMapper).updateSyncResult(eq(7L), statusCaptor.capture(), detailCaptor.capture(), timeCaptor.capture())
        return Recorded(
            status = statusCaptor.firstValue,
            detail = objectMapper.readTree(detailCaptor.firstValue),
            time = timeCaptor.firstValue,
            repository = repository,
        )
    }

    /**
     * A sync that stored everything it could reach is still a good sync when the source dropped a
     * skill the database remembers. `stale` is a report the operator acts on, not an accident that
     * belongs in `failed` — and a source that now reads FAILED would hide the one fact that matters.
     */
    @Test
    fun `record should keep a sync successful when it only found a stale skill`() {
        val repository = SkillRepository().apply {
            id = 7L
            lastSyncStatus = "PARTIAL"
        }
        val install = SkillInstallResponse(installed = listOf("a"), stale = listOf("retired"))

        val recorded = record(install, repository)

        assertEquals("SUCCESS", recorded.status)
        assertEquals(listOf("retired"), recorded.detail.path("stale").map { it.asString() })
        assertEquals("SUCCESS", repository.lastSyncStatus)
    }

    @Test
    fun `record should mark a source whose fetch failed overall as FAILED`() {
        val install = SkillInstallResponse(sourceError = "git clone timed out after 180s")

        val recorded = record(install)

        assertEquals("FAILED", recorded.status)
        assertEquals("git clone timed out after 180s", recorded.detail.path("error").asString())
    }

    @Test
    fun `record should mark a source that produced nothing as EMPTY`() {
        val recorded = record(SkillInstallResponse())

        assertEquals("EMPTY", recorded.status)
        assertEquals(0, recorded.detail.path("saved").asInt())
    }

    /**
     * The loaders explain an empty source with a sentence of their own, and it is not a failed skill.
     * Reporting it as one would turn "nothing to install" into "partially installed" and blame a
     * skill that does not exist, while the sentence itself would disappear behind a fake name.
     */
    @Test
    fun `record should keep a source empty but say why`() {
        val install = SkillInstallResponse(
            emptyReason = "No SKILL.md found in the archive root",
        )

        val recorded = record(install)

        assertEquals("EMPTY", recorded.status)
        assertEquals("No SKILL.md found in the archive root", recorded.detail.path("error").asString())
        assertEquals(0, recorded.detail.path("failed").size())
    }

    /**
     * Nothing stored is nothing stored, whichever way the source ran out of it: a directory whose
     * `SKILL.md` failed to parse must not make the source read as partially synced.
     */
    @Test
    fun `record should prefer EMPTY over PARTIAL when no skill was stored`() {
        val install = SkillInstallResponse(
            failed = listOf(SkillInstallResponse.FailedSkill("broken-dir", "SKILL.md is empty")),
        )

        assertEquals("EMPTY", record(install).status)
    }

    @Test
    fun `record should mark a source that lost some skills as PARTIAL`() {
        val install = SkillInstallResponse(
            installed = listOf("good"),
            failed = listOf(SkillInstallResponse.FailedSkill("broken-dir", "SKILL.md could not be parsed")),
        )

        val recorded = record(install)

        assertEquals("PARTIAL", recorded.status)
        assertEquals("broken-dir", recorded.detail.path("failed")[0].path("name").asString())
        assertFalse(recorded.detail.hasNonNull("error"))
    }

    @Test
    fun `record should mark a source that stored everything as SUCCESS`() {
        val install = SkillInstallResponse(
            installed = listOf("a"),
            updated = listOf("b"),
            flagged = listOf(SkillInstallResponse.FlaggedSkill("b", listOf("SKILL.md:recursive-root-delete"))),
        )

        val recorded = record(install)

        assertEquals("SUCCESS", recorded.status)
        assertEquals(2, recorded.detail.path("saved").asInt())
        assertEquals(listOf("a"), recorded.detail.path("installed").map { it.asString() })
        assertEquals(listOf("b"), recorded.detail.path("updated").map { it.asString() })
        assertEquals(
            listOf("SKILL.md:recursive-root-delete"),
            recorded.detail.path("flagged")[0].path("reasons").map { it.asString() },
        )
    }

    @Test
    fun `record should stamp the sync time`() {
        val recorded = record(SkillInstallResponse(installed = listOf("a")))

        assertNotNull(recorded.time)
    }

    /**
     * The create and install endpoints answer with a response mapped from the row object they were
     * given. If only the database learned about the sync, a source the server has just marked FAILED
     * goes back to the caller as "never synced".
     */
    @Test
    fun `record should mirror the result onto the source object`() {
        val install = SkillInstallResponse(
            installed = listOf("a"),
            sourceError = "clone refused",
        )

        val recorded = record(install)

        assertEquals("FAILED", recorded.repository.lastSyncStatus)
        assertEquals(recorded.time, recorded.repository.lastSyncTime)
        assertEquals(recorded.detail, objectMapper.readTree(recorded.repository.lastSyncDetail))
    }
}
