package com.agnetix.harnax.harness.output

import io.agentscope.harness.agent.sandbox.Sandbox
import org.slf4j.LoggerFactory

/**
 * Detects new files created in the sandbox output directory during an agent call.
 *
 * Strategy: scan `{workspaceRoot}/output/` for files created after [sinceEpochMs].
 * The agent prompt instructs the LLM to save result files to /workspace/output/.
 *
 * @param allowedExtensions Whitelist of file extensions to detect (lowercase, no dot)
 * @param maxFileSize Maximum file size in bytes (files larger are skipped)
 * @param maxFiles Maximum number of files to return per detection
 */
class OutputFileDetector(
    private val allowedExtensions: Set<String> = DEFAULT_EXTENSIONS,
    private val maxFileSize: Long = 50 * 1024 * 1024, // 50MB
    private val maxFiles: Int = 5,
) {
    companion object {
        val DEFAULT_EXTENSIONS = setOf(
            "pptx", "ppt", "xlsx", "xls", "csv",
            "docx", "doc", "pdf",
            "png", "jpg", "jpeg", "gif", "svg",
            "zip", "tar", "gz",
            "mp3", "mp4", "wav",
            "html", "json",
        )

        private val log = LoggerFactory.getLogger(OutputFileDetector::class.java)
    }

    /**
     * Detect new files in the output directory created after [sinceEpochMs].
     *
     * @param sandbox The active sandbox instance
     * @param workspaceRoot Absolute workspace path inside the container (e.g. "/workspace")
     * @param sinceEpochMs Epoch milliseconds — only files modified after this time are detected
     * @return List of detected files (sorted by size descending, limited to [maxFiles])
     */
    fun detect(sandbox: Sandbox, workspaceRoot: String, sinceEpochMs: Long): List<DetectedFile> {
        return try {
            val outputDir = "$workspaceRoot/output"

            // Ensure output directory exists (agent may not have created it)
            sandbox.exec(null, "mkdir -p $outputDir", 5)

            // Convert epoch ms to date string for find -newermt
            val dateStr = java.time.Instant.ofEpochMilli(sinceEpochMs)
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            // Scan only the output directory for new files
            val cmd = "find $outputDir -type f -newermt '$dateStr' " +
                "-printf '%s\\t%p\\n' 2>/dev/null | sort -rn | head -${maxFiles * 3}"

            val result = sandbox.exec(null, cmd, 10)
            val stdout = result.stdout().trim()
            if (stdout.isBlank()) {
                log.debug("[outputDetect] No new files found in workspace after {}", dateStr)
                return emptyList()
            }

            val files = stdout.lines()
                .mapNotNull { line ->
                    val parts = line.split("\t", limit = 2)
                    if (parts.size != 2) return@mapNotNull null
                    val size = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
                    val path = parts[1].trim()
                    val fileName = path.substringAfterLast("/")
                    val ext = fileName.substringAfterLast(".", "").lowercase()
                    if (ext !in allowedExtensions) return@mapNotNull null
                    if (size > maxFileSize) {
                        log.info("[outputDetect] Skipping oversized file: {} ({} bytes > {} limit)", fileName, size, maxFileSize)
                        return@mapNotNull null
                    }
                    if (size == 0L) return@mapNotNull null
                    DetectedFile(path = path, fileName = fileName, size = size)
                }
                .take(maxFiles)

            if (files.isNotEmpty()) {
                log.info("[outputDetect] Detected {} output file(s): {}", files.size, files.joinToString { it.fileName })
            }
            files
        } catch (e: Exception) {
            log.warn("[outputDetect] File detection failed: {}", e.message)
            emptyList()
        }
    }
}

/**
 * A file detected in the sandbox workspace.
 *
 * @property path     Absolute path inside the container
 * @property fileName Base file name (e.g. "report.pptx")
 * @property size     File size in bytes
 */
data class DetectedFile(
    val path: String,
    val fileName: String,
    val size: Long,
)
