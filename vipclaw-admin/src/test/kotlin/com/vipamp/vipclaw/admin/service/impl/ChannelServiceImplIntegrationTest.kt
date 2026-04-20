package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.ChannelCreateRequest
import com.vipamp.vipclaw.admin.dto.ChannelUpdateRequest
import com.vipamp.vipclaw.admin.mapper.ChannelMapper
import org.junit.jupiter.api.*
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * ChannelServiceImpl 集成测试
 * 使用 Testcontainers 启动真实的 MySQL 容器进行测试
 *
 * @author vipamp
 * @since 2026-04-20
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ChannelServiceImplIntegrationTest {

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
    private lateinit var channelService: ChannelServiceImpl

    @Autowired
    private lateinit var channelMapper: ChannelMapper

    @Nested
    @DisplayName("分页查询测试")
    inner class PaginationTests {

        @Test
        @DisplayName("getChannelPage - 正常分页查询")
        fun `getChannelPage should return paginated results`() {
            // When
            val page = channelService.getChannelPage(null, null, null, 1, 2)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 2) // schema-test.sql 中有3条，但deleted的active=0
            assertEquals(2, page.size)
            assertEquals(1, page.current)
            assertEquals(2, page.records.size)
        }

        @Test
        @DisplayName("getChannelPage - 名称搜索")
        fun `getChannelPage should filter by name`() {
            // When
            val page = channelService.getChannelPage("WeCom", null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.name.contains("WeCom") })
        }

        @Test
        @DisplayName("getChannelPage - 类型过滤")
        fun `getChannelPage should filter by type`() {
            // When
            val page = channelService.getChannelPage(null, "http", null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.type == "http" })
        }
    }

    @Nested
    @DisplayName("查询Channel详情测试")
    inner class GetChannelByIdTests {

        @Test
        @DisplayName("getChannelById - 查询存在的Channel")
        fun `getChannelById should return channel when exists`() {
            // When
            val channel = channelService.getChannelById(1L)

            // Then
            assertNotNull(channel)
            assertEquals(1L, channel?.id)
            assertEquals("Test WeCom Channel", channel?.name)
        }

        @Test
        @DisplayName("getChannelById - 查询不存在的Channel返回null")
        fun `getChannelById should return null when channel not found`() {
            // When
            val channel = channelService.getChannelById(999L)

            // Then
            assertNull(channel)
        }
    }

    @Nested
    @DisplayName("创建Channel测试")
    inner class CreateChannelTests {

        @Test
        @DisplayName("createChannel - 创建成功")
        fun `createChannel should create channel successfully`() {
            // Given
            val request = ChannelCreateRequest(
                name = "New Channel",
                type = "wecom",
                agentId = 1L,
                description = "新通道",
                status = 1
            )

            // When
            val result = channelService.createChannel(request)

            // Then
            assertTrue(result)

            // 验证Channel可以查询到
            val page = channelService.getChannelPage("New Channel", null, null, 1, 10)
            assertTrue(page.total >= 1)
        }
    }

    @Nested
    @DisplayName("更新Channel测试")
    inner class UpdateChannelTests {

        @Test
        @DisplayName("updateChannel - 更新部分字段")
        fun `updateChannel should update partial fields`() {
            // Given
            val request = ChannelUpdateRequest(
                name = "Updated Channel",
                description = "更新后的描述"
            )

            // When
            val result = channelService.updateChannel(1L, request)

            // Then
            assertTrue(result)

            // 验证更新成功
            val channel = channelMapper.selectById(1L)
            assertEquals("Updated Channel", channel?.name)
            assertEquals("更新后的描述", channel?.description)
        }

        @Test
        @DisplayName("updateChannel - 更新状态")
        fun `updateChannel should update status`() {
            // Given
            val request = ChannelUpdateRequest(
                status = 0
            )

            // When
            val result = channelService.updateChannel(2L, request)

            // Then
            assertTrue(result)

            val channel = channelMapper.selectById(2L)
            assertEquals(0, channel?.status)
        }
    }

    @Nested
    @DisplayName("切换Channel状态测试")
    inner class ToggleChannelStatusTests {

        @Test
        @DisplayName("toggleChannelStatus - 禁用Channel")
        fun `toggleChannelStatus should disable channel`() {
            // When
            val result = channelService.toggleChannelStatus(1L, 0)

            // Then
            assertTrue(result)

            val channel = channelMapper.selectById(1L)
            assertEquals(0, channel?.status)
        }

        @Test
        @DisplayName("toggleChannelStatus - 启用Channel")
        fun `toggleChannelStatus should enable channel`() {
            // Given
            channelService.toggleChannelStatus(2L, 0)

            // When
            val result = channelService.toggleChannelStatus(2L, 1)

            // Then
            assertTrue(result)

            val channel = channelMapper.selectById(2L)
            assertEquals(1, channel?.status)
        }
    }

    @Nested
    @DisplayName("删除Channel测试")
    inner class DeleteChannelTests {

        @Test
        @DisplayName("deleteChannel - 逻辑删除成功")
        fun `deleteChannel should logically delete channel`() {
            // When
            val result = channelService.deleteChannel(2L)

            // Then
            assertTrue(result)

            val channel = channelMapper.selectById(2L)
            assertEquals(0, channel?.active)
        }

        @Test
        @DisplayName("deleteChannel - 删除不存在的Channel")
        fun `deleteChannel should return false when channel not found`() {
            // When
            val result = channelService.deleteChannel(999L)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：创建 - 查询 - 更新 - 禁用 - 删除")
        fun `complete flow create query update disable delete`() {
            // 1. 创建Channel
            val createRequest = ChannelCreateRequest(
                name = "FlowTest Channel",
                type = "http",
                agentId = 1L,
                description = "流程测试通道",
                status = 1
            )
            assertTrue(channelService.createChannel(createRequest))

            // 2. 查询Channel
            val page = channelService.getChannelPage("FlowTest Channel", null, null, 1, 10)
            assertTrue(page.total >= 1)
            val channelId = page.records[0].id

            // 3. 更新Channel
            val updateRequest = ChannelUpdateRequest(
                description = "更新后的流程测试",
                status = 1
            )
            assertTrue(channelService.updateChannel(channelId, updateRequest))

            val updatedChannel = channelService.getChannelById(channelId)
            assertEquals("更新后的流程测试", updatedChannel?.description)

            // 4. 禁用Channel
            assertTrue(channelService.toggleChannelStatus(channelId, 0))
            val disabledChannel = channelMapper.selectById(channelId)
            assertEquals(0, disabledChannel?.status)

            // 5. 删除Channel
            assertTrue(channelService.deleteChannel(channelId))
            val deletedChannel = channelMapper.selectById(channelId)
            assertEquals(0, deletedChannel?.active)
        }
    }
}
