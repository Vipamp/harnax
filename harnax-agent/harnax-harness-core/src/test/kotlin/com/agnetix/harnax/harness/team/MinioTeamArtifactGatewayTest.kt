package com.agnetix.harnax.harness.team

import com.agnetix.harnax.entity.TeamArtifact
import com.agnetix.harnax.mapper.TeamArtifactMapper
import io.minio.GetObjectArgs
import io.minio.GetObjectResponse
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream

/**
 * The artifact store's two promises (design 8.3): a `fileId` is only a reference, so ownership is decided
 * by the stored row and never by the caller; and the object key is chosen here, so a model cannot name an
 * object that belongs to somebody else.
 */
class MinioTeamArtifactGatewayTest {

    private val bucket = "harnax"
    private val tenantId = 1L
    private val rootSessionId = "web-root"
    private val minioClient = mock<MinioClient>()
    private val mapper = mock<TeamArtifactMapper>()
    private lateinit var gateway: MinioTeamArtifactGateway

    @BeforeEach
    fun setUp() {
        gateway = MinioTeamArtifactGateway(minioClient, bucket, mapper)
    }

    private fun publish(
        fileName: String = "report.csv",
        data: ByteArray = "x,y\n1,2\n".toByteArray(),
    ): TeamArtifact = gateway.publish(
        tenantId = tenantId,
        rootSessionId = rootSessionId,
        teamId = 7L,
        memberAgentId = 2L,
        childSessionId = "team-$rootSessionId-m2",
        fileName = fileName,
        mimeType = "text/csv",
        data = data,
    )

    private fun row(
        fileId: String = "f-1",
        tenant: Long = tenantId,
        session: String = rootSessionId,
    ): TeamArtifact = TeamArtifact().apply {
        this.fileId = fileId
        tenantId = tenant
        sessionId = session
        teamId = 7L
        memberAgentId = 2L
        childSessionId = "team-$session-m2"
        fileName = "report.csv"
        mimeType = "text/csv"
        sizeBytes = 128L
        objectKey = "team-artifacts/$tenant/$session/$fileId"
    }

    private fun putArgs(): PutObjectArgs {
        val captor = argumentCaptor<PutObjectArgs>()
        verify(minioClient).putObject(captor.capture())
        return captor.firstValue
    }

    private fun storedRow(): TeamArtifact {
        val captor = argumentCaptor<TeamArtifact>()
        verify(mapper).insert(captor.capture())
        return captor.firstValue
    }

    @Nested
    inner class Publish {

        @Test
        fun `the object key is built here from tenant, root session and a fresh id`() {
            val stored = publish()

            assertEquals("team-artifacts/$tenantId/$rootSessionId/${stored.fileId}", putArgs().`object`())
        }

        @Test
        fun `a file name never reaches into the object key`() {
            val stored = publish(fileName = ".." + "/etc/passwd")

            val key = putArgs().`object`()
            assertTrue(key.startsWith("team-artifacts/"), key)
            assertFalse(key.contains(".."), key)
            assertEquals("team-artifacts/$tenantId/$rootSessionId/${stored.fileId}", key)
        }

        @Test
        fun `the registered row is the row the object was written under`() {
            val data = "x,y\n1,2\n".toByteArray()

            val stored = publish(data = data)

            val registered = storedRow()
            assertEquals(putArgs().`object`(), registered.objectKey)
            assertEquals(stored.fileId, registered.fileId)
            assertEquals(data.size.toLong(), registered.sizeBytes)
            assertEquals(rootSessionId, registered.sessionId, "the root session owns the artifact, not the child run")
            assertEquals(tenantId, registered.tenantId)
            assertEquals("report.csv", registered.fileName)
            assertEquals("text/csv", registered.mimeType)
        }

        @Test
        fun `the same file published twice gets two references`() {
            val first = publish()
            val second = publish()

            assertNotEquals(first.fileId, second.fileId)
            val keys = argumentCaptor<PutObjectArgs>()
            verify(minioClient, times(2)).putObject(keys.capture())
            assertNotEquals(keys.firstValue.`object`(), keys.secondValue.`object`(), "a re-publish must not overwrite")
        }

        @Test
        fun `a failed registration takes the uploaded object back`() {
            whenever(mapper.insert(any())).thenThrow(RuntimeException("db down"))

            assertThrows(RuntimeException::class.java) { publish() }

            val removed = argumentCaptor<RemoveObjectArgs>()
            verify(minioClient).removeObject(removed.capture())
            assertEquals(putArgs().`object`(), removed.firstValue.`object`())
            assertEquals(bucket, removed.firstValue.bucket())
        }

        @Test
        fun `the bucket and content type come from configuration`() {
            publish()

            assertEquals(bucket, putArgs().bucket())
            assertEquals("text/csv", putArgs().contentType())
        }
    }

    @Nested
    inner class Ownership {

        @Test
        fun `another tenant cannot resolve a reference it was handed`() {
            whenever(mapper.selectByFileId("f-1")).thenReturn(row())

            assertNotNull(gateway.findOwned("f-1", tenantId, rootSessionId))
            assertNull(gateway.findOwned("f-1", 999L, rootSessionId))
        }

        @Test
        fun `another session cannot resolve a reference it was handed`() {
            whenever(mapper.selectByFileId("f-1")).thenReturn(row())

            assertNull(gateway.findOwned("f-1", tenantId, "someone-else's-session"))
        }

        @Test
        fun `an unknown reference resolves to nothing`() {
            whenever(mapper.selectByFileId(any())).thenReturn(null)

            assertNull(gateway.findOwned("nope", tenantId, rootSessionId))
        }

        @Test
        fun `the listing is scoped to the root session`() {
            whenever(mapper.selectBySessionId(rootSessionId)).thenReturn(listOf(row()))

            assertEquals(1, gateway.list(rootSessionId).size)

            verify(mapper).selectBySessionId(eq(rootSessionId))
            verify(mapper, never()).selectBySessionId(eq("other"))
        }
    }

    @Nested
    inner class Read {

        @Test
        fun `read returns the object bytes under the key it stored`() {
            val payload = "x,y\n1,2\n".toByteArray()
            val response = responseOf(payload)
            whenever(minioClient.getObject(any())).thenReturn(response)

            assertEquals(payload.toList(), gateway.read("team-artifacts/1/$rootSessionId/f-1")?.toList())

            val captor = argumentCaptor<GetObjectArgs>()
            verify(minioClient).getObject(captor.capture())
            assertEquals("team-artifacts/1/$rootSessionId/f-1", captor.firstValue.`object`())
            assertEquals(bucket, captor.firstValue.bucket())
        }

        @Test
        fun `a missing object reads as nothing instead of throwing`() {
            whenever(minioClient.getObject(any())).thenThrow(RuntimeException("The specified key does not exist"))

            assertNull(gateway.read("team-artifacts/1/$rootSessionId/gone"))
        }

        /**
         * A real [GetObjectResponse] rather than a mock: its `read(byte[], int, int)` comes from
         * `FilterInputStream`, and a stub that fails to match returns 0, which Kotlin's `readBytes`
         * treats as progress and loops on forever.
         */
        private fun responseOf(payload: ByteArray): GetObjectResponse = GetObjectResponse(
            Headers.Builder().build(),
            bucket,
            "team-artifacts/$tenantId/$rootSessionId/f-1",
            null,
            ByteArrayInputStream(payload),
        )
    }
}
