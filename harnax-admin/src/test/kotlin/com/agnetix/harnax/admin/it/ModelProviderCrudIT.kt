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
 * Model provider CRUD regression: /api/admin/model-providers
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ModelProviderCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val providerName = "it_provider_$suffix"
    private val providerType = "it_prov_$suffix"

    private var providerId: Long = -1
    private var modelId: Long = -1

    private fun locateProviderId(): Long {
        if (providerId > 0) return providerId
        val record = findInPage("/api/admin/model-providers/page", "name=$providerName") {
            it["name"]?.asText() == providerName
        }
        assertNotNull(record, "created provider should be found in page result")
        providerId = record["id"].asLong()
        return providerId
    }

    @Test
    @Order(1)
    fun `create model provider succeeds`() {
        val body = mapOf(
            "type" to providerType,
            "name" to providerName,
            "description" to "IT provider",
            "apiKey" to "sk-it-test-key",
            "baseUrl" to "https://api.example.com/v1",
            "isPublic" to 1,
        )
        assertOk(postJson("/api/admin/model-providers", body))
    }

    @Test
    @Order(2)
    fun `page query finds created provider`() {
        assertTrue(locateProviderId() > 0)
    }

    @Test
    @Order(3)
    fun `get detail returns created provider`() {
        val data = assertOk(getJson("/api/admin/model-providers/${locateProviderId()}"))
        assertEquals(providerName, data["name"].asText())
        assertEquals(providerType, data["type"].asText())
        assertEquals("IT provider", data["description"].asText())
    }

    @Test
    @Order(4)
    fun `create provider with duplicate name fails`() {
        val body = mapOf("type" to "it_dup_$suffix", "name" to providerName)
        assertErr(postJson("/api/admin/model-providers", body))
    }

    @Test
    @Order(5)
    fun `create provider with invalid type pattern returns 400`() {
        assertErr(postJson("/api/admin/model-providers", mapOf("type" to "Bad-Type!", "name" to "it_provider_bad_$suffix")))
        assertErr(postJson("/api/admin/model-providers", mapOf("type" to "", "name" to "it_provider_notype_$suffix")))
    }

    @Test
    @Order(6)
    fun `update provider changes name and description`() {
        val body = mapOf(
            "type" to providerType,
            "name" to "$providerName-upd",
            "description" to "IT provider updated",
        )
        assertOk(putJson("/api/admin/model-providers/update/${locateProviderId()}", body))

        val data = assertOk(getJson("/api/admin/model-providers/$providerId"))
        assertEquals("$providerName-upd", data["name"].asText())
        assertEquals("IT provider updated", data["description"].asText())
    }

    @Test
    @Order(7)
    fun `connectivity test succeeds for existing provider`() {
        val data = assertOk(postJson("/api/admin/model-providers/${locateProviderId()}/test"))
        assertTrue(data.asBoolean())
    }

    @Test
    @Order(8)
    fun `provider with active model cannot be disabled or deleted`() {
        val modelBody = mapOf(
            "name" to "it_prov_model_$suffix",
            "modelName" to "it-prov-model-$suffix",
            "providerId" to locateProviderId(),
            "modelType" to "chat",
        )
        assertOk(postJson("/api/admin/models", modelBody))
        val record = findInPage("/api/admin/models/page", "providerId=$providerId") {
            it["name"]?.asText() == "it_prov_model_$suffix"
        }
        assertNotNull(record)
        modelId = record["id"].asLong()

        assertErr(putJson("/api/admin/model-providers/toggle/$providerId?status=0"))
        assertErr(deleteJson("/api/admin/model-providers/$providerId"))

        val stats = assertOk(getJson("/api/admin/model-providers/$providerId/stats"))
        assertEquals(1, stats["totalModels"].asInt())
        assertEquals(1, stats["enabledModels"].asInt())
    }

    @Test
    @Order(9)
    fun `toggle provider off and on after removing model`() {
        assertOk(deleteJson("/api/admin/models/$modelId"))

        assertOk(putJson("/api/admin/model-providers/toggle/${locateProviderId()}?status=0"))
        var data = assertOk(getJson("/api/admin/model-providers/$providerId"))
        assertEquals(0, data["status"].asInt())

        assertOk(putJson("/api/admin/model-providers/toggle/$providerId?status=1"))
        data = assertOk(getJson("/api/admin/model-providers/$providerId"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(10)
    fun `delete provider then detail returns empty`() {
        assertOk(deleteJson("/api/admin/model-providers/${locateProviderId()}"))

        val node = getJson("/api/admin/model-providers/$providerId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull, "deleted provider should not be returned")
    }
}
