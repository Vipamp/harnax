package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.TeamCreateRequest
import com.agnetix.harnax.admin.dto.TeamResponse
import com.agnetix.harnax.admin.dto.TeamUpdateRequest
import com.agnetix.harnax.admin.service.impl.RelatedSessionInfo
import com.agnetix.harnax.entity.Team

/**
 * Team service interface
 */
interface TeamService {

    fun page(name: String?, status: Int?, pageNum: Int, pageSize: Int): Page<Team>

    fun getTeam(id: Long): Team?

    fun createTeam(request: TeamCreateRequest): Boolean

    fun updateTeam(id: Long, request: TeamUpdateRequest): Boolean

    fun toggleTeamStatus(id: Long, status: Int): Boolean

    fun deleteTeam(id: Long): Boolean

    fun convertToResponse(team: Team): TeamResponse

    /**
     * Web sessions bound to this team, so a team edit can be pushed to the conversations still
     * holding the previous member configuration.
     */
    fun listRelatedSessions(id: Long): List<RelatedSessionInfo>
}
