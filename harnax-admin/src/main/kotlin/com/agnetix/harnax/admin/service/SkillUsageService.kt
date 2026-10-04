package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.SkillUsageReportRequest
import com.agnetix.harnax.admin.dto.SkillUsageSummaryResponse

/**
 * The skill load/use stream: intake for the runtime, aggregation for the operations page.
 *
 * Deliberately its own service rather than methods on [SkillService]: nothing here changes a skill row.
 * The stream is append-only and read-only, and one of its two callers is the running agent rather than a
 * logged-in operator, so the tenant that a row belongs to is decided in completely different ways.
 */
interface SkillUsageService {

    /**
     * Stores what one session reported and answers how many events were kept.
     *
     * Never throws for a session this admin cannot place: the runtime calls this while a conversation is
     * in flight, and a refusal it cannot act on would only turn telemetry into a failure the user sees.
     *
     * @return events stored, zero when the session resolved to no tenant or every event was refused
     */
    fun report(request: SkillUsageReportRequest): Int

    /**
     * Counts per skill for the caller's tenant across the last [days] days, including skills with no events.
     */
    fun summary(days: Int): SkillUsageSummaryResponse
}
