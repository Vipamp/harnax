package com.agnetix.harnax.channel.service.session

import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * [InMemoryChannelSessionManager] 的测试。这个类存在的意义就是进程要跑上几个月，所以两个上限
 * （单会话消息数、内存中的会话数）必须守住——而如果上限会把正在服务的会话淘汰掉，那还不如没有上限。
 */
class InMemoryChannelSessionManagerTest {

    private fun message(
        sessionId: String,
        content: String,
        type: MessageType = MessageType.TEXT,
        imageUrls: List<String> = emptyList(),
    ): ChannelMessage = ChannelMessage(
        sessionId = sessionId,
        content = content,
        channelType = ChannelType.HTTP,
        messageType = type,
        imageUrls = imageUrls,
    )

    private fun liveSessions(
        manager: InMemoryChannelSessionManager,
        vararg sessionIds: String,
        channelId: Long = 1L,
    ): List<String> = sessionIds.filter { manager.getHistory(channelId, it, limit = 5).isNotEmpty() }

    @Test
    fun `a conversation keeps only the most recent messages`() = runBlocking {
        val manager = InMemoryChannelSessionManager(
            maxSessions = 100,
            maxMessagesPerSession = 3,
        )

        (1..5).forEach { manager.addMessage(1L, message("s-1", "msg-$it")) }

        val history = manager.getHistory(1L, "s-1", limit = 20)
        assertEquals(listOf("msg-3", "msg-4", "msg-5"), history.map { it.content })
    }

    @Test
    fun `the requested limit returns the tail of the conversation`() = runBlocking {
        val manager = InMemoryChannelSessionManager(maxSessions = 100, maxMessagesPerSession = 10)
        (1..5).forEach { manager.addMessage(1L, message("s-1", "msg-$it")) }

        val history = manager.getHistory(1L, "s-1", limit = 2)

        assertEquals(listOf("msg-4", "msg-5"), history.map { it.content })
    }

    @Test
    fun `an unknown conversation has no history`() = runBlocking {
        val manager = InMemoryChannelSessionManager()

        assertTrue(manager.getHistory(42L, "never-used", limit = 20).isEmpty())
    }

    @Test
    fun `history is a copy, so a message arriving mid-turn cannot break the reader`() = runBlocking {
        val manager = InMemoryChannelSessionManager(maxSessions = 100, maxMessagesPerSession = 100)
        (1..3).forEach { manager.addMessage(1L, message("s-1", "msg-$it")) }

        val snapshot = manager.getHistory(1L, "s-1", limit = 20)
        // 旧实现返回的是内部 live list 的视图：在这之后追加一条，正在进行的 agent 回合读取该视图
        // 就会抛 ConcurrentModificationException。
        manager.addMessage(1L, message("s-1", "arrived-later"))

        assertEquals(3, snapshot.size)
        assertEquals(listOf("msg-1", "msg-2", "msg-3"), snapshot.map { it.content })
        assertEquals(4, manager.getHistory(1L, "s-1", limit = 20).size)
    }

    @Test
    fun `conversations are scoped by channel`() = runBlocking {
        val manager = InMemoryChannelSessionManager()
        manager.addMessage(1L, message("shared-id", "on channel one"))

        assertTrue(manager.getHistory(2L, "shared-id", limit = 20).isEmpty())
        assertEquals("on channel one", manager.getHistory(1L, "shared-id", limit = 20).single().content)
    }

    @Test
    fun `clearing one conversation leaves the others alone`() = runBlocking {
        val manager = InMemoryChannelSessionManager()
        manager.addMessage(1L, message("s-1", "keep"))
        manager.addMessage(1L, message("s-2", "drop"))

        manager.clearHistory(1L, "s-2")

        assertTrue(manager.getHistory(1L, "s-2", limit = 20).isEmpty())
        assertEquals(1, manager.getHistory(1L, "s-1", limit = 20).size)
    }

    @Test
    fun `a capacity overflow never drops the conversation being served`() = runBlocking {
        val manager = InMemoryChannelSessionManager(maxSessions = 2, maxMessagesPerSession = 10)

        (1..4).forEach {
            // 四次写入若落在同一毫秒，lastTouch 排不出先后，被淘汰的就是 map 遍历顺序里的任意一条。
            Thread.sleep(5)
            manager.addMessage(1L, message("s-$it", "msg-$it"))
        }

        assertTrue(
            manager.getHistory(1L, "s-4", limit = 5).isNotEmpty(),
            "the newest conversation was evicted by the capacity sweep",
        )
        assertTrue(liveSessions(manager, "s-1", "s-2", "s-3", "s-4").size <= 2, "the session map is unbounded")
    }

    @Test
    fun `a capacity overflow with the idle window disabled keeps the newest conversation`() = runBlocking {
        // idleTtl=0 表示“永不过期”，这不等于把「只淘汰过期项」这一轮变成一次全量清扫。
        val manager = InMemoryChannelSessionManager(
            maxSessions = 2,
            maxMessagesPerSession = 10,
            idleTtl = Duration.ZERO,
        )

        (1..3).forEach {
            // 强制淘汰只看 lastTouch，写入落在同一毫秒时被带走的可能就是刚存的 s-3。
            Thread.sleep(5)
            manager.addMessage(1L, message("s-$it", "msg-$it"))
        }

        assertEquals("msg-3", manager.getHistory(1L, "s-3", limit = 5).single().content)
        assertTrue(liveSessions(manager, "s-1", "s-2", "s-3").size <= 2)
    }

    @Test
    fun `an idle conversation is dropped on the next write`() = runBlocking {
        val manager = InMemoryChannelSessionManager(
            maxSessions = 3,
            maxMessagesPerSession = 10,
            idleTtl = Duration.ofMillis(50),
        )
        manager.addMessage(1L, message("s-old", "stale"))
        Thread.sleep(200)

        manager.addMessage(1L, message("s-1", "one"))
        manager.addMessage(1L, message("s-2", "two"))

        assertTrue(liveSessions(manager, "s-old").isEmpty())
        assertEquals(2, liveSessions(manager, "s-1", "s-2", "s-old").size)
    }

    @Test
    fun `reading history keeps a conversation alive`() = runBlocking {
        val manager = InMemoryChannelSessionManager(
            maxSessions = 3,
            maxMessagesPerSession = 10,
            idleTtl = Duration.ofHours(1),
        )
        manager.addMessage(1L, message("s-1", "one"))
        manager.addMessage(1L, message("s-2", "two"))
        // 不加这个 sleep，两次写入可能落在同一毫秒内；lastTouch 相等时被淘汰的就会是 map 遍历顺序
        // 里的那个，而不是「最久未使用」的那个，读取续活这件事也就测不出来了。
        Thread.sleep(5)
        assertEquals(1, manager.getHistory(1L, "s-1", limit = 5).size)

        manager.addMessage(1L, message("s-3", "three"))
        manager.addMessage(1L, message("s-4", "four"))

        // s-1 创建得更早，但它被读过；最久未使用的是 s-2。
        assertEquals(listOf("s-1", "s-3", "s-4"), liveSessions(manager, "s-1", "s-2", "s-3", "s-4"))
    }

    @Test
    fun `image payloads are stripped before the message is stored`() = runBlocking {
        val manager = InMemoryChannelSessionManager()

        manager.addMessage(
            1L,
            message(
                "s-1",
                "look at this",
                type = MessageType.TEXT,
                imageUrls = listOf("data:image/png;base64,AAAA"),
            ),
        )

        val stored = manager.getHistory(1L, "s-1", limit = 5).single()
        assertEquals("look at this", stored.content)
        assertTrue(stored.imageUrls.isEmpty())
    }

    @Test
    fun `an image message is replaced by a placeholder`() = runBlocking {
        val manager = InMemoryChannelSessionManager()

        manager.addMessage(
            1L,
            message(
                "s-1",
                "base64blob",
                type = MessageType.IMAGE,
                imageUrls = listOf("data:image/png;base64,AAAA"),
            ),
        )

        assertEquals("[image sent]", manager.getHistory(1L, "s-1", limit = 5).single().content)
    }

    @Test
    fun `an inline data url is replaced by a placeholder`() = runBlocking {
        val manager = InMemoryChannelSessionManager()

        manager.addMessage(1L, message("s-1", "data:image/jpeg;base64,BBBB", type = MessageType.IMAGE))

        assertEquals("[image sent]", manager.getHistory(1L, "s-1", limit = 5).single().content)
    }

    @Test
    fun `a non-image data url in the text body is left alone`() = runBlocking {
        val manager = InMemoryChannelSessionManager()

        manager.addMessage(1L, message("s-1", "see data:image/png;base64,AAAA inline"))

        assertEquals("see data:image/png;base64,AAAA inline", manager.getHistory(1L, "s-1", limit = 5).single().content)
    }
}
