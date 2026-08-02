package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Agent tool management regression: /api/admin/tools
 *
 * Tools cannot be created via API; builtin ToolBox beans (TimeToolBox/EmailToolBox)
 * are auto-registered on startup by BuiltinToolAutoRegistrar. Tests operate on
 * these builtin records.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentToolCrudIT : BaseAdminIT() {

    private var toolId: Long = -1
    private var toolName: String = ""

    private fun locateTool(): Long {
        if (toolId > 0) return toolId
        // sendEmail is safe to mutate: other ITs do not depend on it
        val record = findInPage("/api/admin/tools/page") { it["name"]?.asText() == "sendEmail" }
        assertNotNull(record, "builtin tool sendEmail should be auto-registered on startup")
        toolId = record["id"].asLong()
        toolName = record["name"].asText()
        return toolId
    }

    @Test
    @Order(1)
    fun `page query returns builtin tools`() {
        val data = assertOk(getJson("/api/admin/tools/page?pageNum=1&pageSize=50"))
        val names = data["records"].map { it["name"].asText() }
        assertTrue(names.contains("getDate"), "TimeToolBox getDate should be registered, got: $names")
        assertTrue(names.contains("getDatetime"), "TimeToolBox getDatetime should be registered, got: $names")
        assertTrue(names.contains("sendEmail"), "EmailToolBox sendEmail should be registered, got: $names")
    }

    @Test
    @Order(2)
    fun `builtin endpoint returns enabled builtin tools`() {
        val data = assertOk(getJson("/api/admin/tools/builtin"))
        assertTrue(data.isArray)
        val names = data.map { it["name"].asText() }
        assertTrue(names.contains("getDate"), "builtin list should contain getDate, got: $names")
        data.forEach { assertEquals("BUILTIN", it["type"].asText()) }
    }

    @Test
    @Order(3)
    fun `available endpoint returns enabled tools with type filter`() {
        val data = assertOk(getJson("/api/admin/tools/available?type=BUILTIN"))
        assertTrue(data.isArray && data.size() > 0)
        data.forEach { assertEquals("BUILTIN", it["type"].asText()) }
    }

    @Test
    @Order(4)
    fun `get detail returns tool info`() {
        val data = assertOk(getJson("/api/admin/tools/${locateTool()}"))
        assertEquals("sendEmail", data["name"].asText())
        assertEquals("BUILTIN", data["type"].asText())
    }

    @Test
    @Order(5)
    fun `update tool description and verify`() {
        val body = mapOf("description" to "IT updated description")
        assertOk(putJson("/api/admin/tools/update/${locateTool()}", body))

        val data = assertOk(getJson("/api/admin/tools/${locateTool()}"))
        assertEquals("IT updated description", data["description"].asText())
    }

    @Test
    @Order(6)
    fun `toggle tool status off removes it from available list`() {
        assertOk(putJson("/api/admin/tools/toggle/${locateTool()}?status=0"))
        var data = assertOk(getJson("/api/admin/tools/${locateTool()}"))
        assertEquals(0, data["status"].asInt())

        val available = assertOk(getJson("/api/admin/tools/available"))
        assertTrue(available.none { it["id"].asLong() == toolId }, "disabled tool must not be available")

        assertOk(putJson("/api/admin/tools/toggle/${locateTool()}?status=1"))
        data = assertOk(getJson("/api/admin/tools/${locateTool()}"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(7)
    fun `get detail of non-existent tool returns empty data`() {
        val node = getJson("/api/admin/tools/99999999")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull)
    }

    @Test
    @Order(8)
    fun `update non-existent tool fails`() {
        assertErr(putJson("/api/admin/tools/update/99999999", mapOf("description" to "x")))
    }

    @Test
    @Order(9)
    fun `delete tool then detail returns empty`() {
        assertOk(deleteJson("/api/admin/tools/${locateTool()}"))

        val node = getJson("/api/admin/tools/$toolId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull, "deleted tool should not be returned")
    }
}
