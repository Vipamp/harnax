package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.MemoryDraftAdaptor
import com.agnetix.harnax.agent.adaptor.MemoryDraftIntake
import com.agnetix.harnax.agent.adaptor.MemoryDraftProposal
import com.agnetix.harnax.agent.service.client.AdminApiClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Posts one conversation's merged memory layer to Admin's review queue over the internal API.
 *
 * Made inline, on the thread the promotion throttle ran it on, because the answer decides what that stage
 * reports: a merge that never reached the queue has to leave the conversation's layer intact, and only
 * [MemoryDraftIntake.Unavailable] says that without also claiming a person rejected the content.
 *
 * The one thing this adds over [AdminApiClient.submitMemoryDraft] is the never-throws half of that contract.
 * The caller runs off the answer path, in a background subscription whose exception would otherwise reach
 * nothing but a reactor error consumer; any fault here becomes an answer about the queue instead.
 */
@Component
class MemoryDraftAdaptorImpl(
    private val adminApiClient: AdminApiClient,
) : MemoryDraftAdaptor {

    private val log = LoggerFactory.getLogger(MemoryDraftAdaptorImpl::class.java)

    override fun propose(proposal: MemoryDraftProposal): MemoryDraftIntake = try {
        adminApiClient.submitMemoryDraft(proposal)
    } catch (e: Exception) {
        log.warn(
            "Memory draft could not be handed to the intake client: sessionId={}, agent={}, {}",
            proposal.sessionId,
            proposal.agentName,
            e.message,
        )
        MemoryDraftIntake.Unavailable(e.message ?: e.javaClass.simpleName)
    }
}
