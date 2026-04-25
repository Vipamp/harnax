package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SysJob
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
 * SysJobMapper 集成测试
 *
 * @author vipamp
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SysJobMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("vipclaw_test")
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
    private lateinit var sysJobMapper: SysJobMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询定时任务")
        fun `selectById should return job by id`() {
            // When
            val job = sysJobMapper.selectById(1L)

            // Then
            assertNotNull(job)
            assertEquals(1L, job.id)
            assertEquals("Data Sync Job", job.jobName)
            assertEquals("DEFAULT", job.jobGroup)
            assertEquals("0 0 * * * ?", job.cronExpression)
            assertEquals(1, job.jobStatus)
            assertEquals(1, job.active)
        }

        @Test
        @DisplayName("selectById - 查询不存在的任务返回 null")
        fun `selectById should return null when job not exists`() {
            // When
            val job = sysJobMapper.selectById(999L)

            // Then
            assertNull(job)
        }

        @Test
        @DisplayName("selectById - 不返回已删除的任务")
        fun `selectById should not return deleted job`() {
            // When
            val job = sysJobMapper.selectById(4L)

            // Then
            assertNull(job)
        }

        @Test
        @DisplayName("insert - 插入新任务")
        fun `insert should create new job`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newJob = SysJob().apply {
                jobName = "New Job"
                jobGroup = "DEFAULT"
                jobClass = "com.vipamp.vipclaw.admin.job.NewJob"
                cronExpression = "0 0 12 * * ?"
                jobStatus = 1
                concurrent = 1
                description = "新任务"
                isPublic = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = sysJobMapper.insert(newJob)

            // Then
            assertEquals(1, result)
            assertTrue(newJob.id > 0)

            val insertedJob = sysJobMapper.selectById(newJob.id)
            assertNotNull(insertedJob)
            assertEquals("New Job", insertedJob.jobName)
        }

        @Test
        @DisplayName("updateById - 更新任务信息")
        fun `updateById should update job info`() {
            // Given
            val jobId = 1L
            val job = sysJobMapper.selectById(jobId)
            assertNotNull(job)

            // When
            job.jobName = "Updated Job"
            job.description = "更新后的描述"
            job.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = sysJobMapper.updateById(job)

            // Then
            assertEquals(1, result)
            val updatedJob = sysJobMapper.selectById(jobId)
            assertNotNull(updatedJob)
            assertEquals("Updated Job", updatedJob.jobName)
        }

        @Test
        @DisplayName("deleteById - 逻辑删除任务")
        fun `deleteById should logically delete job`() {
            // Given
            val jobId = 2L
            val jobBefore = sysJobMapper.selectById(jobId)
            assertNotNull(jobBefore)

            // When
            val result = sysJobMapper.deleteById(jobId)

            // Then
            assertEquals(1, result)
            val deletedJob = sysJobMapper.selectById(jobId)
            assertNull(deletedJob)
        }
    }

    @Nested
    @DisplayName("状态管理测试")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - 更新任务状态")
        fun `updateStatus should update job status`() {
            // Given
            val jobId = 1L
            val newStatus = 0

            // When
            val result = sysJobMapper.updateStatus(jobId, newStatus)
            val updatedJob = sysJobMapper.selectById(jobId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedJob)
            assertEquals(newStatus, updatedJob.jobStatus)
        }
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectJobList - 查询所有任务列表")
        fun `selectJobList should return all jobs`() {
            // When
            val jobs = sysJobMapper.selectJobList(null, null, "admin")

            // Then
            assertTrue(jobs.isNotEmpty())
            assertTrue(jobs.size >= 3)
        }

        @Test
        @DisplayName("selectJobList - 按关键词查询")
        fun `selectJobList should filter by keyword`() {
            // When
            val jobs = sysJobMapper.selectJobList("Data", null, "admin")

            // Then
            assertTrue(jobs.isNotEmpty())
            jobs.forEach {
                assertTrue(it.jobName.contains("Data"))
            }
        }

        @Test
        @DisplayName("selectJobList - 按状态查询")
        fun `selectJobList should filter by status`() {
            // When
            val jobs = sysJobMapper.selectJobList(null, 0, "admin")

            // Then
            assertTrue(jobs.isNotEmpty())
            jobs.forEach {
                assertEquals(0, it.jobStatus)
            }
        }

        @Test
        @DisplayName("selectByNameAndGroup - 根据名称和组查询任务")
        fun `selectByNameAndGroup should return job by name and group`() {
            // When
            val job = sysJobMapper.selectByNameAndGroup("Data Sync Job", "DEFAULT")

            // Then
            assertNotNull(job)
            assertEquals("Data Sync Job", job.jobName)
            assertEquals("DEFAULT", job.jobGroup)
        }

        @Test
        @DisplayName("selectRunningJobs - 查询所有运行中的任务")
        fun `selectRunningJobs should return running jobs`() {
            // When
            val jobs = sysJobMapper.selectRunningJobs()

            // Then
            assertTrue(jobs.isNotEmpty())
            jobs.forEach {
                assertEquals(1, it.jobStatus)
            }
        }
    }
}
