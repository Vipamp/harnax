package com.agnetix.harnax.admin.service

import com.agnetix.harnax.common.dto.ResultVo

/**
 * Client that reaches the agent runtime behind the session-router.
 *
 * admin owns the session row; the running conversation does not live in that row. Chat state, plan notes
 * and the sandbox container belong to whichever agent-service instance served the session last, so the only
 * way admin can let go of a session is to ask the runtime to let go of it — which is what [clearSession]
 * does. The router is the service that knows that mapping, so it is the one this client calls.
 */
interface AgentRuntimeClient {

    /**
     * Release everything the runtime holds for one session: the stored agent state and its plan notes, and
     * the session's sandbox container (whose workspace snapshot is persisted first, by the runtime).
     *
     * A session the router has no instance bound to answers as a success — nothing holds it anywhere, so
     * there is nothing to reclaim — and a session whose runtime refuses answers as a non-200 code carrying
     * that refusal. Neither comes back as an exception: the caller decides what a refusal costs the user,
     * and for the session deletion paths — admin and mobile — that is the deletion being refused rather than
     * half done.
     */
    fun clearSession(sessionId: String): ResultVo<Void>
}
