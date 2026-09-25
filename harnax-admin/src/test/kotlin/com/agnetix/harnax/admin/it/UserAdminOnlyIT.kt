package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AGENT-25: the user domain answers to a global admin only.
 *
 * The tenant predicate on this table was never the point — `selectUserList` took its `tenantId` from
 * a query parameter and the detail read was a bare `selectById`, so any signed-in user carrying their
 * own JWT could page through every account on the platform with its email, phone and `isAdmin`. That
 * is a permission rule, and the only proof that a permission rule holds is a request from somebody
 * who must be refused. Every case here therefore runs as a real non-admin token, and each refusal is
 * paired with the same request succeeding (or the row being untouched) so a blanket 403 from a broken
 * route cannot pass for the gate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UserAdminOnlyIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val username = "it_member_$suffix"
    private val email = "it_member_$suffix@it.harnax.com"
    private val phone = "139${Random.nextLong(10000000, 99999999)}"
    private val ghostUsername = "it_ghost_$suffix"

    private var memberId: Long = -1

    /**
     * The gate reads `is_admin` off the caller's row, so the claim in this token is irrelevant to the
     * outcome; it is set to 0 to keep the fixture honest about who the caller is.
     */
    private fun memberToken(): String = jwtUtil.generateToken(memberId, username, 1L, 0)

    private fun memberRow(): JsonNode? = findInPage("/api/admin/users/page", "keyword=$username") {
        it["username"]?.asText() == username
    }

    private fun assertForbidden(node: JsonNode) {
        assertEquals(403, node["code"].asInt(), "expected the admin-only refusal, got: $node")
        val message = node["message"].asText()
        assertTrue(message.isNotBlank(), "the refusal has to name itself: $node")
        assertTrue(
            message != "error.user.admin_only",
            "the message must be a resolved text rather than the raw key, got: $message",
        )
        val payload = node["data"]
        assertTrue(payload == null || payload.isNull, "a refused call must not carry a payload: $node")
    }

    @Test
    @Order(1)
    fun `admin creates a non-admin user and still reads the whole list`() {
        val body = mapOf(
            "username" to username,
            "password" to "a".repeat(64),
            "nickname" to "IT Member $suffix",
            "email" to email,
            "phone" to phone,
        )
        assertOk(postJson("/api/admin/users", body))

        val record = memberRow()
        assertNotNull(record, "prerequisite member user should exist")
        memberId = record["id"].asLong()
        assertEquals(0, record["isAdmin"].asInt(), "prerequisite: this user is not an admin")
    }

    @Test
    @Order(2)
    fun `member cannot enumerate users`() {
        val response = exchange(HttpMethod.GET, "/api/admin/users/page?pageNum=1&pageSize=100", token = memberToken())
        assertEquals(200, response.statusCode.value(), "the refusal travels in the envelope, not the HTTP status")
        assertForbidden(parseBody(response))
    }

    @Test
    @Order(3)
    fun `member cannot read any user by id, including itself, while admin can`() {
        val adminRow = assertOk(getJson("/api/admin/users/1"))
        assertEquals("admin", adminRow["username"].asText(), "prerequisite: an admin read still works")

        assertForbidden(parseBody(exchange(HttpMethod.GET, "/api/admin/users/1", token = memberToken())))
        // Its own row too: a member's profile is served by /api/admin/mp/user/profile, not by this
        // endpoint, so leaving the self-read open would keep the id-harvesting shape alive.
        assertForbidden(parseBody(exchange(HttpMethod.GET, "/api/admin/users/$memberId", token = memberToken())))
    }

    @Test
    @Order(4)
    fun `member cannot promote itself to admin`() {
        val body = mapOf(
            "nickname" to "Self Promoted",
            "email" to email,
            "phone" to phone,
            "isAdmin" to 1,
        )
        val response = exchange(HttpMethod.PUT, "/api/admin/users/update/$memberId", body, memberToken())
        assertEquals(200, response.statusCode.value(), "the refusal travels in the envelope, not the HTTP status")
        assertForbidden(parseBody(response))

        val stored = memberRow()
        assertNotNull(stored, "the user should still exist")
        assertEquals(0, stored["isAdmin"].asInt(), "a refused update must not have flipped isAdmin")
        assertEquals("IT Member $suffix", stored["nickname"].asText(), "a refused update must not have written at all")

        // Same route, same DTO, same field types: only the caller changed. Without this the refusal
        // above could be a validation error on the body rather than the gate.
        val asAdmin = mapOf(
            "nickname" to "Renamed By Admin",
            "email" to email,
            "phone" to phone,
            "isAdmin" to 0,
        )
        assertOk(putJson("/api/admin/users/update/$memberId", asAdmin))
        assertEquals("Renamed By Admin", memberRow()?.get("nickname")?.asText())
    }

    @Test
    @Order(5)
    fun `member cannot create a user`() {
        val body = mapOf(
            "username" to ghostUsername,
            "password" to "b".repeat(64),
            "nickname" to "IT Ghost $suffix",
            "email" to "it_ghost_$suffix@it.harnax.com",
            "phone" to "138${Random.nextLong(10000000, 99999999)}",
        )
        assertForbidden(parseBody(exchange(HttpMethod.POST, "/api/admin/users", body, memberToken())))

        val ghost = findInPage("/api/admin/users/page", "keyword=$ghostUsername") {
            it["username"]?.asText() == ghostUsername
        }
        assertNull(ghost, "a refused create must not have left an account behind")
    }

    @Test
    @Order(6)
    fun `member cannot disable or delete a user`() {
        assertForbidden(parseBody(exchange(HttpMethod.PUT, "/api/admin/users/toggle/1?status=0", token = memberToken())))
        assertForbidden(parseBody(exchange(HttpMethod.DELETE, "/api/admin/users/$memberId", token = memberToken())))

        assertEquals(1, assertOk(getJson("/api/admin/users/1"))["status"].asInt(), "admin must still be enabled")
        assertNotNull(memberRow(), "the member must still exist after its own refused delete")
    }

    @Test
    @Order(7)
    fun `member cannot use the existence probes`() {
        // These answer "is this username/phone/email taken" for any value the caller names, which is
        // the same enumeration one row at a time.
        assertForbidden(parseBody(exchange(HttpMethod.GET, "/api/admin/users/check/username?username=admin", token = memberToken())))
        assertForbidden(parseBody(exchange(HttpMethod.GET, "/api/admin/users/check/phone?phone=$phone", token = memberToken())))
        assertForbidden(parseBody(exchange(HttpMethod.GET, "/api/admin/users/check/email?email=$email", token = memberToken())))

        assertEquals(
            true,
            assertOk(getJson("/api/admin/users/check/username?username=admin")).asBoolean(),
            "an admin still gets the answer",
        )
    }

    @Test
    @Order(8)
    fun `cleanup`() {
        assertOk(deleteJson("/api/admin/users/$memberId"))
        assertNull(memberRow(), "the probe user should be gone")
    }
}
