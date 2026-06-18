package com.agnetix.harnax.auth

enum class CallerType {
    INTERNAL_SERVICE,
    EXTERNAL_API,
}

data class AuthContext(
    val callerId: String,
    val scopes: Set<String>,
    val callerType: CallerType = CallerType.INTERNAL_SERVICE,
    val tenantId: Long? = null,
    val rateLimitPerMinute: Int? = null,
) {
    val scope: String get() = scopes.firstOrNull() ?: ""

    constructor(callerId: String, scope: String, tenantId: Long? = null) :
        this(callerId = callerId, scopes = setOf(scope), callerType = CallerType.INTERNAL_SERVICE, tenantId = tenantId)

    fun hasScope(required: String): Boolean = required in scopes

    fun hasAnyScope(vararg required: String): Boolean = required.any { it in scopes }

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
