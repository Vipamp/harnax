package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Agent tool management regression: /api/admin/tools
 *
 * Tools cannot be created via API; builtin ToolBox beans (TimeToolBox/EmailToolBox)
 * are auto-registered on startup by BuiltinToolAutoRegistrar. Since every registered record
 * is builtin, this suite covers the read endpoints and the write rejection of builtin rows —
 * the CUSTOM/HTTP write paths are covered by AgentToolServiceImplTest with a mocked mapper,
 * as they cannot be reached without a create API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentToolCrudIT : BaseAdminIT() {

    private var toolId: Long = -1

    private fun locateTool(): Long {
        if (toolId > 0) return toolId
        // sendEmail is a builtin row other ITs do not depend on, so it is the safe write target to probe
        val record = findInPage("/api/admin/tools/page") { it["name"]?.asText() == "sendEmail" }
        assertNotNull(record, "builtin tool sendEmail should be auto-registered on startup")
        toolId = record["id"].asLong()
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
    fun `builtin endpoint returns every registered builtin tool`() {
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
    fun `update on a builtin tool is rejected and leaves the record untouched`() {
        val body = mapOf("description" to "IT should not be able to write this")
        assertErr(putJson("/api/admin/tools/update/${locateTool()}", body))

        val data = assertOk(getJson("/api/admin/tools/${locateTool()}"))
        assertNotEquals("IT should not be able to write this", data["description"].asText())
    }

    @Test
    @Order(6)
    fun `toggle on a builtin tool is rejected and keeps it enabled`() {
        assertErr(putJson("/api/admin/tools/toggle/${locateTool()}?status=0"))

        val data = assertOk(getJson("/api/admin/tools/${locateTool()}"))
        assertEquals(1, data["status"].asInt(), "builtin status is code-owned and stays enabled")

        val available = assertOk(getJson("/api/admin/tools/available"))
        assertTrue(available.any { it["id"].asLong() == toolId }, "builtin tool stays available")
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
    fun `delete on a builtin tool is rejected and the record survives`() {
        assertErr(deleteJson("/api/admin/tools/${locateTool()}"))

        val data = assertOk(getJson("/api/admin/tools/$toolId"))
        assertEquals("sendEmail", data["name"].asText(), "builtin row must survive a delete attempt")
    }
}
