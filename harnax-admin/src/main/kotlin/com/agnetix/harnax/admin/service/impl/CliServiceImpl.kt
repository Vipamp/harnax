package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.CliCreateRequest
import com.agnetix.harnax.admin.dto.CliResponse
import com.agnetix.harnax.admin.dto.CliUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.CliService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Cli
import com.agnetix.harnax.entity.CliSkillBinding
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.CliSkillBindingMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * CLI service implementation.
 * Manages CLI tool definitions and their skill associations (cli_skill_binding).
 */
@Service
class CliServiceImpl(
    private val jwtUtil: JwtUtil,
    private val cliMapper: CliMapper,
    private val cliSkillBindingMapper: CliSkillBindingMapper,
    private val skillMapper: SkillMapper,
) : CliService {

    private val log = LoggerFactory.getLogger(CliServiceImpl::class.java)

    override fun page(name: String?, status: Int?, pageNum: Int, pageSize: Int): Page<Cli> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val tenantId = TenantContext.getTenantId() ?: 1
        PageHelper.startPage<Cli>(pageNum, pageSize)
        return Page.fromPageInfo(cliMapper.selectCliList(name, status, currentUsername ?: "", tenantId))
    }

    override fun getCli(id: Long): Cli? = cliMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createCli(request: CliCreateRequest): Boolean {
        log.info("Creating CLI, name: {}", request.name)

        val tenantId = TenantContext.getTenantId() ?: 1
        if (cliMapper.selectByName(request.name!!, tenantId) != null) {
            throw BizException("CLI name already exists")
        }

        val cli = Cli()
        cli.name = request.name
        cli.description = request.description ?: ""
        cli.version = request.version ?: ""
        cli.installScript = request.installScript!!
        cli.checkCommand = request.checkCommand ?: ""
        cli.envParams = request.envParams
        cli.status = request.status ?: 1
        cli.isPublic = request.isPublic ?: 0
        cli.tenantId = tenantId
        cli.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""
        cli.active = 1

        val success = cliMapper.insert(cli) > 0
        if (success) {
            saveSkillBindings(cli.id, request.skillIds)
        }
        log.info("CLI creation {}, cliId: {}", if (success) "successful" else "failed", cli.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateCli(id: Long, request: CliUpdateRequest): Boolean {
        log.info("Updating CLI, id: {}", id)

        val cli = cliMapper.selectById(id)
            ?: throw BizException("CLI not found")

        if (request.name != null && request.name != cli.name) {
            val exist = cliMapper.selectByName(request.name, cli.tenantId)
            if (exist != null) {
                throw BizException("CLI name already exists")
            }
            cli.name = request.name
        }

        request.description?.let { cli.description = it }
        request.version?.let { cli.version = it }
        request.installScript?.let { cli.installScript = it }
        request.checkCommand?.let { cli.checkCommand = it }
        request.envParams?.let { cli.envParams = it }
        request.isPublic?.let { cli.isPublic = it }

        val success = cliMapper.updateById(cli) > 0
        if (request.skillIds != null) {
            saveSkillBindings(id, request.skillIds)
        }
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleCliStatus(id: Long, status: Int): Boolean {
        cliMapper.selectById(id) ?: throw BizException("CLI not found")
        return cliMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteCli(id: Long): Boolean {
        cliMapper.selectById(id) ?: throw BizException("CLI not found")
        cliSkillBindingMapper.deleteByCliId(id)
        return cliMapper.deleteById(id) > 0
    }

    override fun convertToResponse(cli: Cli): CliResponse {
        val response = CliResponse.fromEntity(cli)
        val bindings = cliSkillBindingMapper.selectByCliId(cli.id)
        if (bindings.isNotEmpty()) {
            val skillsById = skillMapper.selectByIds(bindings.map { it.skillId }).associateBy { it.id }
            response.skillList = bindings.mapNotNull { binding ->
                val skill = skillsById[binding.skillId] ?: return@mapNotNull null
                CliResponse.SkillItem(
                    skillId = skill.id,
                    skillName = skill.name,
                    skillDescription = skill.description,
                )
            }
        }
        return response
    }

    /**
     * Save skill bindings: delete old + insert new.
     */
    private fun saveSkillBindings(cliId: Long, skillIds: List<Long>?) {
        cliSkillBindingMapper.deleteByCliId(cliId)
        if (skillIds.isNullOrEmpty()) return

        val now = LocalDateTime.now()
        val bindings = skillIds.distinct().map { skillId ->
            CliSkillBinding().apply {
                this.cliId = cliId
                this.skillId = skillId
                this.createTime = now
                this.updateTime = now
            }
        }
        cliSkillBindingMapper.batchInsert(bindings)
    }
}
