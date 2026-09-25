package com.agnetix.harnax.admin.it

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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Channel tenancy (AGENT-24): the column has been written since the first version, but no read of
 * `channel` ever filtered on it, so one workspace's channels — each carrying the platform credentials in
 * `configJson` — were listed for, opened by and deletable by the rest of the platform.
 *
 * The pair being proven is the one every other tenant-scoped domain uses: the list carries the caller's
 * tenant as a predicate, and a by-id read that misses answers exactly like a row nobody holds.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ChannelTenantIsolationIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val suffix = Random.nextInt(100000, 999999)

    /** A tenant that exists nowhere else, so these rows cannot collide with another class's. */
    private val otherTenant = 940_003L

    /** Reads as absent wherever it is used, so a refusal can be compared against a row nobody holds. */
    private val noSuchId = 999_999_998L

    private val ownChannel = "it_t1_channel_$suffix"
    private val otherChannel = "it_t2_channel_$suffix"

    private var ownId: Long = -1
    private var otherId: Long = -1

    /** Nothing behind `agent_id` is consulted on the way in, so one id serves both channels. */
    private val agentId = 987_654L

    /** Channels on a keyword-filtered page carrying exactly this name, as the given tenant sees them. */
    private fun rowsNamed(
        name: String,
        tenantId: Long? = null,
    ): List<JsonNode> {
        val data = assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/channels/page?pageNum=1&pageSize=50&keyword=$name", tenantId = tenantId)))
        val records = data["records"]
        return if (records == null || !records.isArray) emptyList() else records.filter { it["name"]?.asText() == name }
    }

    private fun rowId(
        name: String,
        tenantId: Long?,
    ): Long = rowsNamed(name, tenantId)
        .firstOrNull()
        ?.get("id")
        ?.asLong()
        ?: error("no channel named $name visible to tenant ${tenantId ?: "default"}")

    private fun createChannel(
        name: String,
        tenantId: Long?,
    ): JsonNode = exchange(
        HttpMethod.POST,
        "/api/admin/channels",
        mapOf("name" to name, "type" to "http", "agentId" to agentId, "description" to "before"),
        tenantId = tenantId,
    ).let { parseBody(it) }

    /** The message a refusal carries, compared rather than matched so the locale stays irrelevant. */
    private fun messageOf(node: JsonNode): String = node["message"].asText()

    private fun answersAsAbsent(node: JsonNode): Boolean = node["data"] == null || node["data"].isNull

    private fun storedDescription(id: Long): String? = jdbc.queryForObject(
        "SELECT description FROM channel WHERE id = ?",
        String::class.java,
        id,
    )

    @Test
    @Order(1)
    fun `a channel created under a tenant stores that tenant on the row`() {
        assertOk(createChannel(otherChannel, otherTenant))
        otherId = rowId(otherChannel, otherTenant)

        assertEquals(
            otherTenant,
            jdbc.queryForObject("SELECT tenant_id FROM channel WHERE id = ?", Long::class.java, otherId)!!,
        )
    }

    @Test
    @Order(2)
    fun `another tenant's channel is absent from the list and unreadable by id`() {
        assertOk(createChannel(ownChannel, null))
        ownId = rowId(ownChannel, null)

        assertTrue(rowsNamed(otherChannel, null).isEmpty(), "another workspace's channel must stay out of this list")
        assertEquals(1, rowsNamed(otherChannel, otherTenant).size, "the owning tenant still lists its own channel")

        val hidden = parseBody(exchange(HttpMethod.GET, "/api/admin/channels/$otherId", tenantId = ownTenantOfAdmin()))
        val absent = getJson("/api/admin/channels/$noSuchId")
        // The miss travels in the envelope, so neither answer may turn into an HTTP error status.
        assertEquals(200, exchange(HttpMethod.GET, "/api/admin/channels/$otherId").statusCode.value(), "an invisible channel must answer HTTP 200 with an envelope 404")
        assertEquals(absent["code"].asInt(), hidden["code"].asInt(), "an invisible channel must not read differently from a missing one")
        assertEquals(messageOf(absent), messageOf(hidden), "the refusal must not reveal that the channel exists at all")
        // getMessage echoes the key itself when the bundle lacks it, which would leave the two lines
        // above equal and make them pass on an untranslated response — so pin that it resolved.
        assertNotEquals("error.channel.notfound", messageOf(absent), "the refusal must be the bundle's text, not the key")
        assertEquals(404, hidden["code"].asInt(), "a read that misses is a 404, not an empty success")
        assertTrue(answersAsAbsent(hidden), "another tenant's channel must not be readable by id")
        assertTrue(answersAsAbsent(absent))

        assertEquals(otherChannel, assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/channels/$otherId", tenantId = otherTenant)))["name"].asText())
    }

    /** The tenant an admin token without `X-Tenant-ID` acts within: the column default. */
    private fun ownTenantOfAdmin(): Long? = null

    @Test
    @Order(3)
    fun `update toggle and delete by a tenant that does not own the channel are refused as absent`() {
        val write = mapOf("description" to "hijacked")

        val refused = parseBody(exchange(HttpMethod.PUT, "/api/admin/channels/update/$otherId", write))
        val refusedAsMissing = parseBody(exchange(HttpMethod.PUT, "/api/admin/channels/update/$noSuchId", write))
        assertErr(refused)
        assertEquals(messageOf(refusedAsMissing), messageOf(refused), "the refusal should not reveal that the channel exists at all")
        assertEquals("before", storedDescription(otherId), "a refused update must not have landed")

        assertErr(parseBody(exchange(HttpMethod.PUT, "/api/admin/channels/toggle/$otherId?status=0")))
        assertEquals(1, jdbc.queryForObject("SELECT status FROM channel WHERE id = ?", Int::class.java, otherId)!!, "a refused toggle must not have landed")

        assertErr(parseBody(exchange(HttpMethod.DELETE, "/api/admin/channels/$otherId")))
        assertEquals(1, jdbc.queryForObject("SELECT active FROM channel WHERE id = ?", Int::class.java, otherId)!!, "a refused delete must not have taken the row")

        // The same three calls from the owning tenant go through, so the refusals above came from the
        // tenant and not from anything about the requests.
        assertOk(parseBody(exchange(HttpMethod.PUT, "/api/admin/channels/update/$otherId", write, tenantId = otherTenant)))
        assertEquals("hijacked", storedDescription(otherId))
    }

    @Test
    @Order(4)
    fun `cleanup`() {
        assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/channels/$otherId", tenantId = otherTenant)))
        assertOk(deleteJson("/api/admin/channels/$ownId"))
        assertTrue(rowsNamed(otherChannel, otherTenant).isEmpty())
    }
}
