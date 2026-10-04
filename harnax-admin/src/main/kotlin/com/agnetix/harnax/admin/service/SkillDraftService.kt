package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.SkillDraftSubmitRequest

/**
 * The review queue of agent-proposed skills.
 *
 * Only intake is here today, because intake is the half the runtime drives; the two decisions a reviewer
 * makes (approve, reject) arrive with the screen that shows them.
 */
interface SkillDraftService {

    /**
     * Queues one proposal, merging it into the tenant's open draft of the same name.
     *
     * @return the id of the row that now holds the proposal, new or merged
     * @throws com.agnetix.harnax.admin.exception.BizException when the proposal cannot be attributed to a
     * tenant, or when it is not storable as sent — a name over the column's width, an empty body, a file
     * path that would escape the skill directory. The caller is a promotion gate sitting inside a live
     * inference, so the refusal has to name the reason it will not be retried.
     */
    fun submit(request: SkillDraftSubmitRequest): Long
}
