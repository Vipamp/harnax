package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.dto.SkillInstallResponse
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

/**
 * Writes the outcome of one sync onto its source.
 *
 * Every install path already computed a complete report and then handed it to a log line and a
 * browser toast, so the answer to "did this source last sync properly?" was gone as soon as the tab
 * closed. The four write paths end here instead of each deriving the state on their own — the rule
 * about what counts as a failed sync is the part that has to stay single.
 */
@Service
class SkillSyncRecorder(
    private val skillRepositoryMapper: SkillRepositoryMapper,
) {

    /**
     * Stores the report on the row and mirrors it onto [repository].
     *
     * The mirror is not a convenience: callers answer with a response mapped from this same object,
     * and a source the server has just marked FAILED must not go back as "never synced".
     */
    fun record(repository: SkillRepository, install: SkillInstallResponse, syncTime: LocalDateTime = LocalDateTime.now()) {
        val status = when {
            // Checked before the counters: a fetch that never produced anything would otherwise read
            // as an empty source, which is a different problem with a different fix
            install.sourceError != null -> FAILED
            // Nothing stored is nothing stored, whether the source held no skill at all or every
            // candidate failed on the way in. `PARTIAL` claims a part, and a part of zero is zero
            install.savedCount == 0 -> EMPTY
            install.failedCount > 0 -> PARTIAL
            else -> SUCCESS
        }
        val detail = detailMapper.writeValueAsString(detailJson(install))

        repository.lastSyncTime = syncTime
        repository.lastSyncStatus = status
        repository.lastSyncDetail = detail

        skillRepositoryMapper.updateSyncResult(repository.id, status, detail, syncTime)
        log.info("Source {} sync recorded as {}: {}", repository.id, status, install.summary)
    }

    private fun detailJson(install: SkillInstallResponse): Map<String, Any> = buildMap {
        put("saved", install.savedCount)
        put("installed", install.installed)
        put("updated", install.updated)
        put("failed", install.failed.map { mapOf("name" to it.name, "reason" to it.reason) })
        put("flagged", install.flagged.map { mapOf("name" to it.name, "reasons" to it.reasons) })
        // Names the source dropped but the database still holds: not a failure and not a deletion,
        // just the half of the disagreement nobody would otherwise ever see
        put("stale", install.stale)
        // One reason field whatever the cause: the badge already says whether the source broke or
        // simply holds nothing, and the UI shows a single sentence under it either way
        (install.sourceError ?: install.emptyReason)?.let { put("error", it) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SkillSyncRecorder::class.java)

        const val SUCCESS = "SUCCESS"
        const val PARTIAL = "PARTIAL"
        const val FAILED = "FAILED"
        const val EMPTY = "EMPTY"

        private val detailMapper = ObjectMapper()

        /**
         * A stored report as an API consumer should see it, sitting next to the code that writes it
         * so the two cannot disagree about the shape.
         *
         * Unreadable rather than absent is the only failure mode here (a value cut short by a column
         * too small, a row written by hand). Answering null keeps the source list rendering its
         * badge instead of failing on one bad row.
         */
        fun forApi(detail: String?): Map<String, Any>? {
            if (detail.isNullOrBlank()) return null
            return try {
                detailMapper.readValue(detail, object : TypeReference<Map<String, Any>>() {})
            } catch (e: Exception) {
                log.warn("Failed to parse a stored sync report for the API", e)
                null
            }
        }
    }
}
