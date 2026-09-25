package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Agent tool read API regression: /api/admin/tools
 *
 * Tools are code-owned: BuiltinToolAutoRegistrar writes every row at startup and the API exposes no
 * write path any more, so this suite only asserts what the sync produced is visible on all reads.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentToolCrudIT : BaseAdminIT() {

    private var toolId: Long = -1

    private fun locateTool(): Long {
        if (toolId > 0) return toolId
        // sendEmail is a row other ITs do not depend on, so it is the safe detail target
        val record = findInPage("/api/admin/tools/page") { it["name"]?.asText() == "sendEmail" }
        assertNotNull(record, "tool sendEmail should be auto-registered on startup")
        toolId = record["id"].asLong()
        return toolId
    }

    @Test
    @Order(1)
    fun `page query returns the synced tools`() {
        val data = assertOk(getJson("/api/admin/tools/page?pageNum=1&pageSize=50"))
        val names = data["records"].map { it["name"].asText() }
        assertTrue(names.contains("getDate"), "TimeToolBox getDate should be registered, got: $names")
        assertTrue(names.contains("getDatetime"), "TimeToolBox getDatetime should be registered, got: $names")
        assertTrue(names.contains("sendEmail"), "EmailToolBox sendEmail should be registered, got: $names")
    }

    @Test
    @Order(2)
    fun `builtin endpoint returns every synced tool`() {
        val data = assertOk(getJson("/api/admin/tools/builtin"))
        assertTrue(data.isArray)
        val names = data.map { it["name"].asText() }
        assertTrue(names.contains("getDate"), "tool list should contain getDate, got: $names")
    }

    @Test
    @Order(3)
    fun `available endpoint returns bindable tools only`() {
        val data = assertOk(getJson("/api/admin/tools/available"))
        assertTrue(data.isArray && data.size() > 0)
        // Mandatory tools are injected at runtime, never selected here
        assertTrue(data.none { it["isRequired"].asInt() == 1 }, "available list must exclude mandatory tools")
    }

    @Test
    @Order(4)
    fun `get detail returns tool info`() {
        val data = assertOk(getJson("/api/admin/tools/${locateTool()}"))
        assertTrue(data["name"].asText() == "sendEmail")
        assertTrue(data["beanName"].asText().isNotEmpty(), "beanName is how the runtime resolves the ToolBox")
    }

    @Test
    @Order(5)
    fun `required env param endpoint answers for a synced tool`() {
        val data = assertOk(getJson("/api/admin/tools/${locateTool()}/required-env-params"))
        assertTrue(data.isArray)
    }

    @Test
    @Order(6)
    fun `get detail of non-existent tool reports 404`() {
        val node = getJson("/api/admin/tools/99999999")
        assertEquals(404, node["code"].asInt(), "a row that does not exist must not answer as success")
        assertTrue(node["data"] == null || node["data"].isNull)
        assertFalse(node["message"].asText().isEmpty(), "404 must carry a named message")
    }
}
