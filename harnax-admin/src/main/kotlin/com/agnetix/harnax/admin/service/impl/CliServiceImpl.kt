package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.CliResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.CliService
import com.agnetix.harnax.admin.skill.SkillContentScanner
import com.agnetix.harnax.admin.skill.SkillSourcePolicy
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.Cli
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * CLI read side and the operator's enable/disable switch.
 *
 * Rows are registered by `CliPackageAutoRegistrar` from the plugin package directory, so there is
 * nothing here to create, edit or delete — and no tenant or author to scope reads by either, since a
 * published package is a platform asset (design D10).
 */
@Service
class CliServiceImpl(
    private val cliMapper: CliMapper,
    private val skillMapper: SkillMapper,
    private val secretFieldEncryptor: SecretFieldEncryptor,
    private val objectMapper: ObjectMapper,
) : CliService {

    private val log = LoggerFactory.getLogger(CliServiceImpl::class.java)

    override fun page(
        name: String?,
        status: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<Cli> {
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<Cli>(safePageNum, safePageSize)
        return Page.fromPageInfo(cliMapper.selectCliList(name, status))
    }

    override fun getCli(id: Long): Cli? = cliMapper.selectById(id)

    /**
     * Takes a CLI in or out of circulation.
     *
     * Deliberately not blocked when agents still bind it (design D9): the switch exists to stop a CLI
     * that turned out to be a problem, and an operator who has to unbind every agent first cannot do
     * that in one action. The blast radius is visible instead — the page asks
     * `GET /{id}/related-agents` before it confirms.
     *
     * The shipped skill follows the same value (I5). Registering a CLI and teaching it are one offer,
     * so half of it staying live would leave agents told to use a command their sandbox no longer has.
     * Following has one exception: enabling does not lift a content-scan quarantine, which is a
     * reviewer's decision about the skill and not a side effect of switching the CLI back on.
     */
    @Transactional(rollbackFor = [Exception::class])
    override fun toggleCliStatus(
        id: Long,
        status: Int,
    ): Boolean {
        SkillSourcePolicy.requireStatus(status)
        val cli = cliMapper.selectById(id) ?: throw BizException("CLI not found")
        val changed = cliMapper.updateStatus(id, status) > 0
        cli.skillId?.let { skillId ->
            val followed = if (status == ENABLED && isQuarantined(skillId)) DISABLED else status
            if (skillMapper.updateStatus(skillId, followed) == 0) {
                // The registrar owns that row and rewrites its status on the next package change, so a
                // missing row means the skill was deleted from under the CLI rather than that the
                // switch failed. Reported, not fatal.
                log.warn("CLI {} (id={}) points at skill {} which is gone; its status could not follow", cli.name, id, skillId)
            }
        }
        return changed
    }

    /**
     * Whether the skill stored under [skillId] still trips the scan that put it in quarantine.
     *
     * `SkillContentScanner` runs once, at registration, and its verdict survives only as the resulting
     * `status` — so without reading the content back, the second writer of that column would also be
     * a way to approve what the first one refused. Content that cannot be read counts as quarantined:
     * the cost of that answer is one deliberate enable from the skill page, the cost of the other
     * answer is a live skill holding a `curl | sh`.
     */
    private fun isQuarantined(skillId: Long): Boolean {
        val skill = skillMapper.selectById(skillId) ?: return false
        val resources = try {
            if (skill.resources.isBlank()) emptyMap() else objectMapper.readValue(skill.resources, RESOURCES_TYPE)
        } catch (e: Exception) {
            log.warn("Skill {} carries unreadable resources, so it stays quarantined: {}", skillId, e.message)
            return true
        }
        return SkillContentScanner.scan(skill.skillmd, resources).isNotEmpty()
    }

    override fun convertToResponse(cli: Cli): CliResponse {
        val skill = cli.skillId?.let { skillMapper.selectById(it) }
        return CliResponse.fromEntity(cli, objectMapper, secretFieldEncryptor).copy(
            skill = skill?.let {
                CliResponse.SkillItem(
                    skillId = it.id,
                    skillName = it.name,
                    skillDescription = it.description,
                )
            },
        )
    }

    /**
     * The drawer's shape. [CliResponse.payloadDigest] / [CliResponse.depsApt] / [CliResponse.runtimeEnv]
     * are left null by the page on purpose, so this is the only route that answers with them.
     */
    override fun convertToDetailResponse(cli: Cli): CliResponse = convertToResponse(cli).copy(
        payloadDigest = cli.payloadDigest.takeIf { it.isNotBlank() },
        depsApt = readManifest(cli.depsApt, DEPS_APT_TYPE),
        runtimeEnv = readManifest(cli.runtimeEnv, RUNTIME_ENV_TYPE),
    )

    /**
     * Reads one of the two manifest JSON columns, tolerating a value that does not parse.
     *
     * `CliPackageAutoRegistrar` is the only writer and it always writes JSON it just produced, so an
     * unreadable column means a row that did not come from a package. Failing the whole detail read for
     * it would hide the rest of the row from the operator who now needs to see it.
     */
    private fun <T> readManifest(
        json: String?,
        type: TypeReference<T>,
    ): T? {
        if (json.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(json, type)
        } catch (e: Exception) {
            log.warn("A CLI manifest column is unreadable, so the detail view shows it empty: {}", e.message)
            null
        }
    }

    companion object {
        private const val ENABLED = 1
        private const val DISABLED = 0

        /** The `skill.resources` column: `relativePath -> file content`, the shape the scanner reads. */
        private val RESOURCES_TYPE = object : TypeReference<Map<String, String>>() {}

        /** The `cli.deps_apt` column: the `apt` list of `plugin.yaml`. */
        private val DEPS_APT_TYPE = object : TypeReference<List<String>>() {}

        /** The `cli.runtime_env` column: `slot -> platform expression`, the slots filled per container. */
        private val RUNTIME_ENV_TYPE = object : TypeReference<Map<String, String>>() {}
    }
}
