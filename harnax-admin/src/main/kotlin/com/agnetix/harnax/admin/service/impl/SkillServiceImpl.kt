package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillCreateRequest
import com.agnetix.harnax.admin.dto.SkillInstallResponse
import com.agnetix.harnax.admin.dto.SkillResponse
import com.agnetix.harnax.admin.dto.SkillUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.SkillService
import com.agnetix.harnax.admin.skill.SkillInstaller
import com.agnetix.harnax.admin.skill.SkillSourceConfigs
import com.agnetix.harnax.admin.skill.SkillSourcePolicy
import com.agnetix.harnax.admin.skill.SkillSyncRecorder
import com.agnetix.harnax.admin.skill.loader.SkillLoaderRegistry
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.TenantResolver
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.TeamSkillBindingMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path

/**
 * Skill service implementation
 */
@Service
class SkillServiceImpl(
    private val jwtUtil: JwtUtil,
    private val skillMapper: SkillMapper,
    private val skillRepositoryService: SkillRepositoryService,
    private val agentSkillBindingMapper: AgentSkillBindingMapper,
    private val teamSkillBindingMapper: TeamSkillBindingMapper,
    private val cliMapper: CliMapper,
    private val skillLoaderRegistry: SkillLoaderRegistry,
    private val skillInstaller: SkillInstaller,
    private val skillSyncRecorder: SkillSyncRecorder,
    @Value($$"${local.tmp-dir}") private val localTmpDir: String,
    private val messageUtil: MessageUtil,
) : SkillService {

    private val log = LoggerFactory.getLogger(SkillServiceImpl::class.java)

    override fun page(
        name: String?,
        repositoryId: Long?,
        status: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<Skill> {
        log.info(
            "Paginated query for skill list, pageNum: {}, pageSize: {}, name: {}, repositoryId: {}, status: {}",
            pageNum,
            pageSize,
            name,
            repositoryId,
            status,
        )
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val tenantId = currentTenantId()
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        // Built-in CLI skills belong to the seeding tenant but must be listable everywhere,
        // otherwise the CLI binding dialog comes up empty for other tenants
        val builtinRepositoryId = skillRepositoryService.getBuiltinRepository()?.id
        PageHelper.startPage<Skill>(safePageNum, safePageSize)
        return Page.fromPageInfo(
            skillMapper.selectSkillList(name, repositoryId, status, currentUsername, tenantId, builtinRepositoryId),
        )
    }

    override fun getSkill(id: Long): Skill? {
        log.info("Querying skill details, id: {}", id)

        // The row carries the full SKILL.md and every bundled resource, so reading it across
        // tenants would leak another tenant's skill content. An unreadable row now answers as the
        // same nothing an unknown id does: refusing out loud confirmed the skill exists and whose
        // it is, and the controller turned that into a 500.
        return skillMapper.selectById(id)?.takeIf { readable(it) }
    }

    /**
     * The tenant this request acts within. [TenantResolver] holds the chain and the reason a request
     * without `X-Tenant-ID` is read as the caller's own tenant rather than as tenant 1 — the same answer
     * the other admin services give, so a skill written by one call is found by the next.
     *
     * The visibility predicates below ([readable], [requireWritableRepo]) deliberately keep reading the
     * raw [TenantContext] instead: there a null means "an internal call with no tenant to gate by", and
     * resolving it to a concrete tenant would turn that into a membership check against the default
     * workspace. This helper only replaces the two places that had to answer with *some* id.
     */
    private fun currentTenantId(): Long = TenantResolver.resolve(jwtUtil)

    @Transactional(rollbackFor = [Exception::class])
    override fun createSkill(request: SkillCreateRequest): Boolean {
        log.info("Creating skill, name: {}", request.name)

        val name = SkillSourcePolicy.requireUsableText(request.name, "Skill name")
        val repositoryId = request.repositoryId
            ?: throw BizException("Repository ID cannot be empty")

        // Checked before any DB access, and with the same rule the toggle endpoint applies: an
        // out-of-range value fits the TINYINT column, but every delivery path tests `status == 1`,
        // so `status = 7` would store a skill that is neither switchable in the UI nor ever loaded
        val initialStatus = request.status ?: 1 // Default enabled
        SkillSourcePolicy.requireStatus(initialStatus)

        // The runtime builds an AgentSkill out of exactly these columns and throws on a blank one, so
        // without this gate the API stores a skill that lists fine, binds fine and never loads
        SkillSourcePolicy.requireContentOnCreate(request.skillmd, request.description)
        SkillSourcePolicy.requireValidResources(request.resources)

        // Authorisation first: probing whether a name is taken must not be possible for a
        // repository the caller may not write to
        val repository = requireWritableRepo(repositoryId)

        val existSkill = getByNameAndRepo(repositoryId, name)
        if (existSkill != null) {
            throw BizException("Skill name already exists")
        }

        val skill = Skill()
        skill.name = name
        skill.repositoryId = repositoryId
        skill.description = request.description ?: ""
        skill.skillmd = request.skillmd ?: ""
        skill.resources = request.resources ?: ""
        skill.status = initialStatus
        skill.active = 1 // Default active
        // Follow the repository: a private skill inside a public repository is invisible in the
        // list even though the repository itself is shown
        skill.isPublic = repository.isPublic

        // Set tenant ID
        skill.tenantId = currentTenantId()

        // Set creator
        skill.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""

        val success = this.skillMapper.insert(skill) > 0
        log.info("Skill creation {}, skillId: {}", if (success) "successful" else "failed", skill.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateSkill(id: Long, request: SkillUpdateRequest): Boolean {
        log.info("Updating skill, id: {}", id)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("Skill not found")
        requireReadable(skill)

        // Builtin repository skills are read-only, and skills cannot be moved in/out of it
        requireWritableRepo(skill.repositoryId)
        val targetRepository = request.repositoryId?.let { requireWritableRepo(it) }

        // Resolve the final name and repository before checking uniqueness. A move that keeps the
        // name still collides with a skill already sitting in the destination, and leaving that to
        // `uk_skill_repo_active_name` answers with a raw SQL error instead of a usable message
        val targetName = request.name?.let { SkillSourcePolicy.requireUsableText(it, "Skill name") } ?: skill.name
        val targetRepositoryId = request.repositoryId ?: skill.repositoryId
        if (targetName != skill.name || targetRepositoryId != skill.repositoryId) {
            val existSkill = skillMapper.selectByNameAndRepo(targetName, targetRepositoryId)
            if (existSkill != null) {
                throw BizException("Skill name already exists")
            }
        }
        skill.name = targetName
        skill.repositoryId = targetRepositoryId
        // Visibility follows the repository, the rule createSkill and SkillInstaller already apply:
        // keeping the old value left a private skill invisible after a move into a public repository
        targetRepository?.let { skill.isPublic = it.isPublic }

        // Selectively update fields
        SkillSourcePolicy.requireContentOnUpdate(request.skillmd, request.description)
        SkillSourcePolicy.requireValidResources(request.resources)
        request.description?.let { skill.description = it }
        request.skillmd?.let { skill.skillmd = it }
        request.resources?.let { skill.resources = it }

        // status is not part of the updateById statement; it has a dedicated update. Leaving the
        // field unread here made `PUT /skills/update/{id}` answer 200 while the row kept its old
        // status, so `harnax skill update <id> --status 0` printed "updated successfully" and
        // changed nothing. `/skill-sources/{id}` already routes it to updateStatus; so does this
        request.status?.let { newStatus ->
            SkillSourcePolicy.requireStatus(newStatus)
            if (newStatus != skill.status) {
                if (newStatus == 0) requireUnbound(skill, "disabled")
                skillMapper.updateStatus(id, newStatus)
                skill.status = newStatus
            }
        }

        val success = this.skillMapper.updateById(skill) > 0
        log.info("Skill update {}, id: {}", if (success) "successful" else "failed", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleSkillStatus(id: Long, status: Int): Boolean {
        log.info("Toggling skill status, id: {}, status: {}", id, status)
        SkillSourcePolicy.requireStatus(status)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("Skill not found")
        requireReadable(skill)
        requireWritableRepo(skill.repositoryId)
        if (status == 0 && skill.status == 1) requireUnbound(skill, "disabled")

        return skillMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSkill(id: Long): Boolean {
        log.info("Deleting skill, id: {}", id)

        val skill = skillMapper.selectById(id)
            ?: throw BizException("Skill not found")
        requireReadable(skill)
        requireWritableRepo(skill.repositoryId)
        // A delete cascades through the bindings, so without this the row an agent points at would
        // simply disappear — the harsher operation cannot have the looser precondition
        requireUnbound(skill, "deleted")

        // Remove agent references so no dangling binding survives the delete
        agentSkillBindingMapper.deleteBySkillIds(listOf(id))

        return skillMapper.deleteById(id) > 0
    }

    /**
     * Refuses to take a skill out of circulation while something binds it.
     *
     * A binding means the skill is part of what that holder does on its next run. Switching it off or
     * deleting it from the skill page rewrites that holder without anyone looking at it, so the change
     * has to start where it is visible: on the agent's, the team's or the CLI's own configuration. Both
     * counts come from the reads the list page renders, or the switch and this guard drift apart — which
     * is how a skill bound only to a lead used to read "0 agents" here and still be refused.
     */
    private fun requireUnbound(
        skill: Skill,
        action: String,
    ) {
        val agents = boundAgentCounts(listOf(skill.id))[skill.id] ?: 0
        if (agents > 0) {
            val named = if (agents == 1) "1 agent" else "$agents agents"
            throw BizException("Skill '${skill.name}' is bound to $named, so it cannot be $action")
        }
        val teams = boundTeamCounts(listOf(skill.id))[skill.id] ?: 0
        if (teams > 0) {
            val named = if (teams == 1) "a team lead" else "$teams team leads"
            throw BizException("Skill '${skill.name}' is bound to $named, so it cannot be $action")
        }
        // A package's skill is not the skill page's to disable: `cli.skill_id` is how the package owns
        // it, and the registrar rewrites or removes the row with the package.
        val packages = cliMapper.selectBySkillIds(listOf(skill.id))
        if (packages.isNotEmpty()) {
            throw BizException(
                "Skill '${skill.name}' ships with CLI package(s) ${packages.joinToString(", ") { it.name }}, " +
                    "so it cannot be $action from here",
            )
        }
    }

    /**
     * Resolves the repository a mutation targets and rejects it when the caller may not write.
     *
     * The builtin CLI skill repository is platform-managed: its skills are provisioned with the
     * platform and only reachable through CLI bindings. A missing repository is an error, not a
     * reason to let the write through — silently returning used to disable both checks at once.
     */
    private fun requireWritableRepo(repositoryId: Long): SkillRepository {
        val repository = skillRepositoryService.getSkillRepository(repositoryId)
            ?: throw BizException("Skill repository not found")
        if (BuiltinRepository.isBuiltin(repository.name)) {
            throw BizException("Repository '${BuiltinRepository.CLI_SKILLS}' is read-only, its skills cannot be created/modified/deleted")
        }
        val currentTenantId = TenantContext.getTenantId()
        if (currentTenantId != null && repository.tenantId != currentTenantId) {
            throw BizException("Skill repository belongs to another tenant")
        }
        return repository
    }

    /**
     * Tenant rule for a single skill. A null context means an internal/system call, and skills of the
     * shared builtin repository are readable by every tenant.
     */
    private fun readable(skill: Skill): Boolean {
        val currentTenantId = TenantContext.getTenantId() ?: return true
        if (skill.tenantId == currentTenantId) return true
        return skill.repositoryId == skillRepositoryService.getBuiltinRepository()?.id
    }

    /** The write paths have to say why they refused; the detail read answers as absent instead. */
    private fun requireReadable(skill: Skill) {
        if (!readable(skill)) {
            throw BizException(messageUtil.getMessage("error.skill.no_permission"))
        }
    }

    override fun getByNameAndRepo(repositoryId: Long, name: String): Skill? = skillMapper.selectByNameAndRepo(name, repositoryId)

    /**
     * Selective sync: load the source once, then store exactly the skills the caller picked.
     *
     * Loading happens outside the transaction — a Git clone or `npm install` can take minutes and
     * must not hold row locks — and persistence is delegated to [SkillInstaller] so this path and
     * the `skill-sources` install share one upsert rule set and one failure report.
     */
    override fun batchSaveSkillsDetailed(repositoryId: Long, skills: List<String>): SkillInstallResponse {
        log.info("Batch saving skills, repositoryId: {}, count: {}", repositoryId, skills.size)
        // Ceiling plus trim plus blank-drop, shared with the `skill-sources` install so the two entry
        // points cannot drift apart. Asked before the repository read: an oversized list should not
        // cost a lookup, and names the source does not hold are each reported back — without the
        // ceiling one request makes the response carry tens of thousands of failure entries
        val selected = SkillSourcePolicy.normalizeSelection(skills).orEmpty()
        val skillRepository = requireWritableRepo(repositoryId)
        // Answering before the loader is picked: a ZIP source keeps no archive, so the only honest
        // reply is "upload it again", not the config error the ZIP loader would raise
        SkillSourcePolicy.requireRefreshable(skillRepository)

        // An empty or all-blank selection is honoured as "store nothing" rather than as "no selection
        // given", which would install the whole source
        if (selected.isEmpty()) {
            return SkillInstallResponse()
        }

        val config = SkillSourceConfigs.parse(skillRepository)
        val loader = skillLoaderRegistry.getLoader(skillRepository.sourceType)
        loader.validateConfig(config)
        val base = Path.of(localTmpDir).also { Files.createDirectories(it) }
        val tmpDir = Files.createTempDirectory(base, "skill-sync-")

        val loaded = try {
            loader.loadSkills(config, tmpDir)
        } finally {
            try {
                tmpDir.toFile().deleteRecursively()
            } catch (e: Exception) {
                log.warn("Failed to cleanup tmp dir: {}", tmpDir, e)
            }
        }

        // `loaded.failures` covers the directories whose SKILL.md could not be parsed; without them
        // a selection of five skills that yields three stored rows answers as a plain success
        val install = skillInstaller.persist(skillRepository, loaded.skills, only = selected, loadFailures = loaded.failures)
        skillSyncRecorder.record(skillRepository, install)
        log.info("Batch saving skills completed for repository {}: {}", repositoryId, install.summary)
        return install
    }

    override fun convertToResponse(skill: Skill): SkillResponse {
        val repository = skillRepositoryService.getSkillRepository(skill.repositoryId)
        return SkillResponse.fromEntity(
            skill,
            repository,
            boundAgentCount = boundAgentCounts(listOf(skill.id))[skill.id] ?: 0,
            boundTeamCount = boundTeamCounts(listOf(skill.id))[skill.id] ?: 0,
        )
    }

    override fun convertToResponses(skills: List<Skill>): List<SkillResponse> {
        // Cache repository lookups so a page of skills triggers one query per distinct repository
        val repositories = skills.map { it.repositoryId }.distinct()
            .associateWith { skillRepositoryService.getSkillRepository(it) }
        val ids = skills.map { it.id }
        val boundAgents = boundAgentCounts(ids)
        val boundTeams = boundTeamCounts(ids)
        return skills.map {
            SkillResponse.fromEntity(
                it,
                repositories[it.repositoryId],
                boundAgentCount = boundAgents[it.id] ?: 0,
                boundTeamCount = boundTeams[it.id] ?: 0,
            )
        }
    }

    /**
     * Agents per skill from one grouped read.
     *
     * The list page renders the count next to every switch, and the switch is dead precisely because
     * of it — so the answer has to arrive with the rows. Asked per row, a page of twenty skills costs
     * twenty reads.
     */
    private fun boundAgentCounts(skillIds: List<Long>): Map<Long, Int> {
        if (skillIds.isEmpty()) return emptyMap()
        return agentSkillBindingMapper.selectAgentBindingCounts(skillIds).associate { it.skillId to it.agentCount }
    }

    /**
     * Teams per skill, same shape and same reason as [boundAgentCounts].
     *
     * A lead's skills hang off the team row since V34, so a skill can be in use with no agent binding
     * at all; [requireUnbound] refuses to disable one either way and the page has to show the same.
     */
    private fun boundTeamCounts(skillIds: List<Long>): Map<Long, Int> {
        if (skillIds.isEmpty()) return emptyMap()
        return teamSkillBindingMapper.selectTeamBindingCounts(skillIds).associate { it.skillId to it.teamCount }
    }
}
