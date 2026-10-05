package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.SkillVisibilityResponse
import com.agnetix.harnax.admin.dto.SkillVisibilityUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.SkillVisibilityService
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.admin.skill.SkillReviewRecorder
import com.agnetix.harnax.admin.skill.SkillVisibilityCodec
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.TenantResolver
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillReviewLog
import com.agnetix.harnax.entity.SkillVisibilityPolicy
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillVisibilityPolicyMapper
import com.agnetix.harnax.mapper.SysUserMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.Locale

/**
 * Skill visibility policy read and write.
 *
 * The write side is where the value of this table is decided, so it refuses anything the runtime could not
 * act on: a percentage without a CANARY mode, an allow-list of ids that are not members of this tenant, a
 * label containing the comma that separates labels. Each of those would store a rule that reads back fine
 * on this screen while doing something else at runtime, which is the failure a rollout guard must not have.
 */
@Service
class SkillVisibilityServiceImpl(
    private val jwtUtil: JwtUtil,
    private val skillMapper: SkillMapper,
    private val sysUserMapper: SysUserMapper,
    private val skillRepositoryService: SkillRepositoryService,
    private val userTenantService: UserTenantService,
    private val skillVisibilityPolicyMapper: SkillVisibilityPolicyMapper,
    private val skillReviewRecorder: SkillReviewRecorder,
    private val messageUtil: MessageUtil,
) : SkillVisibilityService {

    private val log = LoggerFactory.getLogger(SkillVisibilityServiceImpl::class.java)
    private val objectMapper = ObjectMapper()

    override fun get(skillId: Long): SkillVisibilityResponse {
        val skill = requireSkill(skillId)
        val tenantId = TenantResolver.resolve(jwtUtil)
        if (!readable(skill, tenantId)) {
            throw BizException(messageUtil.getMessage("error.skill.no_permission"))
        }
        // Readable across tenants for a platform-wide skill, writable only by the tenant holding its row
        return responseOf(skill, skillVisibilityPolicyMapper.selectBySkillId(skillId), editable = skill.tenantId == tenantId)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun update(
        skillId: Long,
        request: SkillVisibilityUpdateRequest,
    ): SkillVisibilityResponse {
        val skill = requireSkill(skillId)
        val tenantId = TenantResolver.resolve(jwtUtil)
        if (skill.tenantId != tenantId) {
            // Not "you may not access this skill": a caller who can read a builtin skill genuinely may not
            // set its rollout, and saying so is the whole answer to why the save was refused
            throw BizException("Only the tenant owning skill '${skill.name}' may set its visibility; it belongs to tenant ${skill.tenantId}")
        }

        val mode = normalizeMode(request.mode)
        val before = skillVisibilityPolicyMapper.selectBySkillId(skillId)
        val policy = SkillVisibilityPolicy().apply {
            this.skillId = skill.id
            this.tenantId = skill.tenantId
            this.mode = mode
            // Only the column the mode actually uses is filled: a leftover percentage on a row that now
            // reads ALL would be shown back to the next operator as a rollout state nobody set
            when (mode) {
                SkillVisibilityPolicy.MODE_CANARY -> this.canaryPct = requireCanaryPct(request.canaryPct)
                SkillVisibilityPolicy.MODE_ALLOW_LIST -> this.userIds = SkillVisibilityCodec.userIdsJson(requireUserIds(request.userIds, skill))
                SkillVisibilityPolicy.MODE_ENV -> this.environments = requireEnvironments(request.environments)
                else -> Unit
            }
        }

        // One statement rather than a select-then-insert-or-update: two operators saving the same skill at
        // the same time would otherwise both decide to insert and one would fail on the unique key
        skillVisibilityPolicyMapper.upsert(policy)

        skillReviewRecorder.recordSkill(
            skillId = skill.id,
            action = SkillReviewLog.ACTION_VISIBILITY_CHANGE,
            detail = changeDetail(skill, before, policy),
            tenantId = skill.tenantId,
        )
        log.info("Visibility of skill '{}' (id={}) set to {} by tenant {}", skill.name, skill.id, mode)

        val stored = skillVisibilityPolicyMapper.selectBySkillId(skillId)
        return responseOf(skill, stored, editable = true)
    }

    private fun requireSkill(skillId: Long): Skill = skillMapper.selectById(skillId)
        ?: throw BizException(messageUtil.getMessage("error.skill.notfound"))

    /**
     * The tenant rule [com.agnetix.harnax.admin.service.impl.SkillServiceImpl.readable] applies to the skill
     * row, restated here rather than reached through that service: the same exemption for the platform-wide
     * builtin repository, because an operator on another tenant has to be able to see what a CLI-shipped
     * skill is rolled out to before they bind it.
     */
    private fun readable(
        skill: Skill,
        tenantId: Long,
    ): Boolean {
        if (skill.tenantId == tenantId) return true
        return skill.repositoryId == skillRepositoryService.getBuiltinRepository()?.id
    }

    private fun normalizeMode(mode: String?): String {
        val normalized = mode?.trim()?.uppercase(Locale.ROOT)
        if (normalized.isNullOrEmpty()) {
            throw BizException("A visibility mode is required: ${SkillVisibilityPolicy.MODES.joinToString(", ")}")
        }
        if (normalized !in SkillVisibilityPolicy.MODES) {
            throw BizException("Unknown visibility mode '$mode', expected one of: ${SkillVisibilityPolicy.MODES.joinToString(", ")}")
        }
        return normalized
    }

    private fun requireCanaryPct(canaryPct: Int?): Int {
        if (canaryPct == null) throw BizException("Mode CANARY needs a rollout percentage")
        if (canaryPct < MIN_PCT || canaryPct > MAX_PCT) {
            throw BizException("Rollout percentage must be between $MIN_PCT and $MAX_PCT, got $canaryPct")
        }
        return canaryPct
    }

    /**
     * Membership of the skill's own tenant, checked per id.
     *
     * An id from elsewhere is refused rather than ignored: the allow-list is the one mode whose whole
     * answer is a set of names, and storing a typo that happens to be somebody else's user id would show
     * the operator a list of intended recipients while the runtime gates a stranger.
     */
    private fun requireUserIds(
        userIds: List<Long>?,
        skill: Skill,
    ): List<Long> {
        val ids = userIds.orEmpty().distinct()
        if (ids.isEmpty()) throw BizException("Mode ALLOW_LIST needs at least one user id")
        if (ids.size > MAX_ALLOW_LIST_USERS) {
            throw BizException("Mode ALLOW_LIST takes at most $MAX_ALLOW_LIST_USERS users; use CANARY for a wider rollout")
        }
        val invalid = ids.filter { it <= 0 }
        if (invalid.isNotEmpty()) throw BizException("User ids must be positive, got: ${invalid.joinToString(",")}")
        val outsiders = ids.filter { !userTenantService.isUserInTenant(it, skill.tenantId) }
        if (outsiders.isNotEmpty()) {
            throw BizException("User(s) ${outsiders.joinToString(",")} are not members of tenant ${skill.tenantId}, which owns skill '${skill.name}'")
        }
        return ids
    }

    /**
     * Labels as the storage format allows them: comma separated, so a comma inside a label is two labels,
     * and one column, so the whole list has to fit in it.
     */
    private fun requireEnvironments(environments: List<String>?): String? {
        val labels = environments?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        if (labels.isEmpty()) throw BizException("Mode ENV needs at least one environment label")
        if (labels.any { it.contains(',') }) {
            throw BizException("Environment labels cannot contain a comma: ${labels.filter { it.contains(',') }.joinToString(",")}")
        }
        val tooLong = labels.filter { it.length > MAX_ENV_LABEL_LENGTH }
        if (tooLong.isNotEmpty()) {
            throw BizException("Environment labels over $MAX_ENV_LABEL_LENGTH characters: ${tooLong.joinToString(",")}")
        }
        val joined = labels.joinToString(",")
        if (joined.length > MAX_ENV_TOTAL_LENGTH) {
            throw BizException("Environment labels do not fit the $MAX_ENV_TOTAL_LENGTH-character column, got ${joined.length}")
        }
        return joined
    }

    private fun responseOf(
        skill: Skill,
        policy: SkillVisibilityPolicy?,
        editable: Boolean,
    ): SkillVisibilityResponse {
        if (policy == null) {
            return SkillVisibilityResponse(
                skillId = skill.id,
                skillName = skill.name,
                tenantId = skill.tenantId,
                editable = editable,
            )
        }
        val dto = SkillVisibilityCodec.toDto(policy)
        return SkillVisibilityResponse(
            skillId = skill.id,
            skillName = skill.name,
            tenantId = skill.tenantId,
            mode = dto.mode,
            canaryPct = dto.canaryPct,
            userIds = dto.userIds,
            users = namesOf(dto.userIds),
            environments = dto.environments,
            editable = editable,
            updateTime = policy.updateTime,
        )
    }

    /** One batched read. An id with no live account still comes back, named only by its id. */
    private fun namesOf(userIds: List<Long>): List<SkillVisibilityResponse.User> {
        if (userIds.isEmpty()) return emptyList()
        val names = sysUserMapper.selectByIds(userIds).associateBy { it.id }
        return userIds.map { SkillVisibilityResponse.User(id = it, username = names[it]?.username) }
    }

    private fun changeDetail(
        skill: Skill,
        before: SkillVisibilityPolicy?,
        after: SkillVisibilityPolicy,
    ): String = objectMapper.writeValueAsString(
        mapOf(
            // Named from the row, as every other skill audit entry does: once the skill is gone a history
            // that says only "skill 17" cannot be checked against anything
            "name" to skill.name,
            "before" to stateOf(before),
            "after" to stateOf(after),
        ),
    )

    private fun stateOf(policy: SkillVisibilityPolicy?): Map<String, Any?> = if (policy == null) {
        // No row and an explicit ALL are the same runtime answer, and the trail says which one was in place
        mapOf("mode" to SkillVisibilityPolicy.MODE_ALL)
    } else {
        val dto = SkillVisibilityCodec.toDto(policy)
        mapOf("mode" to dto.mode, "canaryPct" to dto.canaryPct, "userIds" to dto.userIds, "environments" to dto.environments)
    }

    companion object {
        private const val MIN_PCT = 0
        private const val MAX_PCT = 100

        /**
         * Ceiling on one allow-list. Every id costs a membership check on save, and the list is delivered
         * inside every agent spec that carries the skill; wider than this is a percentage, not a list.
         */
        private const val MAX_ALLOW_LIST_USERS = 200

        private const val MAX_ENV_LABEL_LENGTH = 64

        /** The `environments` column is varchar(255). */
        private const val MAX_ENV_TOTAL_LENGTH = 255
    }
}
