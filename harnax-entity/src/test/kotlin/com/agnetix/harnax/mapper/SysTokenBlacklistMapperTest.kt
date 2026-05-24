package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SysTokenBlacklist
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
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
import kotlin.test.assertTrue

@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SysTokenBlacklistMapperTest {

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
    private lateinit var sysTokenBlacklistMapper: SysTokenBlacklistMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询黑名单")
        fun `selectById should return blacklist by id`() {
            val blacklist = sysTokenBlacklistMapper.selectById(1L)
            assertNotNull(blacklist)
            assertEquals(1L, blacklist.id)
            assertEquals("testuser1", blacklist.username)
            assertEquals(1L, blacklist.userId)
            assertEquals("logout", blacklist.reason)
            assertEquals("hash001", blacklist.tokenHash)
        }

        @Test
        @DisplayName("insert - 插入新黑名单")
        fun `insert should create new blacklist`() {
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newBlacklist = SysTokenBlacklist().apply {
                token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.new"
                tokenHash = "hash-new"
                username = "testuser1"
                userId = 1L
                reason = "logout"
                expireTime = now.plusHours(24)
                createTime = now
                createIp = "192.168.1.100"
            }

            val result = sysTokenBlacklistMapper.insert(newBlacklist)
            assertEquals(1, result)
            assertTrue(newBlacklist.id > 0)

            val insertedBlacklist = sysTokenBlacklistMapper.selectById(newBlacklist.id)
            assertNotNull(insertedBlacklist)
            assertEquals("hash-new", insertedBlacklist.tokenHash)
        }
    }
}
