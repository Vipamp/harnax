package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a deleted channel leaves behind (r18 ruling 1: deleting a channel deletes its session, and every
 * delete here is a logical one).
 *
 * A channel carries its own conversation: `chn-{uuid}` is minted at creation and stamped on the channel
 * row, and that id is what the runtime keys the chat state, the plans and the sandbox container by. There
 * is no `session` row to lose — V28's own comment says a channel session never lives in `session` — so the
 * only cleanup available for it is the release admin asks the router to perform. Before this round
 * `ChannelServiceImpl.deleteChannel` was a bare `deleteById`, so the row went away while the conversation
 * kept running under a name nobody could point at any more: the router answers session ownership from the
 * soft-deleted row's `tenant_id`, so it went on routing to a session whose channel no page listed.
 *
 * The cascade has an order, and this class pins the whole of it:
 *  - the release is asked for with the channel's own session id, before any local row is written;
 *  - a runtime that refuses takes the deletion down with it and leaves `channel.active` at 1 — the semantics
 *    AGENT-08 fixed for a session deletion, because "row gone, state alive" is the unrecoverable half of
 *    the split and a channel that can be deleted again later is not;
 *  - the channel row goes logically: it stays, with its `tenant_id`, which is what lets the router still
 *    say whose a session was after the page stopped listing the channel behind it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ChannelSessionCascadeIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val suffix = Random.nextInt(100000, 999999)

    /** Nothing behind `agent_id` is consulted on the way in, so one id serves every channel here. */
    private val agentId = 986_001L + Random.nextInt(1, 900)

    /** A tenant that exists nowhere else, so these rows cannot collide with another class's. */
    private val otherTenant = 941_003L

    @BeforeEach
    fun resetRouter() {
        fakeRouter.reset()
    }

    /** Channels on a keyword-filtered page carrying exactly this name, as the given tenant sees them. */
    private fun rowsNamed(
        name: String,
        tenantId: Long? = null,
    ): List<JsonNode> {
        val data = assertOk(
            parseBody(exchange(HttpMethod.GET, "/api/admin/channels/page?pageNum=1&pageSize=50&keyword=$name", tenantId = tenantId)),
        )
        val records = data["records"]
        return if (records == null || !records.isArray) emptyList() else records.filter { it["name"]?.asText() == name }
    }

    /** A channel of [tenantId] — null means the default tenant the admin token acts within. */
    private fun createChannel(
        name: String,
        tenantId: Long? = null,
    ): Long {
        val body = mapOf(
            "name" to name,
            "type" to "http",
            "agentId" to agentId,
            "communicationMode" to "webhook",
            "description" to "r18 cascade",
        )
        assertOk(parseBody(exchange(HttpMethod.POST, "/api/admin/channels", body, tenantId = tenantId)))
        val record = rowsNamed(name, tenantId).firstOrNull()
        assertNotNull(record, "the created channel should be listed: $name")
        return record["id"].asLong()
    }

    /**
     * The `chn-…` id the runtime holds this channel's conversation under.
     *
     * Read from the table rather than the detail endpoint on purpose: some cases below ask as a tenant that
     * is not the row's owner, and that read is exactly what is supposed to answer "not found".
     */
    private fun sessionIdOf(id: Long): String = jdbc.queryForObject("SELECT session_id FROM channel WHERE id = ?", String::class.java, id)!!

    /** `active` of one row, or null when the row is gone — which a logical delete must never do. */
    private fun activeOf(
        table: String,
        id: Long,
    ): Int? = jdbc.query("SELECT active FROM $table WHERE id = ?", { rs, _ -> rs.getInt("active") }, id).firstOrNull()

    @Test
    @Order(1)
    @DisplayName("deleting a channel releases its chn- session on the runtime side first, using the channel's own id")
    fun deleteReleasesTheChannelSession() {
        val id = createChannel("it_chan_cascade_release_$suffix")
        val sessionId = sessionIdOf(id)

        assertOk(deleteJson("/api/admin/channels/$id"))

        // Nothing else names that conversation. A release keyed by the row id, or none at all, leaves the
        // state and the sandbox running under a name no row still points at.
        assertEquals(
            listOf(sessionId),
            clearedSessions(),
            "deleting a channel must release its own session on the runtime first, got: ${clearedSessions()}",
        )
    }

    @Test
    @Order(2)
    @DisplayName("when the runtime refuses the release the channel row stays put and the reason reaches the caller")
    fun refusalKeepsTheChannel() {
        val id = createChannel("it_chan_cascade_refuse_$suffix")
        val sessionId = sessionIdOf(id)
        fakeRouter.refusal = "sandbox container is busy"

        val refused = deleteJson("/api/admin/channels/$id")
        assertTrue(refused["code"].asInt() != 200, "a runtime refusal must fail the whole delete: $refused")
        assertTrue(
            refused["message"].asText().contains("sandbox container is busy"),
            "the runtime's own reason must reach the caller, got: ${refused["message"].asText()}",
        )
        assertEquals(listOf(sessionId), clearedSessions(), "the release was attempted, and it is what refused")

        // Still there, still listed, and `active` still 1 — which is what makes the refusal recoverable
        // rather than a half-done delete nobody can retry.
        assertEquals(1, activeOf("channel", id), "a refused delete must leave the channel row alone")
        assertEquals(sessionId, sessionIdOf(id), "and leave it readable to its owner")

        // And it goes through once the runtime stops refusing.
        fakeRouter.refusal = null
        assertOk(deleteJson("/api/admin/channels/$id"))
        assertEquals(listOf(sessionId, sessionId), clearedSessions(), "retrying releases the same session again")
        assertEquals(0, activeOf("channel", id), "the row falls only after the release succeeded")
    }

    @Test
    @Order(3)
    @DisplayName("channel delete is logical: row stays, active flips to 0, ownership still reads back")
    fun channelRowIsDeactivatedNotErased() {
        val name = "it_chan_cascade_soft_$suffix"
        val id = createChannel(name)
        val sessionId = sessionIdOf(id)

        assertOk(deleteJson("/api/admin/channels/$id"))

        // "Every delete is a logical delete" — erasing the row would take the audit trail, and the router's
        // ability to say whose that session was, with it. `ChannelMapper.selectOwnerBySessionId` is the read
        // that answers it, and it needs both columns to survive this write.
        assertEquals(0, activeOf("channel", id), "the channel row stays and only flips active")
        assertTrue(rowsNamed(name).isEmpty(), "a deleted channel stays out of the list")
        assertEquals(
            listOf(1L),
            jdbc.query("SELECT tenant_id FROM channel WHERE id = ?", { rs, _ -> rs.getLong("tenant_id") }, id),
            "the deleted row has to keep saying whose channel it was",
        )
        assertEquals(sessionId, sessionIdOf(id), "and keep the session id the runtime keys the state by")
    }

    @Test
    @Order(4)
    @DisplayName("deleting an invisible channel never disturbs the runtime side")
    fun invisibleChannelNeverReachesTheRuntime() {
        val id = createChannel("it_chan_cascade_hidden_$suffix", tenantId = otherTenant)
        fakeRouter.reset()

        val refused = deleteJson("/api/admin/channels/$id")

        // The ownership gate is admin's, and it has to stay in front of the release: asking the runtime
        // about a session this caller cannot name would turn the delete endpoint into a probe.
        assertTrue(clearedSessions().isEmpty(), "no release may go out for a channel the caller cannot see: ${clearedSessions()}")
        assertTrue(refused["code"].asInt() != 200, "a channel that is not this tenant's cannot be deleted: $refused")
        assertEquals(1, activeOf("channel", id), "another tenant's channel row stays untouched")

        assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/channels/$id", tenantId = otherTenant)))
        assertEquals(1, clearedSessions().size, "the owning tenant gets through, so the refusal above was the tenant")
    }

    @Test
    @Order(5)
    @DisplayName("a channel with no session id still deletes")
    fun channelWithoutASessionIdStillDeletes() {
        val id = createChannel("it_chan_cascade_no_session_$suffix")
        // The column is NOT NULL and the entity field a non-null String, so "no session" is the empty
        // string — the shape a row predating the mint could hold.
        jdbc.update("UPDATE channel SET session_id = '' WHERE id = ?", id)

        assertOk(deleteJson("/api/admin/channels/$id"))

        // Nothing to release is not a reason to refuse: no runtime state was ever minted under a name that
        // does not exist. Releasing an empty id, though, would ask the router about nothing at all.
        assertTrue(clearedSessions().isEmpty(), "an empty session id must not send a release: ${clearedSessions()}")
        assertEquals(0, activeOf("channel", id))
    }
}
