package com.agnetix.harnax.tools.builtin

import com.agnetix.harnax.tools.sdk.SessionMetaContext
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallInfo
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.text.SimpleDateFormat

/**
 * TimeToolBox Unit Tests
 *
 * Verifies the built-in TimeToolBox datetime tools:
 * - getDate() returns yyyy-MM-dd formatted date
 * - getDatetime() returns yyyy-MM-dd HH:mm:ss formatted datetime
 * - name() returns the correct tool name
 * - @NeedConfirmed methods are discovered correctly
 *
 * @author agnetix
 * @since 2026-07-10
 */
class TimeToolBoxTest {

    private lateinit var timeToolBox: TimeToolBox
    private lateinit var mockAdaptor: ToolCallLogAdaptor

    @BeforeEach
    fun setUp() {
        timeToolBox = TimeToolBox()
        mockAdaptor = mock()
        timeToolBox.init(
            mockAdaptor,
            SessionMetaContext(agentId = 1L, sessionId = "test-session"),
            UserIdentifier(userId = 1L),
        )
    }

    @Nested
    @DisplayName("Name Tests")
    inner class NameTests {

        @Test
        @DisplayName("name should return 'datetime-tool-box'")
        fun `name should return correct value`() {
            assertEquals("datetime-tool-box", timeToolBox.name())
        }

        @Test
        @DisplayName("NAME companion constant should match name()")
        fun `NAME constant should match name`() {
            assertEquals(TimeToolBox.NAME, timeToolBox.name())
        }
    }

    @Nested
    @DisplayName("NeedConfirmed Tests")
    inner class NeedConfirmedTests {

        @Test
        @DisplayName("should discover both @NeedConfirmed methods")
        fun `should discover both NeedConfirmed methods`() {
            val confirmed = timeToolBox.needConfirmedTools()
            assertEquals(2, confirmed.size)
        }

        @Test
        @DisplayName("should include getDate in needConfirmedTools")
        fun `should include getDate`() {
            val confirmed = timeToolBox.needConfirmedTools()
            assertTrue(confirmed.contains("datetime-tool-box::getDate"))
        }

        @Test
        @DisplayName("should include getDatetime in needConfirmedTools")
        fun `should include getDatetime`() {
            val confirmed = timeToolBox.needConfirmedTools()
            assertTrue(confirmed.contains("datetime-tool-box::getDatetime"))
        }
    }

    @Nested
    @DisplayName("getDate Tests")
    inner class GetDateTests {

        @Test
        @DisplayName("getDate should return today's date in yyyy-MM-dd format")
        fun `getDate should return formatted date`() {
            val result = timeToolBox.getDate()
            val expected = SimpleDateFormat(TimeToolBox.YYYY_MM_DD).format(java.util.Date())
            assertEquals(expected, result)
        }

        @Test
        @DisplayName("getDate should log tool call via adaptor")
        fun `getDate should log tool call`() {
            timeToolBox.getDate()

            val captor = argumentCaptor<ToolCallInfo>()
            verify(mockAdaptor, times(1)).emit(captor.capture())

            val info = captor.firstValue
            assertEquals("datetime-tool-box::getDate", info.toolName)
            assertTrue(info.success)
            assertEquals(1L, info.agentId)
            assertEquals("test-session", info.sessionId)
        }
    }

    @Nested
    @DisplayName("getDatetime Tests")
    inner class GetDatetimeTests {

        @Test
        @DisplayName("getDatetime should return current datetime in yyyy-MM-dd HH:mm:ss format")
        fun `getDatetime should return formatted datetime`() {
            // Capture time before and after to handle edge-case of second rollover
            val before = SimpleDateFormat(TimeToolBox.YYYY_MM_DD_HH_MM_SS).format(java.util.Date())
            val result = timeToolBox.getDatetime()
            val after = SimpleDateFormat(TimeToolBox.YYYY_MM_DD_HH_MM_SS).format(java.util.Date())

            // Result should be between before and after (inclusive)
            assertTrue(result >= before && result <= after)
        }

        @Test
        @DisplayName("getDatetime should log tool call via adaptor")
        fun `getDatetime should log tool call`() {
            timeToolBox.getDatetime()

            val captor = argumentCaptor<ToolCallInfo>()
            verify(mockAdaptor, times(1)).emit(captor.capture())

            val info = captor.firstValue
            assertEquals("datetime-tool-box::getDatetime", info.toolName)
            assertTrue(info.success)
        }
    }

    @Nested
    @DisplayName("DateFormat Constants Tests")
    inner class DateFormatConstantsTests {

        @Test
        @DisplayName("YYYY_MM_DD should be 'yyyy-MM-dd'")
        fun `YYYY_MM_DD constant`() {
            assertEquals("yyyy-MM-dd", TimeToolBox.YYYY_MM_DD)
        }

        @Test
        @DisplayName("YYYY_MM_DD_HH_MM_SS should be 'yyyy-MM-dd HH:mm:ss'")
        fun `YYYY_MM_DD_HH_MM_SS constant`() {
            assertEquals("yyyy-MM-dd HH:mm:ss", TimeToolBox.YYYY_MM_DD_HH_MM_SS)
        }
    }
}
