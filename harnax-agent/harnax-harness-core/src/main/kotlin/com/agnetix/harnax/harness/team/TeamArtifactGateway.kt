package com.agnetix.harnax.harness.team

import com.agnetix.harnax.entity.TeamArtifact
import com.agnetix.harnax.mapper.TeamArtifactMapper
import io.minio.GetObjectArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Stores the files one team hands over between members that run in separate sandboxes.
 *
 * Two rules shape this interface (design section 8.3):
 * - a `fileId` is a reference, not a credential, so every read goes through [findOwned] and is answered
 *   only for the tenant and root session the caller is already acting for;
 * - the object key is decided here, never accepted from a model or a client.
 */
interface TeamArtifactGateway {

    /**
     * Uploads [data] and registers it. Returns the stored row, or throws — a publish that did not get as
     * far as a readable registration must not hand back a usable reference.
     */
    fun publish(
        tenantId: Long,
        rootSessionId: String,
        teamId: Long,
        memberAgentId: Long,
        childSessionId: String,
        fileName: String,
        mimeType: String,
        data: ByteArray,
    ): TeamArtifact

    /** Resolves [fileId] within one tenant and root session, or null when it is not theirs. */
    fun findOwned(fileId: String, tenantId: Long, rootSessionId: String): TeamArtifact?

    fun list(rootSessionId: String): List<TeamArtifact>

    fun read(objectKey: String): ByteArray?
}

/**
 * [TeamArtifactGateway] on the shared MinIO server, under a key prefix the legacy output-file route
 * cannot address.
 *
 * The existing `/api/output-files/{sessionType}/{sessionId}/{fileId}` download only requires a login for
 * `web` and `task`, and it whitelists those two types plus `channel` when it builds the object key. Team
 * artifacts therefore live under [OBJECT_PREFIX] in the same bucket: the old entry point cannot name such
 * a key even by accident, and team downloads go through their own object-level check instead. Reusing the
 * storage is not the same as reusing its permissions (design section 8.4).
 */
class MinioTeamArtifactGateway(
    private val minioClient: MinioClient,
    private val bucketName: String,
    private val artifactMapper: TeamArtifactMapper,
) : TeamArtifactGateway {

    private val log = LoggerFactory.getLogger(MinioTeamArtifactGateway::class.java)

    override fun publish(
        tenantId: Long,
        rootSessionId: String,
        teamId: Long,
        memberAgentId: Long,
        childSessionId: String,
        fileName: String,
        mimeType: String,
        data: ByteArray,
    ): TeamArtifact {
        // A new id per publish, so handing the same file in twice cannot overwrite the first version;
        // the lead picks which one goes to the next member.
        val fileId = UUID.randomUUID().toString()
        val objectKey = objectKey(tenantId, rootSessionId, fileId)
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .`object`(objectKey)
                .stream(data.inputStream(), data.size.toLong(), -1L)
                .contentType(mimeType)
                .build(),
        )

        val artifact = TeamArtifact().apply {
            this.fileId = fileId
            this.tenantId = tenantId
            sessionId = rootSessionId
            this.teamId = teamId
            this.memberAgentId = memberAgentId
            this.childSessionId = childSessionId
            this.fileName = fileName
            this.mimeType = mimeType
            sizeBytes = data.size.toLong()
            this.objectKey = objectKey
        }
        try {
            artifactMapper.insert(artifact)
        } catch (e: Exception) {
            // Without the row nobody can resolve the key, authorise a read, or clean it up later. Take
            // the object back rather than leave an addressable blob with no owner.
            log.warn(
                "[teamArtifact] Registration failed, removing uploaded object '{}': {}",
                objectKey,
                e.message,
            )
            runCatching {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketName).`object`(objectKey).build())
            }
            throw e
        }
        log.info(
            "[teamArtifact] Published '{}' ({} bytes) as fileId={} for session={}",
            fileName,
            data.size,
            fileId,
            rootSessionId,
        )
        return artifact
    }

    override fun findOwned(fileId: String, tenantId: Long, rootSessionId: String): TeamArtifact? = artifactMapper
        .selectByFileId(fileId)
        ?.takeIf { it.tenantId == tenantId && it.sessionId == rootSessionId }

    override fun list(rootSessionId: String): List<TeamArtifact> = artifactMapper.selectBySessionId(rootSessionId)

    override fun read(objectKey: String): ByteArray? = try {
        minioClient.getObject(
            GetObjectArgs.builder().bucket(bucketName).`object`(objectKey).build(),
        ).use { it.readBytes() }
    } catch (e: Exception) {
        log.warn("[teamArtifact] Failed to read object '{}': {}", objectKey, e.message)
        null
    }

    private fun objectKey(tenantId: Long, rootSessionId: String, fileId: String): String = "$OBJECT_PREFIX/$tenantId/$rootSessionId/$fileId"

    companion object {
        /** The prefix the legacy output-file download cannot name. */
        const val OBJECT_PREFIX = "team-artifacts"
    }
}
