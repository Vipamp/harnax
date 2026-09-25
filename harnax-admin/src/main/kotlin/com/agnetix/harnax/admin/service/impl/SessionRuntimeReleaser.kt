package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.AgentRuntimeClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Releases one session's runtime state, and refuses whatever the caller was about to write when the
 * runtime cannot.
 *
 * The row is only one part of a conversation. The stored agent state, the plan notes and the sandbox
 * container live with whichever agent-service instance served the session, and admin cannot reach any of
 * it: [AgentRuntimeClient.clearSession] is the only door. A runtime that says it could not let go therefore
 * means the state would go on living under a name no row points at any more — worse than an operation that
 * can be retried once the runtime is reachable — so the refusal is turned into an exception here rather
 * than returned as a flag a caller could forget to check.
 *
 * That is the whole invariant, and three deletes depend on it: a session deletion (both the admin page and
 * the mobile client), and a channel deletion, whose `chn-{uuid}` conversation has no `session` row of its
 * own to release it. Putting it in one place is what keeps those from drifting apart — the wording, the
 * code and the "unbound session answers as success" tolerance now have one owner.
 */
@Component
class SessionRuntimeReleaser(
    private val agentRuntimeClient: AgentRuntimeClient,
    private val messageUtil: MessageUtil,
) {

    private val log = LoggerFactory.getLogger(SessionRuntimeReleaser::class.java)

    /**
     * Ask the runtime to let go of [sessionId]; throw when it refuses.
     *
     * [sessionId] has to be the id the runtime keys by (`web-…`, `mp-…`, `chn-…`), never a row id: the
     * runtime has never heard of the row, so releasing by it clears nothing and still answers "released".
     *
     * The refusal message carries the runtime's own sentence, because that is the one thing the operator
     * can act on — "the sandbox is busy" and "the router is unreachable" want opposite responses.
     */
    fun release(sessionId: String) {
        val cleared = agentRuntimeClient.clearSession(sessionId)
        if (!cleared.isSuccess()) {
            log.warn("The runtime could not release session {}: {}", sessionId, cleared.message)
            throw BizException(messageUtil.getMessage("error.session.runtime.release", cleared.message))
        }
    }
}
