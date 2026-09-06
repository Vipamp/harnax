package com.agnetix.harnax.admin.skill.loader

import io.agentscope.core.skill.repository.GitSkillRepository
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class GitSkillLoader : SkillLoader {
    override val sourceType = "GIT"

    companion object {
        /** A skill repository is small; anything slower than this is a network problem, not a clone. */
        private const val CLONE_TIMEOUT_SECONDS = 180L

        /**
         * Transports allowed for a user-supplied URL. `file://` and `ext::` are excluded:
         * they turn a "remote repository" into arbitrary local filesystem access.
         */
        private val ALLOWED_URL_PREFIXES = listOf("https://", "http://", "ssh://", "git://", "git@")

        private val BRANCH_REGEX = Regex("^[A-Za-z0-9._/-]+$")

        /**
         * Mirrors the legacy `skill_repository.url` and `skill_repository.branch` columns, see
         * `V1__init_schema.sql`.
         *
         * Every write path funnels a Git config through here, including the `skill-sources` API
         * whose `sourceConfig` is a free-form map no `@Size` annotation can reach. Without the
         * bound an over-long URL was accepted, stored, and answered by MySQL with a
         * data-truncation error naming a column instead of the field the caller got wrong.
         */
        private const val MAX_URL_LENGTH = 500
        private const val MAX_BRANCH_LENGTH = 100

        /**
         * The `user:password` part of `scheme://user:password@host/path`.
         *
         * A private skill repository is commonly cloned with a token embedded in the URL, and JGit
         * quotes the full URI in its transport errors. The scp-like `git@host:org/repo` form carries
         * no secret and has no scheme, so this never matches it.
         */
        private val URL_USERINFO_REGEX = Regex("([A-Za-z][A-Za-z0-9+.-]*://)[^/@\\s]+@")
    }

    private val log = LoggerFactory.getLogger(GitSkillLoader::class.java)

    // Clone runs on a worker thread so the request thread is never held hostage by a slow remote
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "git-skill-loader").apply { isDaemon = true }
    }

    override fun loadSkills(config: Map<String, Any>, tmpDir: Path): SkillLoadResult {
        validateConfig(config)
        // Normalised exactly the way validateConfig normalises: that one trims before matching the
        // transport whitelist, so cloning the untrimmed value let " https://host/repo " pass
        // validation and then fail inside JGit with an error that names a URL nobody typed
        val url = (config["url"] as? String)?.trim().orEmpty()
        val branch = (config["branch"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: "main"

        val skillDir = Files.createTempDirectory(tmpDir, "git-skill-")
        val task = Callable { GitSkillRepository(url, branch, skillDir).allSkills }
        val future = executor.submit(task)
        return try {
            // Per-directory parsing happens inside agentscope's `GitSkillRepository`, so unlike the
            // NPM and ZIP loaders this one cannot attribute a single broken `SKILL.md`: whatever
            // `allSkills` hands back is everything there is to report
            SkillLoadResult(future.get(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw RuntimeException("Git clone timed out after ${CLONE_TIMEOUT_SECONDS}s: ${redact(url)}")
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause ?: e
            val reason = redact(cause.message.orEmpty()).ifBlank { cause.javaClass.simpleName }
            log.error("Git skill load failed for {}: {}", redact(url), reason)
            throw RuntimeException("Git skill load failed: $reason", cause)
        }
    }

    override fun validateConfig(config: Map<String, Any>) {
        val url = (config["url"] as? String)?.trim()
        if (url.isNullOrBlank()) {
            throw IllegalArgumentException("Git source config requires 'url'")
        }
        if (url.length > MAX_URL_LENGTH) {
            // Names the limit only: an over-long URL is precisely the case where echoing the value
            // back would flood the response, and it is the one most likely to carry credentials
            throw IllegalArgumentException("Git URL is longer than the $MAX_URL_LENGTH characters allowed")
        }
        if (url.startsWith("-")) {
            throw IllegalArgumentException("Git URL must not start with '-': ${redact(url)}")
        }
        if (ALLOWED_URL_PREFIXES.none { url.startsWith(it) }) {
            throw IllegalArgumentException(
                "Unsupported Git URL '${redact(url)}'. Allowed transports: ${ALLOWED_URL_PREFIXES.joinToString(", ")}",
            )
        }
        (config["branch"] as? String)?.trim()?.takeIf { it.isNotBlank() }?.let { branch ->
            if (branch.length > MAX_BRANCH_LENGTH) {
                throw IllegalArgumentException("Git branch is longer than the $MAX_BRANCH_LENGTH characters allowed")
            }
            if (branch.startsWith("-") || branch.contains("..") || !BRANCH_REGEX.matches(branch)) {
                throw IllegalArgumentException("Invalid Git branch '$branch'")
            }
        }
    }

    /** Replaces the credentials of every URL found in [text] with `***`. */
    private fun redact(text: String): String = URL_USERINFO_REGEX.replace(text) { match -> "${match.groupValues[1]}***@" }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
