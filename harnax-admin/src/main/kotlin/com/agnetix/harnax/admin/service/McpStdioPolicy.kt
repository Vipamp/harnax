package com.agnetix.harnax.admin.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Whether stdio MCP servers may be stored and delivered.
 *
 * A stdio entry is not a connection, it is a process: agent-service starts it, and that container
 * runs as root with the host Docker socket mounted (`docker-new/docker-compose.yml`), so being able
 * to save such a row is being able to run commands there. Until the execution side is isolated,
 * stdio is off: creation and the switch into it are refused, rows that already exist are not
 * delivered, and admin's own "list tools" / connectivity probe refuses them too — that probe spawns
 * the stored command in the admin container, so without it the switch would only mean "not delivered".
 *
 * `HARNAX_MCP_STDIO_ENABLED=true` turns it back on for deployments that have isolated the runtime.
 * The agent side carries the same switch (`harness.mcp-stdio-enabled`) as a second line of defence;
 * both must be on for a stdio server to actually run.
 */
@Component
class McpStdioPolicy(
    @Value("\${harnax.mcp.stdio-enabled:false}") val enabled: Boolean,
) {

    fun isStdio(type: String?): Boolean = type?.trim()?.lowercase() == TYPE_STDIO

    /** Why a refused or skipped stdio server says so, instead of failing somewhere far away. */
    fun refusalReason(): String = "stdio MCP servers are disabled on this deployment (harnax.mcp.stdio-enabled=false): " +
        "a stdio server is a process started by agent-service, which runs as root next to the host " +
        "Docker socket. Use a network transport (sse / streamablehttp), or enable the switch on a " +
        "deployment that isolates the runtime"

    companion object {
        const val TYPE_STDIO = "stdio"
    }
}
