package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Channel
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
 * ChannelMapper 集成测试
 *
 * @author agnetix
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ChannelMapperTest {

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
    private lateinit var channelMapper: ChannelMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询通道")
        fun `selectById should return channel by id`() {
            // When
            val channel = channelMapper.selectById(1L)

            // Then
            assertNotNull(channel)
            assertEquals(1L, channel.id)
            assertEquals("Test WeCom Channel", channel.name)
            assertEquals("wecom", channel.type)
            assertEquals(1L, channel.agentId)
            assertEquals("test-wecom", channel.callbackKey)
            assertEquals(1, channel.status)
            assertEquals(1, channel.active)
        }

        @Test
        @DisplayName("selectById - 查询不存在的通道返回 null")
        fun `selectById should return null when channel not exists`() {
            // When
            val channel = channelMapper.selectById(999L)

            // Then
            assertNull(channel)
        }

        @Test
        @DisplayName("selectById - 不返回已删除的通道")
        fun `selectById should not return deleted channel`() {
            // When
            val channel = channelMapper.selectById(4L)

            // Then
            assertNull(channel)
        }

        @Test
        @DisplayName("insert - 插入新通道")
        fun `insert should create new channel`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newChannel = Channel().apply {
                name = "New Channel"
                type = "http"
                agentId = 1L
                webhookUrl = "http://localhost:8080/webhook/new"
                token = "new-token"
                callbackKey = "test-new"
                description = "新通道"
                status = 1
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = channelMapper.insert(newChannel)

            // Then
            assertEquals(1, result)
            assertTrue(newChannel.id > 0)

            val insertedChannel = channelMapper.selectById(newChannel.id)
            assertNotNull(insertedChannel)
            assertEquals("New Channel", insertedChannel.name)
        }

        @Test
        @DisplayName("updateById - 更新通道信息")
        fun `updateById should update channel info`() {
            // Given
            val channelId = 1L
            val channel = channelMapper.selectById(channelId)
            assertNotNull(channel)

            // When
            channel.name = "Updated Channel"
            channel.description = "更新后的描述"
            channel.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = channelMapper.updateById(channel)

            // Then
            assertEquals(1, result)
            val updatedChannel = channelMapper.selectById(channelId)
            assertNotNull(updatedChannel)
            assertEquals("Updated Channel", updatedChannel.name)
        }

        @Test
        @DisplayName("deleteById - 逻辑删除通道")
        fun `deleteById should logically delete channel`() {
            // Given
            val channelId = 2L
            val channelBefore = channelMapper.selectById(channelId)
            assertNotNull(channelBefore)

            // When
            val result = channelMapper.deleteById(channelId)

            // Then
            assertEquals(1, result)
            val deletedChannel = channelMapper.selectById(channelId)
            assertNull(deletedChannel)
        }
    }

    @Nested
    @DisplayName("状态管理测试")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - 更新通道状态")
        fun `updateStatus should update channel status`() {
            // Given
            val channelId = 1L
            val newStatus = 0

            // When
            val result = channelMapper.updateStatus(channelId, newStatus)
            val updatedChannel = channelMapper.selectById(channelId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedChannel)
            assertEquals(newStatus, updatedChannel.status)
        }
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectByCallbackKey - 根据回调标识查询通道")
        fun `selectByCallbackKey should return channel by callback key`() {
            // When
            val channel = channelMapper.selectByCallbackKey("test-wecom")

            // Then
            assertNotNull(channel)
            assertEquals("test-wecom", channel.callbackKey)
            assertEquals("Test WeCom Channel", channel.name)
        }

        @Test
        @DisplayName("selectByCallbackKey - 查询不存在的回调标识返回 null")
        fun `selectByCallbackKey should return null when callback key not exists`() {
            // When
            val channel = channelMapper.selectByCallbackKey("nonexistent")

            // Then
            assertNull(channel)
        }

        @Test
        @DisplayName("selectChannelList - 查询所有通道列表")
        fun `selectChannelList should return all channels`() {
            // When
            val channels = channelMapper.selectChannelList(null, null, null)

            // Then
            assertTrue(channels.isNotEmpty())
            assertTrue(channels.size >= 3)
        }

        @Test
        @DisplayName("selectChannelList - 按类型查询")
        fun `selectChannelList should filter by type`() {
            // When
            val channels = channelMapper.selectChannelList(null, "wecom", null)

            // Then
            assertTrue(channels.isNotEmpty())
            channels.forEach {
                assertEquals("wecom", it.type)
            }
        }
    }
}
