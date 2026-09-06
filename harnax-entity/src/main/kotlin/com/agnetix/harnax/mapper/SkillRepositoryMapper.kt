package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SkillRepository
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * SkillRepository Mapper interface
 *
 * The list queries ([selectRepositoryList], [selectActiveRepositories], [selectByName]) take an
 * optional `tenantId` and push it into the SQL. [selectById] and the writes do not — see
 * [selectById] for why that split is deliberate.
 */
@Mapper
interface SkillRepositoryMapper {

    // ==================== Basic CRUD Methods ====================

    /**
     * Tenant-agnostic on purpose, even though the row is the most sensitive one in this table: it
     * carries the Git URL (possibly with a clone token embedded), the branch and the NPM registry
     * address.
     *
     * Filtering here would not make the API safer, it would make it vaguer. Every admin-facing
     * caller goes through `getSkillRepository` in `SkillRepositoryServiceImpl`, which applies
     * `requireSameTenant` and *throws*; with the predicate in SQL the same request would come back
     * empty and be answered as "not found", losing the difference between "not yours" and "does not
     * exist". Internal callers (agent-service config delivery) have no tenant context at all, and
     * the builtin repository is read by every tenant.
     *
     * **Adding a call site?** Use `SkillRepositoryService.getSkillRepository` or
     * `SkillSourceService.getSkillSource`, or run the row through `requireSameTenant` yourself.
     * Calling this method from a new controller or service without one of those reopens
     * cross-tenant reads of another tenant's source credentials.
     */
    fun selectById(@Param("id") id: Long): SkillRepository?

    fun insert(skillrepository: SkillRepository): Int

    fun updateById(skillrepository: SkillRepository): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================
    /**
     * Lists the repositories visible to [currentUsername].
     *
     * [sourceType] filters on the `source_type` column (GIT / NPM / ZIP / BUILTIN) and is appended
     * last so the positional callers keep working; a null or blank value means "no filter".
     */
    fun selectRepositoryList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long? = null,
        @Param("builtinName") builtinName: String? = null,
        @Param("sourceType") sourceType: String? = null,
    ): List<SkillRepository>

    fun selectActiveRepositories(@Param("tenantId") tenantId: Long? = null): List<SkillRepository>

    fun selectByName(@Param("name") name: String, @Param("tenantId") tenantId: Long? = null): SkillRepository?

    /**
     * Locates the platform-managed builtin repository by its reserved name.
     *
     * Deliberately tenant-agnostic and deterministic (`ORDER BY id`): the row is seeded once and
     * is consumed by every tenant's CLI bindings as well as by agent-service, so filtering it by
     * the caller's tenant used to make built-in skills unavailable outside the seeding tenant.
     */
    fun selectBuiltinRepository(@Param("name") name: String): SkillRepository?

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int
}
