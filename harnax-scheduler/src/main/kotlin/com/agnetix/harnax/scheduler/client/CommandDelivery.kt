package com.agnetix.harnax.scheduler.client

import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.common.dto.ResultVo

/**
 * What a command call actually learned.
 *
 * This used to be a boolean, and two different `false` answers hid in it: "an instance replied that no
 * execution is running" and "we never got a reply". The stop path has to treat them differently — only
 * the first means nobody will ever report the execution's outcome — so the difference has to be in the
 * type rather than in a comment.
 */
sealed class CommandDelivery {

    /** An instance holding this session took the command and had a live execution to act on. */
    data object Delivered : CommandDelivery()

    /**
     * The instance answered normally and said there is nothing running for this session. A real miss:
     * the only verdict that lets a caller treat an in-flight execution as over.
     */
    data class Missed(val message: String?) : CommandDelivery()

    /**
     * No verdict was reachable — the call threw, or the envelope carried no command result. Says nothing
     * about whether the execution is alive; a router blip read as a miss would stop a running task on
     * paper and throw away the result it still reports back.
     */
    data class Unanswered(val reason: String) : CommandDelivery()

    companion object {
        /**
         * Read the verdict out of a router response. Only an envelope that carries a command result tells
         * anything about the execution; every other shape — null body, error code, missing data — is
         * [Unanswered] no matter how final it looks.
         */
        fun from(response: ResultVo<CommandResponse>?): CommandDelivery {
            if (response == null) {
                return Unanswered("no response body from router")
            }
            if (!response.isSuccess()) {
                return Unanswered("router answered with code=${response.code}: ${response.message}")
            }
            val verdict = response.data ?: return Unanswered("router answered 200 but sent no command result")
            return if (verdict.success) Delivered else Missed(verdict.message)
        }
    }
}
