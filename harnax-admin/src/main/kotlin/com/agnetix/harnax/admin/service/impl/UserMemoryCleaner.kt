package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.util.MemoryObjectKeys
import com.agnetix.harnax.entity.SysUser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Removes the memory of a user an admin is deleting, from every agent in that user's tenant.
 *
 * Long-term memory is not a row: the agent runtime appends to the owner's bucket on every conversation, so
 * without this sweep a deleted account keeps a store of what it told its agents — visible to nobody, and
 * still readable by whoever is given that user id next. This is the same posture the user deletion already
 * takes for MCP grants ([SysUserServiceImpl.deleteUser] clears those at the same moment).
 *
 * The bucket is keyed `store/tenants/<tenantId>/users/<userId>/`, and both segments here come from the row
 * the deletion is about, never from a request: the tenant is read off `sys_user.tenant_id` and the id from
 * the same row that was just deleted. A row with no tenant is left alone rather than swept under a guessed
 * one — a guess here is a prefix that could name somebody else's workspace, and the memory of an account
 * that never belonged anywhere is nobody's leak.
 *
 * A failure is thrown, not logged: this runs inside the user-delete transaction, so the account comes back
 * and the admin can retry the same call. Swallowing it would report a cleaned account that still has a
 * memory bucket.
 */
@Component
class UserMemoryCleaner(
    private val memoryStoreGateway: MemoryStoreGateway,
) {
    private val log = LoggerFactory.getLogger(UserMemoryCleaner::class.java)

    /**
     * Deletes everything the user wrote into the memory bucket; returns how many objects left.
     *
     * @param user the row being deleted, whose `tenantId` and `id` build the only prefix this can address
     */
    fun deleteForUser(user: SysUser): Int {
        val tenantId = user.tenantId
        if (tenantId == null || tenantId <= 0) {
            log.warn(
                "[memory] User {} has no tenant, so no memory bucket is addressed for it — the account's memory, if any, stays put",
                user.id,
            )
            return 0
        }
        if (!memoryStoreGateway.isAvailable()) {
            // No store on this deployment means no memory to remove; the deletion must not fail because of
            // a component the operator never turned on.
            log.info("[memory] MinIO is not configured, skipping the memory sweep of user {}", user.id)
            return 0
        }
        val removed = memoryStoreGateway.deleteUser(tenantId, MemoryObjectKeys.userSegment(user.id))
        if (removed > 0) {
            log.info("[memory] Removed {} memory object(s) of user {} in tenant {}", removed, user.id, tenantId)
        }
        return removed
    }
}
