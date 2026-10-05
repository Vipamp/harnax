package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SkillReviewLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Audit trail for skill-domain state changes. Insert-only; the read exists to answer the operations
 * question: who approved this agent-written skill, when, and why.
 *
 * [tenantId] is a parameter on the read rather than an afterthought: this table keeps rows for both
 * its subjects, and a log leaking another tenant's review history would be a leak of skill content
 * summaries along with it.
 */
@Mapper
interface SkillReviewLogMapper {

    fun insert(log: SkillReviewLog): Int

    fun selectBySubject(
        @Param("tenantId") tenantId: Long,
        @Param("subject") subject: String,
        @Param("subjectId") subjectId: Long,
    ): List<SkillReviewLog>
}
