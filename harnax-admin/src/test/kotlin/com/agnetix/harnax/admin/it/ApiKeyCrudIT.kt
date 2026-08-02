package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * API Key CRUD regression: /api/admin/api-keys
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ApiKeyCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val keyName = "it_apikey_$suffix"

    private var keyId: Long = -1
    private var rawKey: String = ""

    @Test
    @Order(1)
    fun `create api key returns raw key once`() {
        val body = mapOf(
            "name" to keyName,
            "scopes" to "api:chat,api:session",
            "rateLimit" to 30,
        )
        val data = assertOk(postJson("/api/admin/api-keys", body))
        keyId = data["id"].asLong()
        rawKey = data["rawKey"].asText()
        assertTrue(keyId > 0)
        assertTrue(rawKey.startsWith("hnx_sk_live_"), "raw key should have hnx_sk_live_ prefix: $rawKey")
        assertTrue(data["keyPrefix"].asText().isNotBlank())
    }

    @Test
    @Order(2)
    fun `page query finds created key without raw key`() {
        val record = findInPage("/api/admin/api-keys/page", "keyword=$keyName") {
            it["name"]?.asText() == keyName
        }
        assertNotNull(record)
        assertEquals(keyId, record["id"].asLong())
        assertTrue(record["rawKey"] == null, "page response must not contain rawKey")
    }

    @Test
    @Order(3)
    fun `get detail returns created key`() {
        val data = assertOk(getJson("/api/admin/api-keys/$keyId"))
        assertEquals(keyName, data["name"].asText())
        assertEquals(30, data["rateLimit"].asInt())
        assertEquals(1, data["enabled"].asInt())
    }

    @Test
    @Order(4)
    fun `update api key scopes and rate limit`() {
        val body = mapOf("scopes" to "api:chat", "rateLimit" to 99)
        assertOk(putJson("/api/admin/api-keys/update/$keyId", body))

        val data = assertOk(getJson("/api/admin/api-keys/$keyId"))
        assertEquals("api:chat", data["scopes"].asText())
        assertEquals(99, data["rateLimit"].asInt())
    }

    @Test
    @Order(5)
    fun `toggle api key off and on`() {
        assertOk(putJson("/api/admin/api-keys/toggle/$keyId?enabled=0"))
        var data = assertOk(getJson("/api/admin/api-keys/$keyId"))
        assertEquals(0, data["enabled"].asInt())

        assertOk(putJson("/api/admin/api-keys/toggle/$keyId?enabled=1"))
        data = assertOk(getJson("/api/admin/api-keys/$keyId"))
        assertEquals(1, data["enabled"].asInt())
    }

    @Test
    @Order(6)
    fun `regenerate api key returns new raw key`() {
        val data = assertOk(postJson("/api/admin/api-keys/$keyId/regenerate"))
        val newRaw = data["rawKey"].asText()
        assertTrue(newRaw.startsWith("hnx_sk_live_"))
        assertTrue(newRaw != rawKey, "regenerated key must differ from the original")
    }

    @Test
    @Order(7)
    fun `create with duplicate name fails`() {
        val body = mapOf("name" to keyName, "scopes" to "api:chat")
        assertErr(postJson("/api/admin/api-keys", body))
    }

    @Test
    @Order(8)
    fun `create without required fields returns 400`() {
        assertErr(postJson("/api/admin/api-keys", mapOf("name" to "", "scopes" to "api:chat")))
        assertErr(postJson("/api/admin/api-keys", mapOf("name" to "it_apikey_noscope_$suffix", "scopes" to "")))
    }

    @Test
    @Order(9)
    fun `permanent key cannot be updated deleted or toggled`() {
        // PermanentKeyInitializer creates permanent_admin for user 1 at startup
        val data = assertOk(getJson("/api/admin/api-keys/my-permanent-key"))
        assertTrue(!data.isNull, "admin permanent key should exist after startup")
        val permanentId = data["id"].asLong()

        assertErr(putJson("/api/admin/api-keys/update/$permanentId", mapOf("rateLimit" to 1)))
        assertErr(putJson("/api/admin/api-keys/toggle/$permanentId?enabled=0"))
        assertErr(deleteJson("/api/admin/api-keys/$permanentId"))
    }

    @Test
    @Order(10)
    fun `delete api key then detail returns empty`() {
        assertOk(deleteJson("/api/admin/api-keys/$keyId"))

        val node = getJson("/api/admin/api-keys/$keyId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull, "deleted key should not be returned")

        val record = findInPage("/api/admin/api-keys/page", "keyword=$keyName") {
            it["name"]?.asText() == keyName
        }
        assertTrue(record == null, "deleted key should not appear in page result")
    }
}
