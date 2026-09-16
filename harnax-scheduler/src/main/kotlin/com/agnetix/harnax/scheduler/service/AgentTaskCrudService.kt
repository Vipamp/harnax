package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.scheduler.dto.AgentTaskCreateRequest
import com.agnetix.harnax.scheduler.dto.AgentTaskResponse
import com.agnetix.harnax.scheduler.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.scheduler.dto.Page
import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.entity.AgentTaskLog

/**
 * The scheduled-task CRUD surface, moved here from `harnax-admin`'s `AgentTaskService` in release 2.
 *
 * Reads and writes are per-user: every method below that touches a row does it for
 * [com.agnetix.harnax.scheduler.support.CallerContext.username] — the person admin forwarded the call for —
 * with the visibility rule the domain has always had (`is_public = 1 OR creator = ?` to read, `creator = ?`
 * to write). It is not this module's [com.agnetix.harnax.scheduler.service.SchedulerService]: that one runs
 * and stops executions, this one maintains the definitions, and the two used to live in different services.
 */
interface AgentTaskCrudService {

    fun page(
        name: String?,
        agentId: Long?,
        taskStatus: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<AgentTask>

    /** A task this caller may see, or null for "no such task, or not yours to see". */
    fun getAgentTask(id: Long): AgentTask?

    fun createAgentTask(request: AgentTaskCreateRequest): Boolean

    fun updateAgentTask(id: Long, request: AgentTaskUpdateRequest): Boolean

    fun deleteAgentTask(id: Long): Boolean

    fun convertToResponse(task: AgentTask): AgentTaskResponse

    /**
     * The read a toggle has to pass: the task must exist and be visible to the caller.
     *
     * Deliberately the *visibility* rule and not the creator rule, which is what this domain did before the
     * move too — `start`/`pause`/`trigger` carry no owner check of their own, and the gap is recorded as F13
     * for a later release rather than closed on the way past.
     */
    fun requireVisibleTask(id: Long): AgentTask

    /** The write gate a stop has to pass: the log's owning task must have been created by this caller. */
    fun requireOwnedLog(logId: Long): AgentTaskLog
}
