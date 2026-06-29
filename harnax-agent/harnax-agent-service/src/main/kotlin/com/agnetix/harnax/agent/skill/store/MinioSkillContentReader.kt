package com.agnetix.harnax.agent.skill.store

import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.StatObjectArgs
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["skill.storage.type"], havingValue = "minio")
class MinioSkillContentReader(
    private val minioClient: MinioClient,
    @Value("\${skill.storage.bucket:harnax-skills}") private val bucket: String
) : SkillContentReader {

    private val log = LoggerFactory.getLogger(MinioSkillContentReader::class.java)

    override fun load(storagePath: String): SkillContentData {
        val skillmd = minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(bucket)
                .object("$storagePath/SKILL.md")
                .build()
        ).use { it.readBytes() }.toString(Charsets.UTF_8)

        val resources = loadResources("$storagePath/resources/")
        return SkillContentData(skillmd, resources)
    }

    override fun exists(storagePath: String): Boolean {
        return try {
            minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(bucket)
                    .object("$storagePath/SKILL.md")
                    .build()
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadResources(prefix: String): Map<String, String> {
        val resources = mutableMapOf<String, String>()
        val objects = minioClient.listObjects(
            ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(prefix)
                .recursive(true)
                .build()
        )

        objects.forEach { result ->
            val item = result.get()
            val objectName = item.objectName()
            val relativePath = objectName.removePrefix(prefix)
            if (relativePath.isNotBlank()) {
                val content = minioClient.getObject(
                    GetObjectArgs.builder()
                        .bucket(bucket)
                        .`object`(objectName)
                        .build()
                ).use { it.readBytes() }.toString(Charsets.UTF_8)
                resources[relativePath] = content
            }
        }
        return resources
    }
}
