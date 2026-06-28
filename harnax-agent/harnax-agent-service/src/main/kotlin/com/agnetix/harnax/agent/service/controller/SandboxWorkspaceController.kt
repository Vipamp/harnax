package com.agnetix.harnax.agent.service.controller

import com.agnetix.harnax.auth.InternalOnly
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager
import io.agentscope.harness.agent.sandbox.SandboxException
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/**
 * Sandbox Workspace Controller.
 * Provides file browsing and content reading for Docker sandbox workspaces.
 * All endpoints are internal-only, accessed via session-router proxy.
 */
@RestController
@RequestMapping("/api/agent/workspace")
@InternalOnly
@Tag(name = "Sandbox Workspace", description = "Sandbox workspace file browsing APIs")
class SandboxWorkspaceController(
    private val launcher: HarnessAgentLauncher,
) {

    private val log = LoggerFactory.getLogger(SandboxWorkspaceController::class.java)

    private val sandboxManager: KeepAliveSandboxManager?
        get() = launcher.keepAliveSandboxManager

    private companion object {
        /** Maximum file content size returned by read endpoint (256 KB). */
        const val MAX_FILE_SIZE_BYTES = 256 * 1024

        /** Default exec timeout in seconds. */
        const val EXEC_TIMEOUT_SECONDS = 15

        /** Maximum path depth to prevent abuse. */
        const val MAX_PATH_DEPTH = 20
    }

    /**
     * List files in the workspace directory.
     * @param sessionId session ID
     * @param path directory path inside the container (default: workspace root)
     */
    @GetMapping("/{sessionId}/files")
    @Operation(summary = "List workspace files", description = "List files in a sandbox workspace directory")
    fun listFiles(
        @PathVariable sessionId: String,
        @RequestParam(defaultValue = "/workspace") path: String,
    ): ResultVo<List<Map<String, Any>>> {
        // URL-decode path to handle cases where %2F is not auto-decoded by Tomcat
        val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
        log.info("Listing workspace files for session=$sessionId, path=$decodedPath")
        val sandbox = resolveSandbox(sessionId) ?: return ResultVo.error(404, "No active sandbox for session=$sessionId")

        val safePath = sanitizePath(decodedPath)
            ?: return ResultVo.error(400, "Invalid path: $decodedPath")

        return try {
            // Use find + stat to get structured file info: type, name, size, modified time
            val cmd = """find "$safePath" -maxdepth 1 -mindepth 1 -printf '%y\t%f\t%s\t%T+\n' 2>/dev/null | sort -t"$(printf '\t')" -k1,1 -k2,2"""
            val result = sandbox.exec(null, cmd, EXEC_TIMEOUT_SECONDS)
            val files = result.stdout()
                .lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val parts = line.split("\t", limit = 4)
                    mapOf(
                        "type" to when (parts.getOrElse(0) { "" }) {
                            "d" -> "directory"
                            "f" -> "file"
                            "l" -> "symlink"
                            else -> "unknown"
                        },
                        "name" to parts.getOrElse(1) { "" },
                        "size" to (parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0L),
                        "modified" to parts.getOrElse(3) { "" },
                    )
                }
            ResultVo.success(files)
        } catch (e: SandboxException.ExecException) {
            log.warn("Command failed for session=$sessionId, path=$safePath: exitCode=${e.exitCode}, stderr=${e.stderr}")
            ResultVo.error(404, "Path not found or inaccessible: $safePath")
        } catch (e: Exception) {
            log.warn("Failed to list files for session=$sessionId, path=$safePath: ${e.message}")
            ResultVo.error(500, "Failed to list files: ${e.message}")
        }
    }

    /**
     * Read file content from the workspace.
     * @param sessionId session ID
     * @param path file path inside the container
     */
    @GetMapping("/{sessionId}/read")
    @Operation(summary = "Read workspace file", description = "Read text content of a file in the sandbox workspace")
    fun readFile(
        @PathVariable sessionId: String,
        @RequestParam path: String,
    ): ResultVo<Map<String, Any>> {
        val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
        log.info("Reading workspace file for session=$sessionId, path=$decodedPath")
        val sandbox = resolveSandbox(sessionId) ?: return ResultVo.error(404, "No active sandbox for session=$sessionId")

        val safePath = sanitizePath(decodedPath)
            ?: return ResultVo.error(400, "Invalid path: $decodedPath")

        return try {
            // First check file size to avoid OOM
            val sizeResult = sandbox.exec(null, """stat -c '%s' "$safePath" 2>/dev/null""", EXEC_TIMEOUT_SECONDS)
            val fileSize = sizeResult.stdout().trim().toLongOrNull() ?: 0L

            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return ResultVo.success(
                    mapOf(
                        "content" to "[File too large: $fileSize bytes, limit: $MAX_FILE_SIZE_BYTES bytes]",
                        "truncated" to true,
                        "size" to fileSize,
                    ),
                )
            }

            val readResult = sandbox.exec(null, """cat "$safePath" 2>/dev/null""", EXEC_TIMEOUT_SECONDS)
            ResultVo.success(
                mapOf(
                    "content" to readResult.stdout(),
                    "truncated" to false,
                    "size" to fileSize,
                ),
            )
        } catch (e: SandboxException.ExecException) {
            log.warn("Command failed for session=$sessionId, path=$safePath: exitCode=${e.exitCode}, stderr=${e.stderr}")
            ResultVo.error(404, "File not found or inaccessible: $safePath")
        } catch (e: Exception) {
            log.warn("Failed to read file for session=$sessionId, path=$safePath: ${e.message}")
            ResultVo.error(500, "Failed to read file: ${e.message}")
        }
    }

    /**
     * Upload a file to the workspace.
     * @param sessionId session ID
     * @param path target directory path inside the container
     * @param file the file to upload
     */
    @PostMapping("/{sessionId}/upload")
    @Operation(summary = "Upload file to workspace", description = "Upload a file to a directory in the sandbox workspace")
    fun uploadFile(
        @PathVariable sessionId: String,
        @RequestParam(defaultValue = "/workspace") path: String,
        @RequestParam("file") file: MultipartFile,
    ): ResultVo<Map<String, Any>> {
        val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
        log.info("Uploading file to workspace for session=$sessionId, path=$decodedPath, fileName=${file.originalFilename}")
        val sandbox = resolveSandbox(sessionId) ?: return ResultVo.error(404, "No active sandbox for session=$sessionId")

        val safePath = sanitizePath(decodedPath)
            ?: return ResultVo.error(400, "Invalid path: $decodedPath")

        return try {
            val containerName = getContainerName(sessionId)
                ?: return ResultVo.error(404, "Cannot determine container name for session=$sessionId")

            // Create temp file
            val tempFile = java.io.File.createTempFile("upload-", "-${file.originalFilename}")
            try {
                file.transferTo(tempFile)

                // Use docker cp to copy file to container
                val targetPath = "$safePath/${file.originalFilename ?: "uploaded-file"}"
                val process = ProcessBuilder("docker", "cp", tempFile.absolutePath, "$containerName:$targetPath")
                    .redirectErrorStream(true)
                    .start()
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    val error = process.inputStream.bufferedReader().readText()
                    log.warn("docker cp failed: $error")
                    return ResultVo.error(500, "Failed to upload file: $error")
                }

                ResultVo.success(
                    mapOf(
                        "fileName" to (file.originalFilename ?: "uploaded-file"),
                        "path" to targetPath,
                        "size" to file.size,
                    ),
                )
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            log.warn("Failed to upload file for session=$sessionId, path=$safePath: ${e.message}", e)
            ResultVo.error(500, "Failed to upload file: ${e.message}")
        }
    }

    /**
     * Download a file from the workspace.
     * @param sessionId session ID
     * @param path file path inside the container
     */
    @GetMapping("/{sessionId}/download")
    @Operation(summary = "Download file from workspace", description = "Download a file from the sandbox workspace")
    fun downloadFile(
        @PathVariable sessionId: String,
        @RequestParam path: String,
    ): ResponseEntity<Any> {
        val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
        log.info("Downloading file from workspace for session=$sessionId, path=$decodedPath")
        val sandbox = resolveSandbox(sessionId)
            ?: return ResponseEntity.status(404).body(mapOf("error" to "No active sandbox for session=$sessionId"))

        val safePath = sanitizePath(decodedPath)
            ?: return ResponseEntity.status(400).body(mapOf("error" to "Invalid path: $decodedPath"))

        return try {
            val containerName = getContainerName(sessionId)
                ?: return ResponseEntity.status(404).body(mapOf("error" to "Cannot determine container name for session=$sessionId"))

            // Create temp file for download
            val tempFile = java.io.File.createTempFile("download-", "-${java.io.File(safePath).name}")
            try {
                // Use docker cp to copy file from container
                val process = ProcessBuilder("docker", "cp", "$containerName:$safePath", tempFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    val error = process.inputStream.bufferedReader().readText()
                    log.warn("docker cp failed: $error")
                    return ResponseEntity.status(404).body(mapOf("error" to "File not found or inaccessible: $safePath"))
                }

                val fileName = java.io.File(safePath).name
                val resource = InputStreamResource(tempFile.inputStream())

                ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(tempFile.length())
                    .body(resource as Any)
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        } catch (e: Exception) {
            log.warn("Failed to download file for session=$sessionId, path=$safePath: ${e.message}", e)
            ResponseEntity.status(500).body(mapOf("error" to "Failed to download file: ${e.message}"))
        }
    }

    /**
     * Get sandbox status for one or multiple sessions.
     * @param sessionIds comma-separated session IDs (optional, returns all active if not provided)
     */
    @GetMapping("/status")
    @Operation(summary = "Get sandbox status", description = "Check sandbox status for one or multiple sessions")
    fun getStatus(
        @RequestParam(required = false) sessionIds: String?,
    ): ResultVo<Any> {
        if (sessionIds != null) {
            // Multiple sessions
            val ids = sessionIds.split(",").map { it.trim() }.filter { it.isNotBlank() }.take(50)
            log.info("[SandboxWorkspaceController] Getting sandbox status for ${ids.size} sessions: $ids")
            val result = mutableMapOf<String, Any>()
            for (id in ids) {
                var sandbox = resolveSandbox(id)

                // If not in memory, check if Docker container exists
                if (sandbox == null) {
                    try {
                        val containerName = "agentscope-sandbox-$id"
                        val process = ProcessBuilder("docker", "inspect", "--format", "{{.State.Running}}", containerName)
                            .redirectErrorStream(true)
                            .start()
                        process.waitFor()
                        val output = process.inputStream.bufferedReader().readText().trim()

                        if (output == "true" || output == "false") {
                            log.info("[SandboxWorkspaceController] Session $id - container exists (state=$output), but not in memory")
                            // Container exists, report as active
                            result[id] = mapOf(
                                "active" to true,
                                "containerName" to containerName,
                                "image" to "unknown", // Can't get image without sandbox object
                            )
                            continue
                        }
                    } catch (e: Exception) {
                        log.debug("[SandboxWorkspaceController] Failed to check container for session $id: ${e.message}")
                    }
                }

                log.info("[SandboxWorkspaceController] Session $id - sandbox: ${if (sandbox != null) "found" else "null"}")
                if (sandbox != null) {
                    val state = sandbox.state as? DockerSandboxState
                    result[id] = mapOf(
                        "active" to true,
                        "containerName" to (state?.getContainerName() ?: "unknown"),
                        "image" to (state?.getImage() ?: "unknown"),
                    )
                } else {
                    result[id] = mapOf("active" to false)
                }
            }
            val response: ResultVo<Any> = ResultVo.success(result as Any)
            log.info("[SandboxWorkspaceController] Returning response: code=${response.code}, data=${response.data}")
            return response
        }

        // Single session (legacy, kept for compatibility)
        log.warn("Deprecated: calling /workspace/status without sessionIds parameter")
        return ResultVo.error("Please provide sessionIds parameter")
    }

    // ---- Helpers ----

    private fun getContainerName(sessionId: String): String? {
        val sandbox = resolveSandbox(sessionId) ?: return null
        val state = sandbox.state as? DockerSandboxState
        return state?.getContainerName() ?: "agentscope-sandbox-$sessionId"
    }

    private fun resolveSandbox(sessionId: String): io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox? {
        val manager = sandboxManager
        if (manager == null) {
            log.warn("KeepAliveSandboxManager is not configured")
            return null
        }
        // First try to get from memory
        var sandbox = manager.getSandbox(sessionId)
        log.info("[SandboxWorkspaceController] resolveSandbox($sessionId) - getSandbox returned: ${if (sandbox != null) "found" else "null"}")

        // If not in memory, try to attach to existing Docker container
        if (sandbox == null) {
            log.info("[SandboxWorkspaceController] resolveSandbox($sessionId) - calling attachToExisting")
            sandbox = manager.attachToExisting(sessionId)
            log.info("[SandboxWorkspaceController] resolveSandbox($sessionId) - attachToExisting returned: ${if (sandbox != null) "found" else "null"}")
        }

        return sandbox
    }

    /**
     * Sanitize the path to prevent directory traversal attacks.
     * Returns null if the path is unsafe.
     */
    private fun sanitizePath(path: String): String? {
        // URL-decode to handle cases where %2F is not auto-decoded by Tomcat
        val decoded = try {
            java.net.URLDecoder.decode(path, "UTF-8")
        } catch (e: Exception) {
            path
        }
        if (decoded.isBlank()) return null
        // Reject paths with null bytes
        if (decoded.contains('\u0000')) return null
        // Reject paths with too many segments
        val segments = decoded.split("/").filter { it.isNotEmpty() }
        if (segments.size > MAX_PATH_DEPTH) return null
        // Reject path traversal attempts
        if (segments.any { it == ".." }) return null
        // Only allow paths under /workspace
        val normalized = decoded.trimEnd('/')
        if (!normalized.startsWith("/workspace")) return null
        return normalized
    }
}
