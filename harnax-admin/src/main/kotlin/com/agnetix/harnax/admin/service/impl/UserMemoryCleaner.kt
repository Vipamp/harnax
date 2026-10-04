package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.util.MemoryObjectKeys
import com.agnetix.harnax.entity.SysUser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Removes the memory of a user an admin is deleting, from every tenant that user could have talked to.
 *
 * Long-term memory is not a row: the agent runtime appends to the owner's bucket on every conversation, so
 * without this sweep a deleted account keeps a store of what it told its agents — visible to nobody, and
 * still readable by whoever is given that user id next. This is the same posture the user deletion already
 * takes for MCP grants ([SysUserServiceImpl.deleteUser] clears those at the same moment).
 *
 * The bucket is keyed on the tenant of the *agent* that was talked to, not on the account's home tenant, so
 * the sweep addresses one prefix per membership: the memory of a user in two workspaces sits under both.
 * Every tenant here comes from a row, never from a request. An account with no tenant and no membership is
 * left alone rather than swept under a guessed one — a guess here is a prefix that could name somebody
 * else's workspace, and the memory of an account that never belonged anywhere is nobody's leak.
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
     * @param user the row being deleted, whose `id` is the owner segment of every prefix addressed
     * @param membershipTenantIds the tenants `user_tenant` named for this account before it was deleted
     */
    fun deleteForUser(
        user: SysUser,
        membershipTenantIds: Collection<Long>,
    ): Int {
        val tenantIds = (listOfNotNull(user.tenantId?.takeIf { it > 0 }) + membershipTenantIds.filter { it > 0 })
            .distinct()
        if (tenantIds.isEmpty()) {
            log.warn(
                "[memory] User {} has no tenant and no membership, so no memory bucket is addressed for it — " +
                    "the account's memory, if any, stays put",
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
        val removed = memoryStoreGateway.deleteUser(tenantIds, MemoryObjectKeys.userSegment(user.id))
        if (removed > 0) {
            log.info("[memory] Removed {} memory object(s) of user {} from {}", removed, user.id, tenantIds)
        }
        return removed
    }
}
