package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Skill
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Skill Mapper interface
 *
 * Tenant isolation is deliberately **not** part of these statements: the ID-based reads and every
 * write match on the primary key (or on `repository_id`) plus `active = 1`, and nothing else. Two
 * reasons, both checked against the call sites:
 *
 * - A skill's isolation unit is its repository, and every admin-facing path resolves and
 *   tenant-checks that repository first (`SkillServiceImpl.requireWritableRepo`,
 *   `SkillSourceServiceImpl.requireWritable`), so a tenant predicate here would only re-filter on a
 *   value already known to be the caller's own;
 * - Those service checks *throw* ("Skill belongs to another tenant"), which is what the API answers
 *   with. Filtering in SQL instead would turn the same request into an empty result and the answer
 *   into a plain "not found", erasing the difference between "not yours" and "does not exist".
 *
 * Internal callers (`InternalApiController` delivering agent config, `SkillAdaptorImpl` assembling
 * a skill at runtime) have no tenant context at all and depend on these being unscoped, as does the
 * builtin repository whose skills are shared across tenants.
 *
 * **Adding a call site?** Go through a service, or run the row you get back through the check that
 * owns it: `SkillServiceImpl.requireReadable` for a skill, `requireSameTenant` in
 * `SkillRepositoryServiceImpl` for the repository it hangs off. Calling these methods straight
 * from a new controller or service reopens cross-tenant reads of the full SKILL.md and resources.
 */
@Mapper
interface SkillMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): Skill?

    fun selectByIds(@Param("ids") ids: List<Long>): List<Skill>

    fun insert(skill: Skill): Int

    fun updateById(skill: Skill): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================

    /**
     * Tenant-scoped list query: [tenantId] is pushed into the SQL, with the builtin repository
     * exempted so its shared skills stay visible to every tenant.
     */
    fun selectSkillList(
        @Param("name") name: String?,
        @Param("repositoryId") repositoryId: Long?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long? = null,
        @Param("builtinRepositoryId") builtinRepositoryId: Long? = null,
    ): List<Skill>

    fun selectByNameAndRepo(@Param("name") name: String, @Param("repositoryId") repositoryId: Long): Skill?

    fun selectByRepositoryId(@Param("repositoryId") repositoryId: Long): List<Skill>

    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int
}
