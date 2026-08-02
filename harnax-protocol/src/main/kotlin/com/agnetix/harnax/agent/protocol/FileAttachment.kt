package com.agnetix.harnax.agent.protocol

/**
 * File attachment produced by agent execution in sandbox workspace.
 *
 * @property fileId     Unique file identifier (used in download URL path)
 * @property fileName   Original file name (e.g. "report.pptx")
 * @property filePath   Path in workspace (e.g. "/workspace/output/report.pptx")
 * @property fileSize   File size in bytes
 * @property mimeType   MIME type
 * @property url        Stable download URL (accessible via admin proxy, for WebUI)
 * @property objectKey  MinIO object key (for internal service direct access)
 */
data class FileAttachment(
    val fileId: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val url: String,
    val objectKey: String = "",
)
