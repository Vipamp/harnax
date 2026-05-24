package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SysJobCreateRequest
import com.agnetix.harnax.admin.dto.SysJobUpdateRequest
import com.agnetix.harnax.entity.SysJob

/**
 * Scheduled job service interface
 */
interface SysJobService {

    /**
     * Query scheduled job list with pagination
     *
     * @param keyword   Fuzzy search field (job name)
     * @param jobStatus Status filter field
     * @param pageNum   Current page number
     * @param pageSize  Page size
     * @return Paginated result
     */
    fun page(keyword: String?, jobStatus: Int?, pageNum: Int, pageSize: Int): Page<SysJob>

    /**
     * Get single scheduled job details
     *
     * @param id Job ID
     * @return Job entity
     */
    fun getSysJob(id: Long): SysJob?

    /**
     * Create scheduled job
     *
     * @param request Job create request object
     * @return Create result
     */
    fun createJob(request: SysJobCreateRequest): Boolean

    /**
     * Update scheduled job
     *
     * @param id      Job ID
     * @param request Job update request object
     * @return Update result
     */
    fun updateJob(id: Long, request: SysJobUpdateRequest): Boolean

    /**
     * Delete scheduled job
     *
     * @param id Job ID
     * @return Delete result
     */
    fun deleteJob(id: Long): Boolean

    /**
     * Start scheduled job
     *
     * @param id Job ID
     * @return Operation result
     */
    fun startJob(id: Long): Boolean

    /**
     * Pause scheduled job
     *
     * @param id Job ID
     * @return Operation result
     */
    fun pauseJob(id: Long): Boolean

    /**
     * Execute scheduled job once immediately
     *
     * @param id Job ID
     * @return Operation result
     */
    fun runJobOnce(id: Long): Boolean

    /**
     * Load all running jobs from database to scheduler
     */
    fun loadJobsToScheduler(): Unit

    /**
     * Get all running jobs
     *
     * @return Job list
     */
    fun getRunningJobs(): List<SysJob>
}
