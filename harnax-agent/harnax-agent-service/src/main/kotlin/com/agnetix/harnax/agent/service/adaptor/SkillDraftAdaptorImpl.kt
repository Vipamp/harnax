package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.SkillDraftAdaptor
import com.agnetix.harnax.agent.adaptor.SkillDraftIntake
import com.agnetix.harnax.agent.adaptor.SkillDraftProposal
import com.agnetix.harnax.agent.service.client.AdminApiClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Posts a skill draft to Admin's review queue over the internal API.
 *
 * Unlike [SkillUsageAdaptor], which fires counters and forgets them, this answers for a decision: the
 * promotion gate reads the result and either defers the draft or refuses it. So the call is made inline, on
 * whatever thread the gate ran it on — upstream's gate contract already puts it off the model's thread — and
 * the outcome is reported rather than dropped.
 *
 * The one thing this adds over [AdminApiClient.submitSkillDraft] is the never-throws half of that contract.
 * A client that throws would surface to the gate as an exception instead of as an answer about the queue,
 * and the gate would have to guess which it was; any fault here becomes
 * [SkillDraftIntake.Unavailable], which is the only reading that cannot lose a draft.
 */
@Component
class SkillDraftAdaptorImpl(
    private val adminApiClient: AdminApiClient,
) : SkillDraftAdaptor {

    private val log = LoggerFactory.getLogger(SkillDraftAdaptorImpl::class.java)

    override fun submit(proposal: SkillDraftProposal): SkillDraftIntake = try {
        adminApiClient.submitSkillDraft(proposal)
    } catch (e: Exception) {
        log.warn(
            "Draft skill '{}' could not be handed to the intake client: sessionId={}, {}",
            proposal.name,
            proposal.sessionId,
            e.message,
        )
        SkillDraftIntake.Unavailable(e.message ?: e.javaClass.simpleName)
    }
}
