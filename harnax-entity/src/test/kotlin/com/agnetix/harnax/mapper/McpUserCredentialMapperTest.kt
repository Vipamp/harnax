package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.McpUserCredential
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
 * McpUserCredentialMapper Integration Tests
 *
 * 这张表存的是「用户 x MCP 服务」的令牌密文，一个用户一个服务只允许一行：撤销要在原行上把密文写成
 * NULL，重新授权要覆盖同一行，所以「判空即跳过」的 update 写法在这里是错的，必须实测。
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class McpUserCredentialMapperTest {

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
    private lateinit var mcpUserCredentialMapper: McpUserCredentialMapper

    private fun insertCredential(
        tenantId: Long,
        userId: Long,
        mcpId: Long,
        status: String = McpUserCredential.STATUS_ACTIVE,
    ): McpUserCredential {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        return McpUserCredential().apply {
            this.tenantId = tenantId
            this.userId = userId
            this.mcpId = mcpId
            accessTokenEnc = "aes:access-$userId-$mcpId"
            refreshTokenEnc = "aes:refresh-$userId-$mcpId"
            accessExpiresAt = now.plusHours(1)
            scopes = "mcp:read mcp:write"
            this.status = status
            createTime = now
            updateTime = now
        }.also { mcpUserCredentialMapper.insert(it) }
    }

    @Nested
    @DisplayName("读写与撤销测试")
    inner class RoundTripTests {

        @Test
        @DisplayName("insert + selectByUserAndMcp - 密文与过期时间原样读回")
        fun `insert should persist the ciphertext and expiry`() {
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val credential = McpUserCredential().apply {
                tenantId = 1L
                userId = 100L
                mcpId = 200L
                accessTokenEnc = "aes:at"
                refreshTokenEnc = "aes:rt"
                accessExpiresAt = now.plusHours(2)
                scopes = "mcp:read"
                status = McpUserCredential.STATUS_ACTIVE
                lastRefreshedAt = now
                createTime = now
                updateTime = now
            }

            assertEquals(1, mcpUserCredentialMapper.insert(credential))
            assertTrue(credential.id > 0)

            val loaded = mcpUserCredentialMapper.selectByUserAndMcp(1L, 100L, 200L)
            assertNotNull(loaded)
            assertEquals("aes:at", loaded.accessTokenEnc)
            assertEquals("aes:rt", loaded.refreshTokenEnc)
            assertEquals(now.plusHours(2), loaded.accessExpiresAt)
            assertEquals("mcp:read", loaded.scopes)
            assertEquals(McpUserCredential.STATUS_ACTIVE, loaded.status)
            assertEquals(now, loaded.lastRefreshedAt)
        }

        @Test
        @DisplayName("updateById - 撤销能把两个密文都写回 NULL")
        fun `updateById should null out both tokens on revoke`() {
            // Given - 撤销必须真的落库：SET 列表一旦判空跳过，库里就还留着一把「能用但管理员以为已撤销」的令牌
            insertCredential(1L, 101L, 201L)
            val loaded = mcpUserCredentialMapper.selectByUserAndMcp(1L, 101L, 201L)!!

            // When
            loaded.accessTokenEnc = null
            loaded.refreshTokenEnc = null
            loaded.accessExpiresAt = null
            loaded.status = McpUserCredential.STATUS_REVOKED
            loaded.lastError = "user revoked"
            mcpUserCredentialMapper.updateById(loaded)

            // Then
            val after = mcpUserCredentialMapper.selectByUserAndMcp(1L, 101L, 201L)
            assertNotNull(after)
            assertEquals(loaded.id, after.id)
            assertNull(after.accessTokenEnc)
            assertNull(after.refreshTokenEnc)
            assertNull(after.accessExpiresAt)
            assertEquals(McpUserCredential.STATUS_REVOKED, after.status)
            assertEquals("user revoked", after.lastError)
        }

        @Test
        @DisplayName("updateById - 重新授权覆盖同一行")
        fun `updateById should overwrite the grant in place`() {
            val first = insertCredential(1L, 102L, 202L)

            val loaded = mcpUserCredentialMapper.selectByUserAndMcp(1L, 102L, 202L)!!
            loaded.accessTokenEnc = "aes:at-2"
            loaded.refreshTokenEnc = "aes:rt-2"
            loaded.scopes = "mcp:read"
            loaded.status = McpUserCredential.STATUS_ACTIVE
            mcpUserCredentialMapper.updateById(loaded)

            val after = mcpUserCredentialMapper.selectByUserAndMcp(1L, 102L, 202L)
            assertNotNull(after)
            assertEquals(first.id, after.id)
            assertEquals("aes:at-2", after.accessTokenEnc)
            assertEquals("mcp:read", after.scopes)
        }

        @Test
        @DisplayName("selectByUserAndMcp - 认租户，跨租户的同号用户不算同一个人")
        fun `selectByUserAndMcp should require the tenant`() {
            insertCredential(21L, 103L, 203L)

            assertNotNull(mcpUserCredentialMapper.selectByUserAndMcp(21L, 103L, 203L))
            assertNull(mcpUserCredentialMapper.selectByUserAndMcp(22L, 103L, 203L))
        }
    }

    @Nested
    @DisplayName("唯一键与级联清理测试")
    inner class UniqueKeyAndCascadeTests {

        @Test
        @DisplayName("uk_mcp_user_credential_tenant_user_mcp - 一人一服务只有一行")
        fun `unique key should keep one row per user and server`() {
            insertCredential(23L, 104L, 204L)

            assertThrows<DuplicateKeyException> { insertCredential(23L, 104L, 204L) }
            assertNotNull(insertCredential(24L, 104L, 204L))
            assertNotNull(insertCredential(23L, 105L, 204L))
            assertNotNull(insertCredential(23L, 104L, 205L))
        }

        @Test
        @DisplayName("deleteByMcpId - 删服务时硬删该服务的全部凭据")
        fun `deleteByMcpId should remove every credential of the server`() {
            insertCredential(25L, 106L, 206L)
            insertCredential(25L, 107L, 206L)
            insertCredential(25L, 106L, 207L)

            // When - 这张表没有 active 列，服务没了令牌也就没了留下的理由
            assertEquals(2, mcpUserCredentialMapper.deleteByMcpId(206L))

            // Then
            assertNull(mcpUserCredentialMapper.selectByUserAndMcp(25L, 106L, 206L))
            assertNull(mcpUserCredentialMapper.selectByUserAndMcp(25L, 107L, 206L))
            assertNotNull(mcpUserCredentialMapper.selectByUserAndMcp(25L, 106L, 207L))
        }

        @Test
        @DisplayName("deleteByMcpId - 没有凭据时返回 0 而不是报错")
        fun `deleteByMcpId should return zero when nothing matched`() {
            assertEquals(0, mcpUserCredentialMapper.deleteByMcpId(999_999L))
        }
    }
}
