package com.agnetix.harnax.harness.output

import io.minio.MinioClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Contract C1 seen from the output store, which is the consumer that never parses a task session id at all.
 *
 * It sorts a session into `task/` or `web/` by prefix and keeps the id verbatim underneath, so one more
 * segment must be invisible here. That was reasoned rather than tested when C1 was written; these two cases
 * are what make it a fact — a four-segment id still lands in `task/`, and still lands there whole, because a
 * consumer that tried to read the tail as part of the path would have truncated it.
 *
 * `retrieve` is not exercised: the object key is the only thing here that depends on the session id.
 */
class MinioOutputFileStoreSessionTypeTest {

    private val store = MinioOutputFileStore(
        minioClient = mock<MinioClient>(),
        adminBaseUrl = "http://admin.local:8080",
    )

    @Test
    fun `a four-segment task session id is stored as a task session under its whole id`() {
        val sessionId = "task-123-45-6f0b1a2c3d4e5f60718293a4b5c6d7e8"

        val stored = store.persist(sessionId, "report.md", "hello".toByteArray(), "text/markdown")

        assertTrue(stored.objectKey.startsWith("task/$sessionId/"), "object key lost its type or its id: ${stored.objectKey}")
        assertTrue(stored.url.contains("/api/output-files/task/$sessionId/"), "download URL: ${stored.url}")
    }

    @Test
    fun `a web session id is still stored as a web session`() {
        val stored = store.persist("web-abc123", "report.md", "hello".toByteArray(), "text/markdown")

        assertTrue(stored.objectKey.startsWith("web/web-abc123/"), "object key: ${stored.objectKey}")
    }
}
