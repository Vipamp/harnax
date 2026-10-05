package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SkillUsage
import com.agnetix.harnax.entity.dto.SkillUsageAggregate
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/**
 * Skill load/use stream.
 *
 * [selectUsageByTenant] carries its tenant predicate in SQL, unlike [SkillMapper], because it reads
 * `skill` itself rather than being handed ids a service already vetted. Nothing here needs a tenant
 * check for the writes: an event row is created by the runtime for a skill it was just handed.
 */
@Mapper
interface SkillUsageMapper {

    fun insert(usage: SkillUsage): Int

    /**
     * Every skill of [tenantId] with its event counts inside the window starting at [since].
     *
     * Events are counted per tenant, so a shared skill used by two tenants answers twice with two
     * different numbers. Skills with no events come back at zero rather than being absent — the
     * zero-usage list is the reason this projection exists.
     *
     * [builtinRepositoryId] exempts the shared builtin repository from the tenant filter so a tenant can
     * see whether it uses the platform-shipped skills at all; pass null where no builtin row exists.
     *
     * [currentUsername] carries the visibility rule the skill list applies: a private skill belongs to its
     * creator, so another user's summary leaves it out entirely rather than showing it at zero. Passing
     * null restricts the read to public skills.
     */
    fun selectUsageByTenant(
        @Param("tenantId") tenantId: Long,
        @Param("since") since: LocalDateTime,
        @Param("builtinRepositoryId") builtinRepositoryId: Long? = null,
        @Param("currentUsername") currentUsername: String?,
    ): List<SkillUsageAggregate>
}
