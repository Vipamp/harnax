package com.agnetix.harnax.admin.context

/**
 * Tenant context
 * Uses ThreadLocal to store the current request's tenant ID
 */
object TenantContext {
    private val CONTEXT = ThreadLocal<Long>()

    /**
     * Set tenant ID
     */
    fun setTenantId(tenantId: Long) {
        CONTEXT.set(tenantId)
    }

    /**
     * Get tenant ID
     */
    fun getTenantId(): Long? = CONTEXT.get()

    /**
     * Clear tenant context
     */
    fun clear() {
        CONTEXT.remove()
    }
}
