package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.config.AdminMinioProperties
import com.agnetix.harnax.mapper.TeamArtifactMapper
import io.minio.MinioClient
import io.minio.RemoveObjectArgs
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Removes the artifacts a session produced: the MinIO object first, then the row that points at it.
 *
 * Session deletion is the only place a team artifact stops being reachable. A team cannot be deleted
 * while it still has sessions ([TeamServiceImpl.deleteTeam] refuses, naming them), so this is the
 * path that keeps both ends from leaking — before it existed, both the object and its metadata row
 * outlived every session that could ever have served them.
 *
 * The order matters because object storage is not transactional: deleting the object first means a
 * database failure leaves a row pointing at nothing, which a retry repairs (MinIO treats a repeated
 * delete as a no-op). The reverse order would leave objects no row names, which nothing can ever find
 * again. A single object that cannot be deleted keeps its row and is logged, so the loss stays
 * visible instead of turning into a broken download.
 *
 * With MinIO disabled the rows are kept and a warning is logged rather than half-cleaning: the object
 * is still there, so dropping the row would strand it for good.
 */
@Component
class TeamArtifactCleaner(
    private val teamArtifactMapper: TeamArtifactMapper,
    private val minioClientProvider: ObjectProvider<MinioClient>,
    private val minioPropertiesProvider: ObjectProvider<AdminMinioProperties>,
) {
    private val log = LoggerFactory.getLogger(TeamArtifactCleaner::class.java)

    /** Deletes every artifact of one root session; returns how many rows were removed. */
    fun deleteForSession(sessionId: String): Int {
        val artifacts = teamArtifactMapper.selectBySessionId(sessionId)
        if (artifacts.isEmpty()) {
            return 0
        }

        val minioClient = minioClientProvider.ifAvailable
        val bucket = minioPropertiesProvider.ifAvailable?.outputBucket
        if (minioClient == null || bucket.isNullOrBlank()) {
            log.warn(
                "[artifacts] MinIO is not configured, keeping {} artifact row(s) of session {} so their objects stay reachable",
                artifacts.size,
                sessionId,
            )
            return 0
        }

        var removed = 0
        for (artifact in artifacts) {
            try {
                minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).`object`(artifact.objectKey).build(),
                )
            } catch (e: Exception) {
                log.warn(
                    "[artifacts] Failed to delete object {} of session {}: {}",
                    artifact.objectKey,
                    sessionId,
                    e.message,
                )
                continue
            }
            removed += teamArtifactMapper.deleteById(artifact.id)
        }

        log.info("[artifacts] Removed {} of {} artifact(s) of session {}", removed, artifacts.size, sessionId)
        return removed
    }
}
