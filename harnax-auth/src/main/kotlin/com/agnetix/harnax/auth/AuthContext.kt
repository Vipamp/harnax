package com.agnetix.harnax.auth

enum class CallerType {
    INTERNAL_SERVICE,
    EXTERNAL_API,
}

data class AuthContext(
    val callerId: String,
    val callerType: CallerType = CallerType.INTERNAL_SERVICE,
    val tenantId: Long? = null,
    val rateLimitPerMinute: Int? = null,
    /** Retained for logging/observability only — not used for access control. */
    val scopes: Set<String> = emptySet(),
) {
    fun isInternal(): Boolean = callerType == CallerType.INTERNAL_SERVICE
}

object AuthContextHolder {
    private val holder = ThreadLocal<AuthContext>()

    fun set(context: AuthContext) {
        holder.set(context)
    }

    fun get(): AuthContext? = holder.get()

    fun clear() {
        holder.remove()
    }
}
