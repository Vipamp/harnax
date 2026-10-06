package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.entity.ToolInvocationLog

/**
 * Where a tool call came from, decided from what assembly already knew.
 *
 * No database read here (design D4): the runtime holds none of Admin's tables, and a lookup per call
 * would turn a counter into a round trip on the inference path. The three maps below are built once per
 * agent build and are the only facts this needs.
 *
 * The order is the contract, not an implementation detail. The registry is what the model could actually
 * call, so an MCP answer beats a name that also appears in the delivered tool list; the shell is checked
 * before the delivered list because a CLI runs *through* it, and a name that is neither lands as
 * `framework` — a harness built-in nothing in Admin's tables names.
 */
object ToolInvocationClassifier {

    /** Both shell shapes: the harness sandbox tool and the core coding tool. */
    val SHELL_TOOL_NAMES = setOf("execute", "execute_shell_command")

    /** Upstream's fixed name for the skill loader; it is package private upstream, so it is a literal here. */
    const val SKILL_LOAD_TOOL_NAME = "load_skill_through_path"

    /** Argument that holds the command line on either shell tool. */
    private const val COMMAND_ARG = "command"

    /** A `VAR=value` word before a command is not the command. */
    private val ENV_ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*$""")

    private val SEGMENT_OPERATORS = setOf("|", "||", "&&", ";")

    /**
     * @param toolName Name as the model sees it
     * @param input Tool arguments, used to read the shell command line
     * @param mcpIdsByTool Tool name to MCP server row, from the assembled registry
     * @param cliIdsByCommand CLI command name to package row, from the delivered spec
     * @param builtinToolNames Framework names of the tools Admin delivered
     */
    fun classify(
        toolName: String,
        input: Map<String, Any?>,
        mcpIdsByTool: Map<String, Long>,
        cliIdsByCommand: Map<String, Long>,
        builtinToolNames: Set<String>,
    ): InvocationAttribution {
        mcpIdsByTool[toolName]?.let {
            return InvocationAttribution(ToolInvocationLog.KIND_MCP, toolName, it, null)
        }
        if (toolName in SHELL_TOOL_NAMES) {
            val command = input[COMMAND_ARG] as? String
            val name = cliCommandName(command, cliIdsByCommand)
            return if (name == null) {
                InvocationAttribution(ToolInvocationLog.KIND_SHELL, toolName, null, null)
            } else {
                InvocationAttribution(ToolInvocationLog.KIND_CLI, name, null, cliIdsByCommand[name])
            }
        }
        if (toolName in builtinToolNames) {
            return InvocationAttribution(ToolInvocationLog.KIND_BUILTIN, toolName, null, null)
        }
        return InvocationAttribution(ToolInvocationLog.KIND_FRAMEWORK, toolName, null, null)
    }

    /**
     * The leftmost word of [command] that names a delivered CLI, or null when none does.
     *
     * Only the first match is returned: a compound command is still one invocation of one tool, and
     * splitting it into rows would break the one-row rule (I1). The full command line stays in
     * `args_json` for anyone who needs to check the attribution afterwards.
     */
    fun cliCommandName(
        command: String?,
        cliIdsByCommand: Map<String, Long>,
    ): String? {
        if (command.isNullOrBlank() || cliIdsByCommand.isEmpty()) return null
        for (head in commandHeads(command)) {
            val name = head.substringAfterLast('/')
            if (name.isNotEmpty() && cliIdsByCommand.containsKey(name)) return name
        }
        return null
    }

    /**
     * First word of each segment: segments split on `|`, `||`, `&&`, `;`, and a `VAR=value` prefix is skipped.
     *
     * Public because assembly uses it to read a delivered CLI package's `checkCommand` for the names that
     * package can be invoked by (Task 8), which is the same question this function answers for a live command.
     */
    fun commandHeads(command: String): List<String> {
        val heads = mutableListOf<String>()
        var expectHead = true
        for (token in tokenize(command)) {
            when {
                token in SEGMENT_OPERATORS -> expectHead = true
                expectHead && !ENV_ASSIGNMENT.matches(token) -> {
                    heads += token
                    expectHead = false
                }
            }
        }
        return heads
    }

    /**
     * Split on whitespace outside quotes, treating the pipeline operators as boundaries of their own.
     *
     * Quotes are honoured because a delivered CLI may contain a space, and an operator inside a quoted
     * argument must not open a segment. An unquoted operator inside an argument is accepted as a segment
     * break: no command name this feature matches contains one, and the cost of getting it wrong is a
     * row filed as `shell` rather than as the CLI.
     */
    private fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val buf = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (quote != null) {
                if (c == quote) {
                    quote = null
                    if (buf.isNotEmpty()) {
                        tokens += buf.toString()
                        buf.setLength(0)
                    }
                } else {
                    buf.append(c)
                }
                i++
                continue
            }
            if (c == '\'' || c == '"') {
                quote = c
                i++
                continue
            }
            if (c.isWhitespace()) {
                if (buf.isNotEmpty()) {
                    tokens += buf.toString()
                    buf.setLength(0)
                }
                i++
                continue
            }
            if (c == '|' || c == '&' || c == ';') {
                if (buf.isNotEmpty()) {
                    tokens += buf.toString()
                    buf.setLength(0)
                }
                val doubled = i + 1 < command.length && command[i + 1] == c
                tokens += if (doubled) "$c$c" else "$c"
                i += if (doubled) 2 else 1
                continue
            }
            buf.append(c)
            i++
        }
        if (buf.isNotEmpty()) tokens += buf.toString()
        return tokens
    }
}

/**
 * The row's identity beyond the tool name: which `kind` it is and which subject it belongs to.
 *
 * [toolName] differs from the called tool only for `kind = cli`, where the CLI's command name is what an
 * operator reads; the shell tool that ran it is the same for every CLI.
 */
data class InvocationAttribution(
    val kind: String,
    val toolName: String,
    val mcpId: Long?,
    val cliId: Long?,
)
