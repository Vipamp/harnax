package com.agnetix.harnax.admin.util

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.security.SecurityUtils
import org.slf4j.LoggerFactory

/**
 * The tenant this request acts within, resolved one way for every admin service.
 *
 * Two shapes used to answer this question inside `harnax-admin`: most services read
 * `TenantContext.getTenantId() ?: 1`, while the env-variable and MCP-server services carried a copy of a
 * four-step chain. The same header-less request therefore resolved two different tenants in two services —
 * an agent written into tenant 1 by one call and looked up from the caller's own tenant by the next.
 *
 * The chain is what the copies had to exist for: [com.agnetix.harnax.admin.interceptor.TenantInterceptor]
 * only fills [TenantContext] when an `X-Tenant-ID` header arrives with it (and verifies the caller belongs
 * to that tenant first), so a request without the header would otherwise create rows inside tenant 1 — a
 * workspace the caller may not belong to — and default its own permission checks there too.
 *
 * First step that answers wins:
 * 1. [TenantContext] — the header-verified workspace, and the only one that carries a membership check.
 * 2. The `tenantId` claim of the caller's own token, for a client that sends no header (the CLI never
 *    sends one).
 * 3. The tenant on the caller's `sys_user` row, for a token issued before the claim existed.
 * 4. [DEFAULT_TENANT_ID], which is what an internal call with neither token nor account lands on — the
 *    column default, unchanged from before this resolver existed.
 *
 * Steps 2 and 3 only ever name a tenant the caller *is* — their own claim, their own row — so the chain
 * can resolve a more accurate tenant than the header alone but can never place a caller in a workspace
 * they do not belong to.
 */
object TenantResolver {

    private val log = LoggerFactory.getLogger(TenantResolver::class.java)

    /** Last resort, and the `tenant_id` column default: never a workspace someone may write into by right. */
    const val DEFAULT_TENANT_ID = 1L

    /**
     * Resolve the tenant for the current request.
     *
     * Cannot throw. Every fallback is read defensively, because the callers run on paths that carry
     * nothing to resolve from — an internal API call, a channel callback, a scheduled run — and those must
     * keep answering with [DEFAULT_TENANT_ID] rather than failing on the way there.
     *
     * @param jwtUtil the JWT reader, injected by the calling service and passed here so this stays a plain
     * object rather than a bean every service has to wire
     * @return the tenant the request acts within
     */
    fun resolve(jwtUtil: JwtUtil): Long = TenantContext.getTenantId()
        ?: tenantFromToken(jwtUtil)
        ?: tenantFromUserRecord()
        ?: defaultTenantId()

    /**
     * The tenant the request's own token claims.
     *
     * A claim that states 0 is refused rather than trusted: ids start at 1, and acting as "tenant 0"
     * would list nobody's rows while writing new ones into a tenant that does not exist. Mockito hands a
     * mock's `Long?` back as 0, so that guard is also what keeps an unstubbled test token from becoming a
     * phantom tenant.
     */
    private fun tenantFromToken(jwtUtil: JwtUtil): Long? = runCatching {
        UserContextUtil.getToken()?.let { token -> jwtUtil.getTenantIdFromToken(token) }?.takeIf { it > 0 }
    }.getOrNull()

    /**
     * The tenant the account itself carries, read from its row rather than from anything the request
     * sends. Somebody holding a token from before the claim existed still belongs somewhere, and defaulting
     * their writes into tenant 1 would mix one workspace's rows into another's.
     */
    private fun tenantFromUserRecord(): Long? = runCatching {
        SecurityUtils.getCurrentUser()?.tenantId?.takeIf { it > 0 }
    }.getOrNull()

    private fun defaultTenantId(): Long {
        log.debug("Request carries no tenant header, token claim or user row, so it acts within the default tenant {}", DEFAULT_TENANT_ID)
        return DEFAULT_TENANT_ID
    }
}
