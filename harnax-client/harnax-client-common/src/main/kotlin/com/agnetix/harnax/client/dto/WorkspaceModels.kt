package com.agnetix.harnax.client.dto

/**
 * File information from workspace listing.
 */
data class FileInfo(
    val name: String,
    val path: String,
    val type: String,
    val size: Long = 0,
    val lastModified: Long = 0,
)

/**
 * File content from workspace read.
 */
data class FileContent(
    val name: String,
    val path: String,
    val content: String,
    val type: String = "text",
    val size: Long = 0,
)

/**
 * Workspace status for a session.
 */
data class WorkspaceStatus(
    val active: Boolean = false,
    val sessionId: String? = null,
    val workspacePath: String? = null,
)

/**
 * Upload result from workspace file upload.
 */
data class UploadResult(
    val fileName: String,
    val path: String,
    val size: Long = 0,
)

/**
 * Download result from workspace file download.
 *
 * @property bytes       Raw file bytes
 * @property contentType MIME type of the file
 * @property fileName    Original file name
 */
data class DownloadResult(
    val bytes: ByteArray,
    val contentType: String,
    val fileName: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadResult) return false
        return bytes.contentEquals(other.bytes) &&
            contentType == other.contentType &&
            fileName == other.fileName
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}
