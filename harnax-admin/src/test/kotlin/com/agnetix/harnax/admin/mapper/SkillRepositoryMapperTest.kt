package com.agnetix.harnax.admin.mapper

import com.agnetix.harnax.admin.entity.SkillRepository
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
class SkillRepositoryMapperTest {

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
}
