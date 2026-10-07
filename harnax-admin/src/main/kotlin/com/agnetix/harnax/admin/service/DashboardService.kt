package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.DashboardOverviewResponse
import java.time.LocalDateTime

/**
 * Landing-page overview reads.
 *
 * One call answers the whole page: today's numbers, their yesterday counterpart, the 14-day trend, both
 * top-5 lists and the asset stock-take. Assembling that from six paginated list endpoints on the client
 * would not work — a list page's total reflects the filters on that page, not a counting window, and no
 * amount of list reads yields yesterday's same-span comparison.
 *
 * Read-only, and the only method here. Nothing writes: an overview observes the workspace, it does not
 * change it.
 */
interface DashboardService {

    /**
     * Build the overview for one tenant at one instant.
     *
     * [now] is a parameter rather than a [LocalDateTime.now] call inside, for two reasons that both
     * matter: the caller reads one clock for the whole response — so the today window, its yesterday
     * counterpart and the trend all hang off the same instant instead of drifting apart by the seconds
     * between three reads — and a test can pin the four window boundaries by naming a fixed moment.
     *
     * @param tenantId Tenant to count within, resolved from the request and never taken from a parameter
     * @param now The instant every window in the response is measured against
     * @return Every number the page shows
     */
    fun overview(tenantId: Long, now: LocalDateTime): DashboardOverviewResponse
}
