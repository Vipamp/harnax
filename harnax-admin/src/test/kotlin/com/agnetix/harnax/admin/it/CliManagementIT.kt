package com.agnetix.harnax.admin.it

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.entity.AgentCliBinding
import com.agnetix.harnax.entity.Cli
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.mapper.AgentCliBindingMapper
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CLI 管理面 HTTP 回归：/api/admin/clis
 *
 * 这一面只剩读和开关两件事，所以用例也就这两件：注册行的读取与详情（含包摘要、自带技能）、
 * 停用开关，以及手工录入入口确实已经从路由上消失。
 *
 * 行由 [com.agnetix.harnax.admin.registrar.CliPackageAutoRegistrar] 在启动时从包目录登记，这里直接用
 * mapper 种一行代替——本类验证的是 HTTP 契约。例外是 Order(8) 与 Order(9)：`status` 不被换包复位、
 * 删行只删点名的那几行，这两条护栏都长在 SQL 里，而单测 mock 的正是 mapper 本身，所以只有真库能证明。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CliManagementIT : BaseAdminIT() {

    @Autowired
    private lateinit var cliMapper: CliMapper

    @Autowired
    private lateinit var skillMapper: SkillMapper

    @Autowired
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    @Autowired
    private lateinit var agentCliBindingMapper: AgentCliBindingMapper

    private val suffix = Random.nextInt(100000, 999999)
    private val cliName = "it_cli_$suffix"

    /** `agent_cli_binding` 没有外键，这一行只需要指向本 CLI 就够。 */
    private val boundAgentId = 900_000_000L + suffix

    /** Must match `admin.internal-api.secret` in `application-it.yml`, as in [InternalApiIT]. */
    private val internalSecret = "it-internal-api-secret-0123456789abcdef"

    // Deliberately not `packageDigest`/`skillId`: inside `Cli().apply {}` those names resolve to the
    // entity's own properties, so the seed would assign the default to itself and pass silently.
    private val pkgDigest = "c".repeat(64)

    private var cliId = -1L
    private var shippedSkillId = -1L
    private var modelId = -1L

    /** 种一行「已登记的包」：一个 cli 行加上它 skill_id 指向的自带技能 */
    @Test
    @Order(1)
    fun `register a package row`() {
        val row = seedPackage(cliName)
        cliId = row.id
        shippedSkillId = requireNotNull(row.skillId)
        assertTrue(cliId > 0, "the registered row should be readable back by name")
    }

    /**
     * 走登记器写的那几条语句种一行，返回库里读回来的行（含 id）。
     *
     * 手动 mapper 插入而非启动登记器：IT 环境没有包目录也没有对象存储，而这里要验证的是 SQL 本身。
     */
    private fun seedPackage(
        name: String,
        digest: String = pkgDigest,
    ): Cli {
        val repository = skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)
        assertNotNull(repository, "the managed CLI skill repository must exist")
        val skill = Skill().apply {
            tenantId = repository.tenantId
            this.name = name
            repositoryId = repository.id
            description = "IT shipped skill"
            skillmd = "# $name"
            resources = "{}"
            version = "1.0.0"
            status = 1
            isPublic = 1
            creator = "SYSTEM"
            active = 1
        }
        skillMapper.insert(skill)

        val row = packageRow().apply {
            this.name = name
            skillId = skill.id
            packageDigest = digest
            packageObject = "$name/$digest.harnaxcli.zip"
        }
        cliMapper.upsertCliPackage(row)
        return assertNotNull(cliMapper.selectByName(name), "the registered row should be readable back by name")
    }

    /** 一行「已登记的包」，即登记器会写进 `cli` 的全部列。 */
    private fun packageRow(manifestVersion: String = "1.0.0"): Cli = Cli().apply {
        name = cliName
        description = "IT cli"
        version = manifestVersion
        checkCommand = "it-cli --version"
        this.skillId = shippedSkillId
        this.packageDigest = pkgDigest
        payloadDigest = "d".repeat(64)
        packageObject = "$cliName/$pkgDigest.harnaxcli.zip"
        depsApt = """["curl"]"""
        runtimeEnv = """{"IT_URL":"${'$'}{platform.adminUrl}"}"""
        envParams = """[{"envParamName":"IT_TOKEN","secret":true}]"""
        status = 1
    }

    @Test
    @Order(2)
    fun `page and detail show the registered package`() {
        val record = findInPage("/api/admin/clis/page", "name=$cliName") {
            it["name"]?.asText() == cliName
        }
        assertNotNull(record, "registered cli should appear in the page result")

        val data = assertOk(getJson("/api/admin/clis/$cliId"))
        assertEquals(cliName, data["name"].asText())
        assertEquals("IT cli", data["description"].asText())
        assertEquals("1.0.0", data["version"].asText())
        assertEquals("it-cli --version", data["checkCommand"].asText())
        assertEquals(pkgDigest, data["packageDigest"].asText())
        assertEquals(1, data["status"].asInt())
        // 自带技能是包的一部分，详情页直接带出来，不再有一张绑定表可查
        assertEquals(shippedSkillId, data["skill"]["skillId"].asLong())
        assertEquals(cliName, data["skill"]["skillName"].asText())
        // secret 的默认值只能以掩码出现
        val envParams = data["envParams"]
        assertEquals(1, envParams.size())
        assertEquals("IT_TOKEN", envParams[0]["envParamName"].asText())

        // These plugin.yaml columns existed only in the library; the detail endpoint is their one exit (CLI-05②)
        assertEquals("d".repeat(64), data["payloadDigest"].asText())
        assertEquals("curl", data["depsApt"][0].asText())
        assertEquals("\${platform.adminUrl}", data["runtimeEnv"]["IT_URL"].asText())
        // The page shape deliberately carries none of them, so reading them really does need this endpoint
        assertNull(record["payloadDigest"])
        assertNull(record["depsApt"])
        assertNull(record["runtimeEnv"])
    }

    @Test
    @Order(3)
    fun `manual write routes are gone`() {
        // 发布一个 CLI 现在是往包目录放一个 .harnaxcli.zip，不是往表单里填安装脚本（设计 D2）。
        // 断言的是「没有路由」，不是「路由拒绝了我」：404/405 才说明入口真的关掉了
        assertNotRouted(postJson("/api/admin/clis", mapOf("name" to "it_cli_x", "installScript" to "RUN echo x")))
        assertNotRouted(putJson("/api/admin/clis/update/$cliId", mapOf("description" to "hijacked")))
        assertNotRouted(deleteJson("/api/admin/clis/$cliId"))

        // 上一代的插件接口同样已经不存在
        assertNotRouted(getJson("/api/admin/cli-plugins/page?pageNum=1&pageSize=10"))
    }

    @Test
    @Order(4)
    fun `disabling a cli takes its shipped skill with it`() {
        assertOk(putJson("/api/admin/clis/toggle/$cliId?status=0"))

        assertEquals(0, cliMapper.selectById(cliId)?.status, "cli.status should be switched off")
        // I5：技能不会比它的 CLI 活得更久，否则 agent 会被教去用一个沙箱里已经不存在的命令
        assertEquals(0, skillMapper.selectById(shippedSkillId)?.status, "the shipped skill follows cli.status")

        assertOk(putJson("/api/admin/clis/toggle/$cliId?status=1"))
        assertEquals(1, skillMapper.selectById(shippedSkillId)?.status)
    }

    /**
     * `createAgent` 要求 modelId 非空，缺了会在写绑定表之前就 NPE，所以先按 API 建一个可用的模型。
     */
    private fun ensureModelId(): Long {
        if (modelId > 0) return modelId
        val provider = mapOf("type" to "it_cli_mp_$suffix", "name" to "it_cli_provider_$suffix")
        assertOk(postJson("/api/admin/model-providers", provider))
        val providerRecord = findInPage("/api/admin/model-providers/page", "name=it_cli_provider_$suffix") {
            it["name"]?.asText() == "it_cli_provider_$suffix"
        }
        assertNotNull(providerRecord, "prerequisite provider should exist")

        val body = mapOf(
            "name" to "it_cli_model_$suffix",
            "modelName" to "it-cli-model-$suffix",
            "providerId" to providerRecord["id"].asLong(),
            "modelType" to "chat",
        )
        assertOk(postJson("/api/admin/models", body))
        val record = findInPage("/api/admin/models/page", "name=it_cli_model_$suffix") {
            it["name"]?.asText() == "it_cli_model_$suffix"
        }
        assertNotNull(record, "prerequisite model should exist")
        modelId = record["id"].asLong()
        return modelId
    }

    @Test
    @Order(5)
    fun `disabling is not blocked by agents that bind the cli`() {
        val agent = mapOf(
            "name" to "it_cli_agent_$suffix",
            "description" to "IT cli holder",
            "systemPrompt" to "You are an IT agent.",
            "modelId" to ensureModelId(),
            "status" to 1,
            "cliList" to listOf(mapOf("id" to cliId)),
        )
        assertOk(postJson("/api/admin/agents", agent))
        val related = assertOk(getJson("/api/admin/clis/$cliId/related-agents"))
        assertTrue(related.size() > 0, "the bound agent should be listed")

        // 设计 D9：开关的意义就是当场停用出问题的 CLI，前置条件是「先解开所有 agent」的话就停不了
        assertOk(putJson("/api/admin/clis/toggle/$cliId?status=0"))
        assertOk(putJson("/api/admin/clis/toggle/$cliId?status=1"))

        val relatedSessions = assertOk(getJson("/api/admin/clis/$cliId/related-sessions"))
        assertTrue(relatedSessions.isArray)

        related.forEach { assertOk(deleteJson("/api/admin/agents/${it["agentId"].asLong()}")) }
    }

    @Test
    @Order(6)
    fun `toggle of an unknown cli fails`() {
        assertErr(putJson("/api/admin/clis/toggle/99999999?status=0"))
    }

    @Test
    @Order(7)
    fun `cli routes without a token return 401`() {
        val page = exchange(HttpMethod.GET, "/api/admin/clis/page?pageNum=1&pageSize=1", token = null)
        assertEquals(401, page.statusCode.value())

        val toggle = exchange(HttpMethod.PUT, "/api/admin/clis/toggle/$cliId?status=0", token = null)
        assertEquals(401, toggle.statusCode.value())
    }

    /**
     * `status` 是这张表里唯一不属于包的列（设计 D9），换包不能把它复位。
     *
     * 护栏写在 `upsertCliPackage` 的 ON DUPLICATE 列表里（那里没有 status），所以只有真库能证明它：
     * 单测 mock 的正是 mapper 本身。行 id 不变是同一句话的另一半——`agent_cli_binding` 认的是行，
     * 不是版本，一次升级不该把所有绑定重置。
     */
    @Test
    @Order(8)
    fun `a package upgrade keeps the operator switch and the row identity`() {
        val binding = AgentCliBinding().apply {
            agentId = boundAgentId
            cliId = this@CliManagementIT.cliId
            envBindings = """[{"envParamName":"IT_TOKEN","envParamValue":"v"}]"""
        }
        agentCliBindingMapper.batchInsert(listOf(binding))
        assertOk(putJson("/api/admin/clis/toggle/$cliId?status=0"))

        cliMapper.upsertCliPackage(packageRow(manifestVersion = "1.0.1"))

        val after = cliMapper.selectById(cliId)
        assertEquals(0, after?.status, "a new package must not re-arm a CLI the operator switched off")
        assertEquals("1.0.1", after?.version, "the manifest columns do move")
        assertEquals(cliId, agentCliBindingMapper.selectByCliId(cliId).single().cliId, "the row keeps its id")
        agentCliBindingMapper.deleteByAgentId(boundAgentId)
    }

    /**
     * 删行是登记器在这个域里唯一会带走数据的动作，而它的护栏就是 `WHERE id IN (…)` 那一串。
     *
     * 单测 mock 的正是 mapper 接口本身，所以只有真库能证明「点名的删掉、没点名的一个不动」——
     * 后者是这里更有价值的一半：一次没挂上卷的启动不该把所有 CLI 连绑定一起抹掉。
     */
    @Test
    @Order(9)
    fun `prune deletes exactly the rows it names`() {
        val doomed = seedPackage("it_cli_gone_$suffix")
        val doomedSkill = requireNotNull(doomed.skillId)
        agentCliBindingMapper.batchInsert(listOf(bindingOf(boundAgentId, cliId), bindingOf(boundAgentId + 1, doomed.id)))

        agentCliBindingMapper.deleteByCliIds(listOf(doomed.id))
        skillMapper.deleteById(doomedSkill)
        cliMapper.deleteByIds(listOf(doomed.id))

        assertNull(cliMapper.selectById(doomed.id), "the named cli row goes")
        assertNull(skillMapper.selectById(doomedSkill), "the skill it shipped goes with it")
        assertTrue(agentCliBindingMapper.selectByCliId(doomed.id).isEmpty(), "its agent bindings are cleared")

        assertNotNull(cliMapper.selectById(cliId), "an unlisted cli row survives")
        assertNotNull(skillMapper.selectById(shippedSkillId), "an unlisted skill survives")
        assertTrue(agentCliBindingMapper.selectByCliId(cliId).isNotEmpty(), "an unlisted binding survives")

        agentCliBindingMapper.deleteByAgentId(boundAgentId)
        agentCliBindingMapper.deleteByAgentId(boundAgentId + 1)
    }

    /**
     * The whitelist the runtime's reclaim sweeps delete by, read out of the database it is built from.
     *
     * `InternalApiControllerTest` stubs the two mappers this endpoint composes, so the SQL is what no test
     * has seen: `selectCliList` answers with `SELECT *` through a resultMap, and a column that failed to
     * map would come back empty — and an empty `packageDigest` is dropped from the list, which a host reads
     * as "unused" and deletes. The same silent narrowing renames an image tag, and a tag that is not on the
     * whitelist is a live agent's sandbox removed.
     */
    @Test
    @Order(10)
    fun `the reclaim inventory carries the rows the sweeps delete by`() {
        val live = seedPackage("it_cli_inv_on_$suffix", digest = "e".repeat(64))
        val switchedOff = seedPackage("it_cli_inv_off_$suffix", digest = "f".repeat(64))
        assertOk(putJson("/api/admin/clis/toggle/${switchedOff.id}?status=0"))
        val agentId = boundAgentId + 20
        agentCliBindingMapper.batchInsert(listOf(bindingOf(agentId, live.id), bindingOf(agentId, switchedOff.id)))

        val data = assertOk(
            parseBody(exchange(HttpMethod.GET, "/api/admin/internal/cli/inventory", token = internalSecret)),
        )
        val digests = data["packageDigests"].map { it.asText() }
        val switchedOffDigest = requireNotNull(switchedOff.packageDigest)
        // The kill switch is not a deletion: the archive and the payload tree survive it, so the cache must
        // not be told to drop them — a re-enable would have to refetch bytes nobody can rewrite.
        assertTrue(switchedOffDigest in digests, "a disabled package is still a registered package")
        assertTrue(requireNotNull(live.packageDigest) in digests)

        val cliSet = data["agentCliSets"].first { it["agentId"].asLong() == agentId }
        assertEquals(
            listOf(live.id),
            cliSet["clis"].map { it["id"].asLong() },
            "a switched-off CLI has no image to protect",
        )
        val cli = cliSet["clis"].single()
        // The tag hashes exactly these values, so each one has to reach the wire as the row holds it.
        assertEquals(live.version, cli["version"].asText())
        assertEquals(live.payloadDigest, cli["payloadDigest"].asText())

        agentCliBindingMapper.deleteByAgentId(agentId)
    }

    private fun bindingOf(
        agentId: Long,
        cliId: Long,
    ): AgentCliBinding = AgentCliBinding().apply {
        this.agentId = agentId
        this.cliId = cliId
        envBindings = "[]"
    }

    private fun assertNotRouted(node: tools.jackson.databind.JsonNode) {
        val code = node["code"]?.asInt() ?: 0
        assertTrue(
            code == 404 || code == 405,
            "expected the route to be unmapped (404/405) but the response was: $node",
        )
    }
}
