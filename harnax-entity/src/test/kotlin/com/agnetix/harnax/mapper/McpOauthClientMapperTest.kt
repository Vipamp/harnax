package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.McpOauthClient
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DuplicateKeyException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * McpOauthClientMapper Integration Tests
 *
 * 这张表存的是「租户 x 授权服务器」的客户端注册，一个 issuer 上配错一次就会被所有 MCP 服务共用，
 * 所以复用规则、唯一键和「NULL 表示真的没有这个端点」三件事必须在真实 MySQL 上验一遍。
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class McpOauthClientMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var mcpOauthClientMapper: McpOauthClientMapper

    private fun insertClient(
        tenantId: Long,
        issuer: String,
        clientId: String,
        active: Int = 1,
    ): McpOauthClient {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        return McpOauthClient().apply {
            this.tenantId = tenantId
            this.issuer = issuer
            this.clientId = clientId
            callbackUrl = "http://localhost:8080/api/admin/mcp/oauth/callback"
            creator = "admin"
            this.active = active
            createTime = now
            updateTime = now
        }.also { mcpOauthClientMapper.insert(it) }
    }

    @Nested
    @DisplayName("读写与发现快照测试")
    inner class RoundTripTests {

        @Test
        @DisplayName("insert + selectByTenantAndIssuer - 发现结果原样读回")
        fun `insert should persist the discovery snapshot`() {
            // Given - 端点快照是为了运行时不再依赖 AS 的 metadata 可用
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val client = McpOauthClient().apply {
                tenantId = 1L
                issuer = "https://auth.example.com/realms/x"
                clientId = "cid-1"
                clientSecretEnc = "aes:secret"
                registrationSource = "DCR"
                authorizationEndpoint = "https://auth.example.com/realms/x/protocol/openid-connect/auth"
                tokenEndpoint = "https://auth.example.com/realms/x/protocol/openid-connect/token"
                revocationEndpoint = "https://auth.example.com/realms/x/protocol/openid-connect/revoke"
                scopesSupported = """["mcp:read","mcp:write"]"""
                callbackUrl = "http://localhost:8080/api/admin/mcp/oauth/callback"
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            assertEquals(1, mcpOauthClientMapper.insert(client))
            assertTrue(client.id > 0)
            val loaded = mcpOauthClientMapper.selectByTenantAndIssuer(1L, "https://auth.example.com/realms/x")

            // Then
            assertNotNull(loaded)
            assertEquals("cid-1", loaded.clientId)
            assertEquals("aes:secret", loaded.clientSecretEnc)
            assertEquals("DCR", loaded.registrationSource)
            assertEquals(client.tokenEndpoint, loaded.tokenEndpoint)
            assertEquals(client.revocationEndpoint, loaded.revocationEndpoint)
            assertEquals(client.scopesSupported, loaded.scopesSupported)
        }

        @Test
        @DisplayName("updateById - 写 NULL 就是没有这个端点，不是「没改」")
        fun `updateById should be able to clear an endpoint`() {
            // Given - SET 列表不做 <if> 判空，否则 AS 撤掉 DCR 后旧端点会一直留在库里被继续调用
            val client = insertClient(1L, "https://as.example.com", "cid-clear")
            val loaded = mcpOauthClientMapper.selectByTenantAndIssuer(1L, "https://as.example.com")!!
            loaded.registrationEndpoint = "https://as.example.com/register"
            mcpOauthClientMapper.updateById(loaded)
            assertEquals("https://as.example.com/register", mcpOauthClientMapper.selectByTenantAndIssuer(1L, "https://as.example.com")!!.registrationEndpoint)

            // When
            loaded.registrationEndpoint = null
            loaded.clientSecretEnc = null
            mcpOauthClientMapper.updateById(loaded)

            // Then
            val after = mcpOauthClientMapper.selectByTenantAndIssuer(1L, "https://as.example.com")
            assertNotNull(after)
            assertNull(after.registrationEndpoint)
            assertNull(after.clientSecretEnc)
            assertEquals(client.clientId, after.clientId)
        }

        @Test
        @DisplayName("updateById - 不改 tenant_id 和 issuer")
        fun `updateById should leave the row identity alone`() {
            insertClient(1L, "https://as.example.com", "cid-id")
            val loaded = mcpOauthClientMapper.selectByTenantAndIssuer(1L, "https://as.example.com")!!
            loaded.clientId = "cid-id-renamed"

            mcpOauthClientMapper.updateById(loaded)

            // Then - 身份列不在 SET 里，换 client_id 后仍然按原 issuer 查得到
            val after = mcpOauthClientMapper.selectByTenantAndIssuer(1L, "https://as.example.com")
            assertNotNull(after)
            assertEquals("cid-id-renamed", after.clientId)
            assertEquals(1L, after.tenantId)
            assertEquals("https://as.example.com", after.issuer)
        }
    }

    @Nested
    @DisplayName("复用与唯一键测试")
    inner class ReuseAndUniqueKeyTests {

        @Test
        @DisplayName("selectByTenantAndIssuer - issuer 精确匹配，差一个斜杠就是另一个 AS")
        fun `selectByTenantAndIssuer should match the issuer exactly`() {
            insertClient(1L, "https://as.example.com", "cid-exact")

            assertNotNull(mcpOauthClientMapper.selectByTenantAndIssuer(1L, "https://as.example.com"))
            // 归一化留给上层显式决定，库里不做 trim/大小写折叠，否则两个真不同的 AS 会被并成一个
            assertNull(mcpOauthClientMapper.selectByTenantAndIssuer(1L, "https://as.example.com/"))
            assertNull(mcpOauthClientMapper.selectByTenantAndIssuer(1L, "HTTPS://AS.example.com"))
        }

        @Test
        @DisplayName("selectByTenantAndIssuer - 同 issuer 多条登记时取 id 最小的")
        fun `selectByTenantAndIssuer should pick the lowest id deterministically`() {
            // Given - 唯一键允许同 issuer 挂不同 client_id，此时必须有确定答案
            val first = insertClient(7L, "https://multi.example.com", "cid-a")
            insertClient(7L, "https://multi.example.com", "cid-b")

            val picked = mcpOauthClientMapper.selectByTenantAndIssuer(7L, "https://multi.example.com")
            assertNotNull(picked)
            assertEquals(first.id, picked.id)
            assertEquals("cid-a", picked.clientId)
        }

        @Test
        @DisplayName("selectByTenantAndIssuer - 被删的登记不再复用")
        fun `selectByTenantAndIssuer should skip inactive registrations`() {
            val client = insertClient(8L, "https://gone.example.com", "cid-gone")
            val loaded = mcpOauthClientMapper.selectByTenantAndIssuer(8L, "https://gone.example.com")!!
            loaded.active = 0
            mcpOauthClientMapper.updateById(loaded)

            assertNull(mcpOauthClientMapper.selectByTenantAndIssuer(8L, "https://gone.example.com"))

            // Then - 行本身还在，只是不再被复用：把它改回可用就又能查到同一行
            loaded.active = 1
            mcpOauthClientMapper.updateById(loaded)
            val revived = mcpOauthClientMapper.selectByTenantAndIssuer(8L, "https://gone.example.com")
            assertNotNull(revived)
            assertEquals(client.id, revived.id)
        }

        @Test
        @DisplayName("uk_mcp_oauth_client_tenant_issuer_client - 同租户同 issuer 不允许重复登记同一个 client")
        fun `unique key should reject a duplicate registration`() {
            insertClient(9L, "https://dup.example.com", "cid-dup")

            assertThrows<DuplicateKeyException> { insertClient(9L, "https://dup.example.com", "cid-dup") }
            // 换租户或换 client_id 都不冲突
            assertNotNull(insertClient(10L, "https://dup.example.com", "cid-dup"))
            assertNotNull(insertClient(9L, "https://dup.example.com", "cid-other"))
        }

        @Test
        @DisplayName("生成列 - 软删后可以把同一个 client_id 重新登记回来")
        fun `generated column should allow re-registration after soft delete`() {
            val client = insertClient(11L, "https://re.example.com", "cid-re")
            val loaded = mcpOauthClientMapper.selectByTenantAndIssuer(11L, "https://re.example.com")!!
            loaded.active = 0
            mcpOauthClientMapper.updateById(loaded)

            // 与 mcp_server 的 V15/V23 同一写法：active=0 时生成列为 NULL，唯一索引忽略 NULL
            assertNotNull(insertClient(11L, "https://re.example.com", "cid-re"))
            assertEquals(1, mcpOauthClientMapper.selectByTenantAndIssuer(11L, "https://re.example.com")?.active)
            assertEquals(client.id + 1, mcpOauthClientMapper.selectByTenantAndIssuer(11L, "https://re.example.com")?.id)
        }
    }
}
