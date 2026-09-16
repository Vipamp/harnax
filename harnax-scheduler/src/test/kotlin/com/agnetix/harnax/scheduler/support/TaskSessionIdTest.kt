package com.agnetix.harnax.scheduler.support

import com.agnetix.harnax.common.session.TaskSessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Contract C1: a scheduled-task session id carries both ids it can be resolved by.
 *
 * The class under test lives in `harnax-common` (both this module and admin consume the grammar, and
 * neither may depend on the other), but its test lives here because that is where the id is minted —
 * `SchedulerServiceImpl.insertRunningLog` is the only caller of [TaskSessionId.of] — and `harnax-common`
 * has no test harness of its own.
 *
 * [TaskSessionId.parse] recognises exactly one shape and answers null for everything else, the pre-C1
 * spelling included. So the assertions below can always be about the result as a whole; there is no
 * partial answer to tell apart from a real one.
 */
class TaskSessionIdTest {

    @Test
    fun `a session id carries both ids and survives a round trip`() {
        val id = TaskSessionId.of(12L, 34L)

        assertTrue(id.startsWith("task-12-34-"), "generated id lost its shape: $id")
        // Exactly four tokens, which is what makes the two shapes countable rather than guessable.
        assertEquals(4, id.split('-').size, "expected four segments in $id")

        val parsed = TaskSessionId.parse(id)
        assertEquals(12L, parsed?.taskId, "an id this class generated did not read back: $id")
        assertEquals(34L, parsed?.agentId, "an id this class generated named no agent: $id")
        assertEquals(id.removePrefix("task-12-34-"), parsed?.random, "the tail is whatever of() appended")
        assertTrue(parsed?.random?.isNotBlank() == true)
    }

    @Test
    fun `of keeps the four-token shape whatever uuid it is handed`() {
        // The tail is stored without dashes so that a dashed uuid — the shape the old producer wrote —
        // cannot come out of of() and be read as something else.
        val id = TaskSessionId.of(12L, 34L, "6f0b1a2c-3d4e-5f60-7182-93a4b5c6d7e8")

        assertEquals("task-12-34-6f0b1a2c3d4e5f60718293a4b5c6d7e8", id)
        assertEquals(34L, TaskSessionId.parse(id)?.agentId, "of() wrote a tail its own parse() refuses: $id")
    }

    @Test
    fun `two ids for the same task and agent still tell their executions apart`() {
        assertNotEquals(TaskSessionId.of(12L, 34L), TaskSessionId.of(12L, 34L))
        // The agent segment is what admin used to have to read a row for, so it has to be the real one.
        assertEquals(35L, TaskSessionId.parse(TaskSessionId.of(12L, 35L))?.agentId)
    }

    @Test
    fun `the pre-C1 three-segment form is refused rather than half-read`() {
        // What the producer wrote before C1, in both spellings it came in. Refusing these is the honest
        // reading of "release 2 carries no rows over": the scheduler's tables start empty and the old ones
        // are dropped, so no string of this shape exists to be nice about. And a `Parsed` with a task id
        // but no agent had exactly one consumer, `InternalApiController.resolveFromTask`, which could only
        // ever reject it — a partial answer that nothing reads is not an answer.
        listOf(
            "task-7-abcdef",
            "task-7-6f0b1a2c-3d4e-5f60-7182-93a4b5c6d7e8", // with the dashes the old UUID kept
        ).forEach { sessionId ->
            assertNull(TaskSessionId.parse(sessionId), "$sessionId names no agent, so it is not a C1 id at all")
        }
    }

    @Test
    fun `anything that is not a task session is refused rather than guessed at`() {
        listOf(
            "web-1",
            "xtask-1-2-3", // the prefix has to be the whole first token
            "session-1",
            "task", // a bare word is not a session id
            "task-", // a prefix with nothing behind it
            "task-abc-1-2", // a non-numeric taskId
            "task-0-1-2", // task 0 does not exist
            "task--1-2-3", // an empty segment
            "task-9223372036854775808-1-2", // an id that does not fit a Long must not silently become one
            "task-+1-2-3", // '+', '_' and a leading space all read as Long in Java, and of() writes none
        ).forEach { sessionId ->
            assertNull(TaskSessionId.parse(sessionId), "$sessionId is not a task session id at all")
        }
    }

    @Test
    fun `an id whose second segment is not an agent id is not a C1 id`() {
        // Counting four tokens is necessary and not sufficient: the middle one has to be an agent id and the
        // tail has to be there, or this is a broken string and the caller has to be told so by the message.
        listOf(
            "task-42-abc-6f0b1a2c", // an agent id that is not a number
            "task-42-0-6f0b1a2c", // agent 0 is not an agent
            "task-42-100-", // nothing left for the random tail
            "task-42-100-6f0b1a2c-3d4e", // a tail with dashes in it is not what of() writes
        ).forEach { sessionId ->
            assertNull(TaskSessionId.parse(sessionId), "$sessionId is not a C1 id, and half of one is no help")
        }
    }

    @Test
    fun `an unsaved task or agent cannot mint an id`() {
        // Both columns are NOT NULL with an auto-increment id, so 0 only ever means "this row was never
        // written". Minting `task-0-…` would hand a consumer an id it must reject at read time, far from
        // here — and dropping the agent segment altogether would mint the pre-C1 shape, which parse() now
        // refuses outright.
        assertThrows<IllegalArgumentException> { TaskSessionId.of(0L, 34L) }
        assertThrows<IllegalArgumentException> { TaskSessionId.of(12L, 0L) }
        assertThrows<IllegalArgumentException> { TaskSessionId.of(-1L, 34L) }
        assertThrows<IllegalArgumentException> { TaskSessionId.of(12L, 34L, "----") }
    }

    @Test
    fun `even the widest possible id fits the column that stores it`() {
        // `agent_task_log.session_id` is VARCHAR(128) since V2 of the scheduler schema; a real id is far
        // shorter, and this is the pathological end of that range.
        val id = TaskSessionId.of(Long.MAX_VALUE, Long.MAX_VALUE)

        assertTrue(id.length <= 128, "id too long for the column: $id")
    }
}
