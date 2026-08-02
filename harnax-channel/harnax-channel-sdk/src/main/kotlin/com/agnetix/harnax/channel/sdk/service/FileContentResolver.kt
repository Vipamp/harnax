package com.agnetix.harnax.channel.sdk.service

import com.agnetix.harnax.agent.protocol.FileAttachment

/**
 * Resolves file content from storage for channel delivery.
 *
 * Implementations can read from MinIO directly (internal network),
 * or fall back to HTTP download from admin proxy.
 */
fun interface FileContentResolver {
    /**
     * Resolve file bytes from the given attachment.
     *
     * @param attachment File attachment with objectKey and/or url
     * @return File bytes, or null if resolution failed
     */
    fun resolve(attachment: FileAttachment): ByteArray?
}
