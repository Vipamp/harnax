package com.agnetix.harnax.admin.util

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.exception.GlobalExceptionHandler
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import java.sql.SQLException

/**
 * Builds the message a controller shows when it catches an exception itself.
 *
 * The skill controllers answer with HTTP 200 plus an error body, so [GlobalExceptionHandler] never
 * sees their failures and its policy of not exposing database details has to be applied here
 * instead. Two things used to reach the operator verbatim: the text of a `DuplicateKeyException`,
 * which quotes the schema, the table and the statement, and a wrapped command output (an
 * `npm install` log is easily thousands of lines).
 *
 * `V15__skill_source_integrity.sql` added unique indexes, so a duplicate name now hits one on a
 * regular path instead of only under a race. Messages the application authored for a human (a
 * [BizException], loader validation, archive guard rails) are kept, because hiding them would
 * replace an actionable answer with a shrug.
 */
object ApiErrors {

    private val log = LoggerFactory.getLogger(ApiErrors::class.java)

    /** Anything longer is a log dump rather than a message; the full text stays in the log. */
    private const val MAX_LENGTH = 500

    private const val DATABASE_FALLBACK =
        "Database operation failed, please check the submitted values and try again"

    private const val GENERIC_DUPLICATE = "The submitted name is already in use"

    /**
     * Unique index of `V15__skill_source_integrity.sql` mapped to the rule it enforces.
     *
     * Naming the index keeps the answer specific: a bare "already exists" does not say whether the
     * collision is on the source name, on a skill inside it, or on the reserved builtin name.
     */
    private val DUPLICATE_INDEX_MESSAGES = mapOf(
        "uk_skill_repository_tenant_active_name" to "Source name already exists",
        "uk_skill_repository_builtin_guard" to
            "Repository name '${BuiltinRepository.CLI_SKILLS}' is reserved for the platform",
        "uk_skill_repo_active_name" to "A skill with this name already exists in the repository",
    )

    /**
     * @param e the caught exception; the caller logs it in full, so only the shown text is trimmed
     * @param fallback used when [e] carries no usable message at all
     */
    fun message(e: Throwable, fallback: String): String {
        val shown = when {
            e is DuplicateKeyException -> duplicateMessage(e)
            // A constraint other than uniqueness (a NOT NULL column, a value too long for its
            // column) says nothing to an operator in its raw form
            e is DataAccessException || e is SQLException -> {
                log.warn("Suppressed a database error detail: {}", e.message)
                DATABASE_FALLBACK
            }
            else -> e.message?.trim()?.takeIf { it.isNotEmpty() }
        }
        return truncate(shown ?: fallback)
    }

    private fun duplicateMessage(e: DuplicateKeyException): String {
        val raw = e.message.orEmpty()
        return DUPLICATE_INDEX_MESSAGES.entries.firstOrNull { (index, _) -> raw.contains(index) }?.value
            ?: GENERIC_DUPLICATE
    }

    private fun truncate(text: String): String = if (text.length <= MAX_LENGTH) text else text.take(MAX_LENGTH) + "... (truncated, see the server log)"
}
