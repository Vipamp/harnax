package com.vipamp.vipclaw.admin.context

/**
 * 租户上下文
 * 使用 ThreadLocal 存储当前请求的租户 ID
 */
object TenantContext {
    private val CONTEXT = ThreadLocal<Long>()

    /**
     * 设置租户 ID
     */
    fun setTenantId(tenantId: Long) {
        CONTEXT.set(tenantId)
    }

    /**
     * 获取租户 ID
     */
    fun getTenantId(): Long? = CONTEXT.get()

    /**
     * 清理租户上下文
     */
    fun clear() {
        CONTEXT.remove()
    }
}
