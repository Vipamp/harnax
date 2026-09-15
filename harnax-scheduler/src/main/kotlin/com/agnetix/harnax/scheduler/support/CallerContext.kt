package com.agnetix.harnax.scheduler.support

/**
 * Who the request currently on this thread is being served for.
 *
 * The scheduler authenticates *services* (see [InternalCallerInterceptor]) and a service is not a person, so
 * everything a task CRUD or an ownership check needs about the human has to come from the caller: admin
 * forwards `X-Forwarded-User` and `X-Tenant-Id` next to its internal bearer, and this object is where the
 * request thread keeps them. Both are `null` when the call has no user behind it, which is normal — the C5
 * owner read happens while a task runs, on a chain that has never seen a login.
 *
 * The lifecycle is the interceptor's problem, not this object's: it is set in `preHandle` and cleared in
 * `afterCompletion`, never in `postHandle`. Tomcat hands the same thread to the next request, so a clear that
 * an exception path can skip is a caller identity that leaks into someone else's request — with this module
 * reading task ownership off exactly those two values, that is not a cosmetic bug.
 */
object CallerContext {

    private val HOLDER = ThreadLocal<Caller>()

    /** The verified service-side identity, i.e. the `sub` of the internal JWT admin presented. */
    val callerId: String?
        get() = HOLDER.get()?.callerId

    /** The end user this call is forwarded for, from `X-Forwarded-User`; null when there is none. */
    val username: String?
        get() = HOLDER.get()?.username

    /** That user's tenant, from `X-Tenant-Id`; null when there is none or the value is not a number. */
    val tenantId: Long?
        get() = HOLDER.get()?.tenantId

    fun set(caller: Caller) {
        HOLDER.set(caller)
    }

    fun clear() {
        HOLDER.remove()
    }

    data class Caller(val callerId: String, val username: String?, val tenantId: Long?)
}
