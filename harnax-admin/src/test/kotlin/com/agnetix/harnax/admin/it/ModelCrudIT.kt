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
 * Model CRUD regression: /api/admin/models
 *
 * Models require an existing provider, so one is created up front and removed
 * after the model lifecycle has been verified.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ModelCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val providerName = "it_model_provider_$suffix"
    private val modelDisplayName = "it_model_$suffix"
    private val modelTechName = "it-model-$suffix"

    private var providerId: Long = -1
    private var modelId: Long = -1

    private fun ensureProvider(): Long {
        if (providerId > 0) return providerId
        val body = mapOf("type" to "it_mp_$suffix", "name" to providerName)
        assertOk(postJson("/api/admin/model-providers", body))
        val record = findInPage("/api/admin/model-providers/page", "name=$providerName") {
            it["name"]?.asText() == providerName
        }
        assertNotNull(record, "prerequisite provider should exist")
        providerId = record["id"].asLong()
        return providerId
    }

    private fun locateModelId(): Long {
        if (modelId > 0) return modelId
        val record = findInPage("/api/admin/models/page", "name=$modelDisplayName") {
            it["name"]?.asText() == modelDisplayName
        }
        assertNotNull(record, "created model should be found in page result")
        modelId = record["id"].asLong()
        return modelId
    }

    @Test
    @Order(1)
    fun `create model succeeds`() {
        val body = mapOf(
            "name" to modelDisplayName,
            "modelName" to modelTechName,
            "providerId" to ensureProvider(),
            "modelType" to "chat",
            "supportTool" to 1,
            "supportVision" to 0,
            "price" to 2.5,
            "description" to "IT model",
        )
        assertOk(postJson("/api/admin/models", body))
    }

    @Test
    @Order(2)
    fun `page query finds created model`() {
        assertTrue(locateModelId() > 0)
    }

    @Test
    @Order(3)
    fun `get detail returns created model`() {
        val data = assertOk(getJson("/api/admin/models/${locateModelId()}"))
        assertEquals(modelDisplayName, data["name"].asText())
        assertEquals(modelTechName, data["modelName"].asText())
        assertEquals(providerId, data["providerId"].asLong())
        assertEquals("chat", data["modelType"].asText())
        assertEquals(1, data["supportTool"].asInt())
        assertEquals(2.5, data["price"].asDouble())
    }

    @Test
    @Order(4)
    fun `create model with nonexistent provider fails`() {
        val body = mapOf(
            "name" to "it_model_np_$suffix",
            "modelName" to "it-model-np-$suffix",
            "providerId" to 999999999L,
            "modelType" to "chat",
        )
        assertErr(postJson("/api/admin/models", body))
    }

    @Test
    @Order(5)
    fun `create model with duplicate name under same provider fails`() {
        val dupName = mapOf(
            "name" to modelDisplayName,
            "modelName" to "it-model-other-$suffix",
            "providerId" to ensureProvider(),
            "modelType" to "chat",
        )
        assertErr(postJson("/api/admin/models", dupName))

        val dupTechName = mapOf(
            "name" to "it_model_other_$suffix",
            "modelName" to modelTechName,
            "providerId" to providerId,
            "modelType" to "chat",
        )
        assertErr(postJson("/api/admin/models", dupTechName))
    }

    @Test
    @Order(6)
    fun `create model without required fields returns 400`() {
        assertErr(
            postJson(
                "/api/admin/models",
                mapOf("name" to "", "modelName" to "x", "providerId" to ensureProvider(), "modelType" to "chat"),
            ),
        )
        assertErr(
            postJson(
                "/api/admin/models",
                mapOf("name" to "it_model_notype_$suffix", "modelName" to "x", "providerId" to providerId, "modelType" to ""),
            ),
        )
    }

    @Test
    @Order(7)
    fun `update model changes price and description`() {
        val body = mapOf(
            "name" to modelDisplayName,
            "modelName" to modelTechName,
            "providerId" to providerId,
            "modelType" to "chat",
            "price" to 9.9,
            "description" to "IT model updated",
        )
        assertOk(putJson("/api/admin/models/update/${locateModelId()}", body))

        val data = assertOk(getJson("/api/admin/models/$modelId"))
        assertEquals(9.9, data["price"].asDouble())
        assertEquals("IT model updated", data["description"].asText())
    }

    @Test
    @Order(8)
    fun `toggle model status off and on`() {
        assertOk(putJson("/api/admin/models/toggle/${locateModelId()}?status=0"))
        var data = assertOk(getJson("/api/admin/models/$modelId"))
        assertEquals(0, data["status"].asInt())

        assertOk(putJson("/api/admin/models/toggle/$modelId?status=1"))
        data = assertOk(getJson("/api/admin/models/$modelId"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(9)
    fun `delete model then detail returns empty`() {
        assertOk(deleteJson("/api/admin/models/${locateModelId()}"))

        val node = getJson("/api/admin/models/$modelId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull, "deleted model should not be returned")

        // Cleanup prerequisite provider
        assertOk(deleteJson("/api/admin/model-providers/$providerId"))
    }
}
