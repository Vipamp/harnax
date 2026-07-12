package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SkillRepository
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
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
 * SkillRepositoryMapper 集成测试
 *
 * @author agnetix
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class SkillRepositoryMapperTest {

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
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询技能仓库")
        fun `selectById should return skill repository by id`() {
            // When
            val repository = skillRepositoryMapper.selectById(1L)

            // Then
            assertNotNull(repository)
            assertEquals(1L, repository.id)
            assertEquals("Default Repository", repository.name)
            assertEquals("https://github.com/agnetix/skills", repository.url)
            assertEquals("main", repository.branch)
            assertEquals(1, repository.status)
            assertEquals(1, repository.active)
        }

        @Test
        @DisplayName("selectById - 查询不存在的仓库返回 null")
        fun `selectById should return null when repository not exists`() {
            // When
            val repository = skillRepositoryMapper.selectById(999L)

            // Then
            assertNull(repository)
        }

        @Test
        @DisplayName("selectById - 不返回已删除的仓库")
        fun `selectById should not return deleted repository`() {
            // When
            val repository = skillRepositoryMapper.selectById(3L)

            // Then
            assertNull(repository)
        }

        @Test
        @DisplayName("insert - 插入新仓库")
        fun `insert should create new repository`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newRepository = SkillRepository().apply {
                name = "New Repository"
                url = "https://github.com/agnetix/new-skills"
                branch = "main"
                description = "新技能仓库"
                status = 1
                isPublic = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = skillRepositoryMapper.insert(newRepository)

            // Then
            assertEquals(1, result)
            assertTrue(newRepository.id > 0)

            val insertedRepository = skillRepositoryMapper.selectById(newRepository.id)
            assertNotNull(insertedRepository)
            assertEquals("New Repository", insertedRepository.name)
        }

        @Test
        @DisplayName("updateById - 更新仓库信息")
        fun `updateById should update repository info`() {
            // Given
            val repositoryId = 1L
            val repository = skillRepositoryMapper.selectById(repositoryId)
            assertNotNull(repository)

            // When
            repository.name = "Updated Repository"
            repository.description = "更新后的描述"
            repository.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = skillRepositoryMapper.updateById(repository)

            // Then
            assertEquals(1, result)
            val updatedRepository = skillRepositoryMapper.selectById(repositoryId)
            assertNotNull(updatedRepository)
            assertEquals("Updated Repository", updatedRepository.name)
        }

        @Test
        @DisplayName("deleteById - 逻辑删除仓库")
        fun `deleteById should logically delete repository`() {
            // Given
            val repositoryId = 2L
            val repositoryBefore = skillRepositoryMapper.selectById(repositoryId)
            assertNotNull(repositoryBefore)

            // When
            val result = skillRepositoryMapper.deleteById(repositoryId)

            // Then
            assertEquals(1, result)
            val deletedRepository = skillRepositoryMapper.selectById(repositoryId)
            assertNull(deletedRepository)
        }
    }

    @Nested
    @DisplayName("状态管理测试")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - 更新仓库状态")
        fun `updateStatus should update repository status`() {
            // Given
            val repositoryId = 1L
            val newStatus = 0

            // When
            val result = skillRepositoryMapper.updateStatus(repositoryId, newStatus)
            val updatedRepository = skillRepositoryMapper.selectById(repositoryId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedRepository)
            assertEquals(newStatus, updatedRepository.status)
        }
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectRepositoryList - 查询所有仓库列表")
        fun `selectRepositoryList should return all repositories`() {
            // When
            val repositories = skillRepositoryMapper.selectRepositoryList(null, null, "admin")

            // Then
            assertTrue(repositories.isNotEmpty())
            assertTrue(repositories.size >= 2)
        }

        @Test
        @DisplayName("selectRepositoryList - 按名称模糊查询")
        fun `selectRepositoryList should filter by name`() {
            // When
            val repositories = skillRepositoryMapper.selectRepositoryList("Default", null, "admin")

            // Then
            assertTrue(repositories.isNotEmpty())
            repositories.forEach {
                assertTrue(it.name.contains("Default"))
            }
        }

        @Test
        @DisplayName("selectActiveRepositories - 查询所有启用的仓库")
        fun `selectActiveRepositories should return active repositories`() {
            // When
            val repositories = skillRepositoryMapper.selectActiveRepositories()

            // Then
            assertTrue(repositories.isNotEmpty())
            repositories.forEach {
                assertEquals(1, it.status)
                assertEquals(1, it.active)
            }
        }

        @Test
        @DisplayName("selectByName - 根据名称查询仓库")
        fun `selectByName should return repository by name`() {
            // When
            val repository = skillRepositoryMapper.selectByName("Default Repository")

            // Then
            assertNotNull(repository)
            assertEquals("Default Repository", repository.name)
        }
    }

    @Nested
    @DisplayName("租户隔离测试")
    inner class TenantIsolationTests {

        @Test
        @DisplayName("selectByName - 传入 tenantId=1 只返回租户1的仓库")
        fun `selectByName with tenantId should return only tenant1 repository`() {
            // When
            val repository = skillRepositoryMapper.selectByName("Default Repository", 1L)

            // Then
            assertNotNull(repository)
            assertEquals("Default Repository", repository.name)
            assertEquals(1L, repository.tenantId)
            assertEquals("https://github.com/agnetix/skills", repository.url)
        }

        @Test
        @DisplayName("selectByName - 传入 tenantId=2 返回租户2的同名仓库")
        fun `selectByName with tenantId2 should return tenant2 repository`() {
            // When
            val repository = skillRepositoryMapper.selectByName("Default Repository", 2L)

            // Then
            assertNotNull(repository)
            assertEquals("Default Repository", repository.name)
            assertEquals(2L, repository.tenantId)
            assertEquals("https://github.com/tenant2/default", repository.url)
        }

        @Test
        @DisplayName("selectByName - 不同租户的同名仓库是不同的记录")
        fun `selectByName same name different tenant returns different records`() {
            val repo1 = skillRepositoryMapper.selectByName("Default Repository", 1L)
            val repo2 = skillRepositoryMapper.selectByName("Default Repository", 2L)

            assertNotNull(repo1)
            assertNotNull(repo2)
            assertEquals("Default Repository", repo1.name)
            assertEquals("Default Repository", repo2.name)
            // 不同租户，ID 和 URL 不同
            assertTrue(repo1.id != repo2.id, "Different tenants should have different IDs")
            assertEquals(1L, repo1.tenantId)
            assertEquals(2L, repo2.tenantId)
        }

        @Test
        @DisplayName("selectByName - 传入不存在的租户返回 null")
        fun `selectByName with non-existing tenantId returns null`() {
            val repository = skillRepositoryMapper.selectByName("Default Repository", 999L)
            assertNull(repository)
        }

        @Test
        @DisplayName("selectRepositoryList - 按租户过滤列表")
        fun `selectRepositoryList with tenantId filters by tenant`() {
            // When - 查询租户1
            val tenant1Repos = skillRepositoryMapper.selectRepositoryList(null, null, "admin", 1L)
            // Then - 租户1有2个公开仓库
            assertTrue(tenant1Repos.isNotEmpty())
            tenant1Repos.forEach {
                assertEquals(1L, it.tenantId)
            }

            // When - 查询租户2
            val tenant2Repos = skillRepositoryMapper.selectRepositoryList(null, null, "user2", 2L)
            // Then
            assertTrue(tenant2Repos.isNotEmpty())
            tenant2Repos.forEach {
                assertEquals(2L, it.tenantId)
            }

            // 两个租户的仓库不重叠
            val tenant1Ids = tenant1Repos.map { it.id }.toSet()
            val tenant2Ids = tenant2Repos.map { it.id }.toSet()
            assertTrue(tenant1Ids.intersect(tenant2Ids).isEmpty(), "Tenant repositories should not overlap")
        }

        @Test
        @DisplayName("selectActiveRepositories - 按租户过滤活跃仓库")
        fun `selectActiveRepositories with tenantId filters by tenant`() {
            // When - 查询租户1
            val tenant1Active = skillRepositoryMapper.selectActiveRepositories(1L)
            assertTrue(tenant1Active.isNotEmpty())
            tenant1Active.forEach {
                assertEquals(1L, it.tenantId)
                assertEquals(1, it.status)
                assertEquals(1, it.active)
            }

            // When - 查询租户2
            val tenant2Active = skillRepositoryMapper.selectActiveRepositories(2L)
            assertTrue(tenant2Active.isNotEmpty())
            tenant2Active.forEach {
                assertEquals(2L, it.tenantId)
                assertEquals(1, it.status)
                assertEquals(1, it.active)
            }
        }

        @Test
        @DisplayName("insert - 插入带 tenantId 的仓库并正确读回")
        fun `insert with tenantId should persist and read back correctly`() {
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newRepo = SkillRepository().apply {
                tenantId = 3L
                name = "Tenant3 Repo"
                url = "https://github.com/tenant3/skills"
                branch = "main"
                sourceType = "GIT"
                sourceConfig = """{"url":"https://github.com/tenant3/skills"}"""
                description = "Tenant 3 repository"
                status = 1
                isPublic = 0
                creator = "user3"
                active = 1
                createTime = now
                updateTime = now
            }

            val result = skillRepositoryMapper.insert(newRepo)
            assertEquals(1, result)
            assertTrue(newRepo.id > 0)

            // 用 tenantId 查询应该能找到
            val found = skillRepositoryMapper.selectByName("Tenant3 Repo", 3L)
            assertNotNull(found)
            assertEquals(3L, found.tenantId)
            assertEquals("Tenant3 Repo", found.name)

            // 用其他 tenantId 查询不应该找到
            val notFound = skillRepositoryMapper.selectByName("Tenant3 Repo", 1L)
            assertNull(notFound)
        }
    }
}
