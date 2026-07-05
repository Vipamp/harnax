package com.agnetix.harnax.admin.skill.store

import io.minio.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

@Component
@ConditionalOnProperty(name = ["skill.storage.type"], havingValue = "minio")
class MinioContentStore(
    private val minioClient: MinioClient,
    @Value("\${skill.storage.bucket:harnax-skills}") private val bucket: String,
) : SkillContentStore {

    private val log = LoggerFactory.getLogger(MinioContentStore::class.java)

    init {
        ensureBucket()
    }

    override fun save(repositoryId: Long, skillName: String, content: SkillContent): String {
        val prefix = "$repositoryId/$skillName"

        val skillmdBytes = content.skillmd.toByteArray()
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucket)
                .`object`("$prefix/SKILL.md")
                .stream(ByteArrayInputStream(skillmdBytes), skillmdBytes.size.toLong(), -1L)
                .contentType("text/markdown")
                .build(),
        )

        content.resources.forEach { (path, bytes) ->
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .`object`("$prefix/resources/$path")
                    .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1L)
                    .build(),
            )
        }

        log.debug("Saved skill content to MinIO: {}/{}", bucket, prefix)
        return prefix
    }

    override fun load(storagePath: String): SkillContent {
        val skillmd = minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(bucket)
                .`object`("$storagePath/SKILL.md")
                .build(),
        ).use { String(it.readBytes(), Charsets.UTF_8) }

        val resources = loadResources("$storagePath/resources/")
        return SkillContent(skillmd, resources)
    }

    override fun delete(storagePath: String) {
        val objects = minioClient.listObjects(
            ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix("$storagePath/")
                .recursive(true)
                .build(),
        )

        objects.forEach { result ->
            val item = result.get()
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .`object`(item.objectName())
                    .build(),
            )
        }
        log.debug("Deleted skill content from MinIO: {}/{}", bucket, storagePath)
    }

    override fun exists(storagePath: String): Boolean = try {
        minioClient.statObject(
            StatObjectArgs.builder()
                .bucket(bucket)
                .`object`("$storagePath/SKILL.md")
                .build(),
        )
        true
    } catch (e: Exception) {
        false
    }

    private fun loadResources(prefix: String): Map<String, ByteArray> {
        val resources = mutableMapOf<String, ByteArray>()
        val objects = minioClient.listObjects(
            ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(prefix)
                .recursive(true)
                .build(),
        )

        objects.forEach { result ->
            val item = result.get()
            val objectName = item.objectName()
            val relativePath = objectName.removePrefix(prefix)
            if (relativePath.isNotBlank()) {
                val bytes = minioClient.getObject(
                    GetObjectArgs.builder()
                        .bucket(bucket)
                        .`object`(objectName)
                        .build(),
                ).use { it.readBytes() }
                resources[relativePath] = bytes
            }
        }
        return resources
    }

    private fun ensureBucket() {
        val exists = minioClient.bucketExists(
            BucketExistsArgs.builder().bucket(bucket).build(),
        )
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
            log.info("Created MinIO bucket: {}", bucket)
        }
    }
}
