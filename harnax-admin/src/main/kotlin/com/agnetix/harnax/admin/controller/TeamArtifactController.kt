package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.config.AdminMinioProperties
import com.agnetix.harnax.admin.dto.TeamArtifactResponse
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.TeamArtifactMapper
import io.minio.GetObjectArgs
import io.minio.MinioClient
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Team artifact list and download for the chat UI.
 *
 * This is the second file route in the product, and it exists because the first one cannot carry team
 * files: `/api/output-files/{sessionType}/…` is open to any logged-in user for `web` and `task`, and it
 * builds the object key out of the caller's own path variables. Here the key always comes from the
 * `team_artifact` row, and that row is only ever used when the caller owns the session it belongs to
 * (design section 8.4).
 *
 * A member's run cannot reach this endpoint at all — it has no credential for admin — so fetching an
 * artifact into a sandbox stays a server-side read inside agent-service.
 */
@RestController
@RequestMapping("/api/admin/team-artifacts")
@Tag(name = "Team Artifacts", description = "List and download files published by team members")
@ConditionalOnProperty(prefix = "minio", name = ["enabled"], havingValue = "true")
class TeamArtifactController(
    private val minioClient: MinioClient,
    private val minioProperties: AdminMinioProperties,
    private val teamArtifactMapper: TeamArtifactMapper,
    private val sessionMapper: SessionMapper,
    private val jwtUtil: JwtUtil,
) {
    private val log = LoggerFactory.getLogger(TeamArtifactController::class.java)

    companion object {
        // UUID pattern, so a fileId can never name a path (it is also the shape agent-service mints)
        private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        private val SESSION_ID_PATTERN = Regex("^[a-zA-Z0-9_-]{1,128}$")
    }

    @GetMapping
    @Operation(summary = "List artifacts of a team session", description = "Files the members of one team session have published, newest first by publication order")
    fun listArtifacts(
        @Parameter(description = "Root team session ID") @RequestParam("sessionId") sessionId: String,
    ): ResultVo<List<TeamArtifactResponse>> {
        val session = ownedTeamSession(sessionId) ?: return ResultVo.error("No permission to access this session")
        val artifacts = teamArtifactMapper.selectBySessionId(session.sessionId)
            // The row's own tenant is what scopes the list; a session that changed tenant mid-life must
            // not surface the previous tenant's files.
            .filter { it.tenantId == session.tenantId }
        return ResultVo.success(artifacts.map { TeamArtifactResponse.of(it) })
    }

    @GetMapping("/{fileId}")
    @Operation(summary = "Download a team artifact", description = "Proxies one published member file from MinIO after checking the caller owns its session")
    fun download(
        @Parameter(description = "Artifact reference returned by team_artifact_publish") @PathVariable fileId: String,
        @Parameter(description = "Root team session ID the artifact was published into") @RequestParam("sessionId") sessionId: String,
    ): ResponseEntity<ByteArray> {
        if (!UUID_PATTERN.matches(fileId)) return ResponseEntity.badRequest().build()
        val session = ownedTeamSession(sessionId) ?: return ResponseEntity.status(403).build()
        val artifact = teamArtifactMapper.selectByFileId(fileId) ?: return ResponseEntity.notFound().build()
        if (artifact.sessionId != session.sessionId || artifact.tenantId != session.tenantId) {
            // A real fileId from another session is a 404 rather than a 403: this endpoint must not
            // confirm that someone else's reference exists.
            log.warn("[teamArtifact] fileId={} belongs to session {}, not the requested session {}", fileId, artifact.sessionId, session.sessionId)
            return ResponseEntity.notFound().build()
        }
        val bytes = readFromMinio(artifact.objectKey) ?: return ResponseEntity.notFound().build()
        log.info(
            "[teamArtifact] User '{}' downloaded '{}' ({} bytes) of session {}",
            session.creator,
            artifact.fileName,
            bytes.size,
            session.sessionId,
        )
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${sanitizeFileName(artifact.fileName)}\"")
            .contentType(mediaTypeOf(artifact.mimeType))
            .contentLength(bytes.size.toLong())
            .body(bytes)
    }

    /**
     * The session the request may act on: a live team session created by the caller, or null.
     *
     * Returning null rather than throwing keeps both callers' status codes their own choice, and puts the
     * one rule the whole controller rests on in a single place: a `fileId` proves nothing by itself.
     */
    private fun ownedTeamSession(sessionId: String): Session? {
        if (!SESSION_ID_PATTERN.matches(sessionId)) return null
        val currentUsername = runCatching { UserContextUtil.getCurrentUsername(jwtUtil) }.getOrNull()
            ?: return null
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1) ?: return null
        if (session.teamId == null) {
            log.warn("[teamArtifact] Session {} is not a team session", sessionId)
            return null
        }
        if (session.creator != currentUsername) {
            log.warn(
                "[teamArtifact] Permission denied: user '{}' asked for artifacts of session {} (owner: '{}')",
                currentUsername,
                sessionId,
                session.creator,
            )
            return null
        }
        return session
    }

    /** Stored MIME types come from a model-supplied file name, so a bad one falls back to a download. */
    private fun mediaTypeOf(mimeType: String): MediaType = runCatching { MediaType.parseMediaType(mimeType) }
        .getOrDefault(MediaType.APPLICATION_OCTET_STREAM)

    /**
     * Sanitize file name for Content-Disposition header.
     * Removes quotes, CR/LF, and semicolons to prevent header injection.
     */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace("\"", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace(";", "")
            .trim()
        return cleaned.ifEmpty { "download" }
    }

    private fun readFromMinio(objectKey: String): ByteArray? = try {
        minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(minioProperties.outputBucket)
                .`object`(objectKey)
                .build(),
        ).use { it.readBytes() }
    } catch (e: Exception) {
        log.warn("[teamArtifact] Failed to read object '{}': {}", objectKey, e.message)
        null
    }
}
