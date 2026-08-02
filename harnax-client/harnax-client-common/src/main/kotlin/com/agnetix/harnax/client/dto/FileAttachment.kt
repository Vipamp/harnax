package com.agnetix.harnax.client.dto

/**
 * File attachment produced by agent execution in sandbox workspace.
 *
 * @property fileId     Unique file identifier (used in download URL path)
 * @property fileName   Original file name (e.g. "report.pptx")
 * @property filePath   Path in workspace (e.g. "/workspace/output/report.pptx")
 * @property fileSize   File size in bytes
 * @property mimeType   MIME type
 * @property url        Stable download URL (accessible via admin proxy)
 */
data class FileAttachment(
    val fileId: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val url: String,
)
