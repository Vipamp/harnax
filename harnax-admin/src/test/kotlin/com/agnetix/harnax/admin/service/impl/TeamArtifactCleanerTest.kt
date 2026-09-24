package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.config.AdminMinioProperties
import com.agnetix.harnax.entity.TeamArtifact
import com.agnetix.harnax.mapper.TeamArtifactMapper
import io.minio.MinioClient
import io.minio.RemoveObjectArgs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness
import org.springframework.beans.factory.ObjectProvider

/**
 * TeamArtifactCleaner Unit Tests
 *
 * The ordering it enforces is the whole point: the object goes before the row that names it, so a
 * database failure leaves something a retry can repair instead of an object nothing can find.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamArtifactCleanerTest {

    @Mock
    private lateinit var teamArtifactMapper: TeamArtifactMapper

    @Mock
    private lateinit var minioClient: MinioClient

    @Mock
    private lateinit var minioClientProvider: ObjectProvider<MinioClient>

    @Mock
    private lateinit var minioPropertiesProvider: ObjectProvider<AdminMinioProperties>

    private fun cleaner() = TeamArtifactCleaner(teamArtifactMapper, minioClientProvider, minioPropertiesProvider)

    private fun minioAvailable() {
        `when`(minioClientProvider.ifAvailable).thenReturn(minioClient)
        `when`(minioPropertiesProvider.ifAvailable).thenReturn(AdminMinioProperties().apply { outputBucket = "harnax-output" })
    }

    private fun artifact(id: Long, objectKey: String) = TeamArtifact().apply {
        this.id = id
        fileId = "f-$id"
        sessionId = SESSION_ID
        this.objectKey = objectKey
    }

    @Test
    @DisplayName("deleteForSession - 没有产物时什么都不做")
    fun `deleteForSession should do nothing without artifacts`() {
        `when`(teamArtifactMapper.selectBySessionId(SESSION_ID)).thenReturn(emptyList())
        minioAvailable()

        assertEquals(0, cleaner().deleteForSession(SESSION_ID))
        verify(minioClient, never()).removeObject(any())
        verify(teamArtifactMapper, never()).deleteById(anyLong())
    }

    @Test
    @DisplayName("deleteForSession - 每个产物先删对象再删行")
    fun `deleteForSession should delete every object before its row`() {
        val first = artifact(1L, "team/root-a.bin")
        val second = artifact(2L, "team/root-b.bin")
        `when`(teamArtifactMapper.selectBySessionId(SESSION_ID)).thenReturn(listOf(first, second))
        `when`(teamArtifactMapper.deleteById(anyLong())).thenReturn(1)
        minioAvailable()

        assertEquals(2, cleaner().deleteForSession(SESSION_ID))

        val keys = argumentCaptor<RemoveObjectArgs>()
        verify(minioClient, org.mockito.Mockito.times(2)).removeObject(keys.capture())
        assertEquals(listOf("team/root-a.bin", "team/root-b.bin"), keys.allValues.map { it.`object`() })

        val order = inOrder(minioClient, teamArtifactMapper)
        order.verify(minioClient).removeObject(any())
        order.verify(teamArtifactMapper).deleteById(1L)
    }

    @Test
    @DisplayName("deleteForSession - MinIO 未配置时保留行，不半清理")
    fun `deleteForSession should keep rows when MinIO is not configured`() {
        `when`(teamArtifactMapper.selectBySessionId(SESSION_ID)).thenReturn(listOf(artifact(1L, "team/a.bin")))
        `when`(minioClientProvider.ifAvailable).thenReturn(null)

        assertEquals(0, cleaner().deleteForSession(SESSION_ID))

        verify(teamArtifactMapper, never()).deleteById(anyLong())
    }

    @Test
    @DisplayName("deleteForSession - 对象删不掉时保留它的行，而不是留下指向空对象的行")
    fun `deleteForSession should keep the row whose object could not be deleted`() {
        `when`(teamArtifactMapper.selectBySessionId(SESSION_ID)).thenReturn(listOf(artifact(1L, "team/stuck.bin")))
        `when`(minioClient.removeObject(any<RemoveObjectArgs>()))
            .thenThrow(RuntimeException("simulated MinIO failure"))
        minioAvailable()

        assertEquals(0, cleaner().deleteForSession(SESSION_ID))

        verify(teamArtifactMapper, never()).deleteById(anyLong())
    }

    companion object {
        private const val SESSION_ID = "team-web-1"
    }
}
