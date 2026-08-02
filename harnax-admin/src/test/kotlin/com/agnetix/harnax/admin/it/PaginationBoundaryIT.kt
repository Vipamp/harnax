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
import kotlin.test.assertTrue

/**
 * Pagination boundary matrix: /api/admin/users/page (primary, full matrix)
 * plus a light structural pass over /api/admin/models/page (secondary).
 *
 * Both endpoints clamp the incoming parameters before calling
 * PageHelper.startPage (pageNum.coerceAtLeast(1), pageSize.coerceIn(1, 1000)),
 * wrap the result in dto.Page (pageNum/pageSize/total/records + computed
 * pages/hasPrevious/hasNext) and catch all service exceptions into
 * ResultVo.error, so no boundary input should ever surface as HTTP 5xx.
 *
 * Boundary cases covered:
 *  1. huge pageNum (99999)          -> empty records, total still correct
 *  2. pageSize=0                    -> clamped to 1, single-record first page
 *  3. negative pageNum / pageSize   -> clamped to 1, first page data
 *  4. huge pageSize (10000)         -> clamped to 1000, all rows on one page
 *  5. exact paging pageSize=1       -> total=3, pages=3, disjoint pages
 *  6. filter + pagination combo     -> total counts only filtered hits
 *  7. missing pageNum/pageSize      -> server defaults pageNum=1 pageSize=10
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PaginationBoundaryIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)

    /** Common keyword prefix so the users page can be filtered down to exactly our 3 rows. */
    private val prefix = "it_pgbnd_$suffix"
    private val usernames = (1..3).map { "${prefix}_u$it" }

    private val userIds = mutableListOf<Long>()

    private fun userPage(query: String): JsonNode = getJson("/api/admin/users/page?$query")

    private fun createUserBody(index: Int): Map<String, Any?> = mapOf(
        "username" to usernames[index],
        "password" to "abcdef123456",
        "nickname" to "IT PgBnd $suffix-$index",
        "email" to "${prefix}_u$index@it.harnax.com",
        "phone" to "13${index}${Random.nextLong(10000000, 99999999)}",
        "gender" to 1,
    )

    /** Assert a Page payload has the legal shape regardless of the boundary input. */
    private fun assertPageShape(data: JsonNode): JsonNode {
        assertNotNull(data["records"], "page data should contain records: $data")
        assertTrue(data["records"].isArray, "records should be an array: $data")
        assertTrue(data["total"].asLong() >= 0, "total should be non-negative: $data")
        assertTrue(data["pages"].asLong() >= 0, "pages should be non-negative: $data")
        return data
    }

    @Test
    @Order(1)
    fun `setup creates three users with distinct update_time`() {
        usernames.indices.forEach { i ->
            assertOk(postJson("/api/admin/users", createUserBody(i)))
            // The page query orders by update_time DESC and the column is
            // second-precision datetime; space creations out so the ordering
            // (and therefore page slicing) is deterministic across pages.
            if (i < usernames.lastIndex) Thread.sleep(1100)
        }
        usernames.forEach { name ->
            val record = findInPage("/api/admin/users/page", "keyword=$name") {
                it["username"]?.asText() == name
            }
            assertNotNull(record, "created user $name should be found in page result")
            userIds.add(record["id"].asLong())
        }
        assertEquals(3, userIds.size)
    }

    @Test
    @Order(2)
    fun `missing pageNum and pageSize fall back to defaults 1 and 10`() {
        // no pageNum/pageSize
        val data = assertPageShape(assertOk(userPage("keyword=$prefix")))
        assertEquals(1, data["pageNum"].asInt(), "default pageNum should be 1")
        assertEquals(10, data["pageSize"].asInt(), "default pageSize should be 10")
        assertEquals(3, data["total"].asInt())
        assertEquals(3, data["records"].size())

        // Unfiltered defaults: still page 1 / size 10, never more than 10 rows.
        val all = assertPageShape(assertOk(userPage("pageNum=1&pageSize=10")))
        assertTrue(all["records"].size() <= 10)
        assertTrue(all["total"].asLong() >= 3, "global total should include our 3 users")
    }

    @Test
    @Order(3)
    fun `pageSize 1 splits three rows into three disjoint pages`() {
        val page1 = assertPageShape(assertOk(userPage("keyword=$prefix&pageNum=1&pageSize=1")))
        assertEquals(3, page1["total"].asInt(), "total should be 3")
        assertEquals(3, page1["pages"].asInt(), "pages should be 3")
        assertEquals(1, page1["records"].size(), "page 1 should hold exactly 1 record")
        assertEquals(false, page1["hasPrevious"].asBoolean())
        assertEquals(true, page1["hasNext"].asBoolean())

        val page2 = assertPageShape(assertOk(userPage("keyword=$prefix&pageNum=2&pageSize=1")))
        assertEquals(1, page2["records"].size(), "page 2 should hold exactly 1 record")
        val name1 = page1["records"][0]["username"].asText()
        val name2 = page2["records"][0]["username"].asText()
        assertTrue(name1 != name2, "page 2 record ($name2) must differ from page 1 record ($name1)")

        val page3 = assertPageShape(assertOk(userPage("keyword=$prefix&pageNum=3&pageSize=1")))
        assertEquals(1, page3["records"].size(), "page 3 should hold exactly 1 record")
        assertEquals(false, page3["hasNext"].asBoolean())

        val seen = setOf(name1, name2, page3["records"][0]["username"].asText())
        assertEquals(usernames.toSet(), seen, "three pages together must cover the 3 created users exactly once")
    }

    @Test
    @Order(4)
    fun `huge pageNum returns empty records but correct total`() {
        val data = assertPageShape(assertOk(userPage("keyword=$prefix&pageNum=99999&pageSize=10")))
        assertEquals(0, data["records"].size(), "far-out-of-range page should be empty")
        assertEquals(3, data["total"].asInt(), "total must stay correct even when the page is empty")
    }

    @Test
    @Order(5)
    fun `huge pageSize is clamped to 1000 and returns everything on one page`() {
        val data = assertPageShape(assertOk(userPage("keyword=$prefix&pageNum=1&pageSize=10000")))
        assertEquals(1000, data["pageSize"].asInt(), "pageSize=10000 should be clamped to 1000")
        assertEquals(3, data["total"].asInt())
        assertEquals(3, data["records"].size(), "all matching rows should fit on the single huge page")
        assertEquals(1, data["pages"].asInt())
        assertEquals(false, data["hasNext"].asBoolean())
    }

    @Test
    @Order(6)
    fun `pageSize zero is clamped to 1 and returns a single-record first page`() {
        // The service clamps pageSize with coerceIn(1, 1000), so pageSize=0
        // behaves exactly like pageSize=1.
        val response = exchange(HttpMethod.GET, "/api/admin/users/page?keyword=$prefix&pageNum=1&pageSize=0")
        assertTrue(!response.statusCode.is5xxServerError, "pageSize=0 must not cause HTTP 5xx")
        val node = parseBody(response)
        assertNotNull(node["code"], "response should be a ResultVo: $node")
        assertEquals(200, node["code"].asInt(), "pageSize=0 should be clamped, not rejected: $node")
        val data = assertPageShape(node["data"])
        assertEquals(1, data["pageSize"].asInt(), "pageSize=0 should be clamped to 1")
        assertEquals(1, data["records"].size(), "clamped pageSize=1 should yield exactly one record")
        assertEquals(3, data["total"].asInt())
    }

    @Test
    @Order(7)
    fun `negative pageNum is clamped to 1 and returns the first page`() {
        // The service clamps pageNum with coerceAtLeast(1), so pageNum=-1
        // behaves exactly like page 1.
        val response = exchange(HttpMethod.GET, "/api/admin/users/page?keyword=$prefix&pageNum=-1&pageSize=10")
        assertTrue(!response.statusCode.is5xxServerError, "pageNum=-1 must not cause HTTP 5xx")
        val node = parseBody(response)
        assertNotNull(node["code"], "response should be a ResultVo: $node")
        assertEquals(200, node["code"].asInt(), "pageNum=-1 should be clamped, not rejected: $node")
        val data = assertPageShape(node["data"])
        assertEquals(1, data["pageNum"].asInt(), "pageNum=-1 should be clamped to 1")
        assertEquals(3, data["total"].asInt(), "negative pageNum should still report the correct total")
        assertEquals(3, data["records"].size(), "negative pageNum should degrade to first page")
    }

    @Test
    @Order(8)
    fun `negative pageSize is clamped to 1 and returns a single-record page`() {
        // The service clamps pageSize with coerceIn(1, 1000), so a negative
        // LIMIT can no longer reach MySQL; pageSize=-5 behaves like pageSize=1.
        val response = exchange(HttpMethod.GET, "/api/admin/users/page?keyword=$prefix&pageNum=1&pageSize=-5")
        assertTrue(!response.statusCode.is5xxServerError, "pageSize=-5 must not cause HTTP 5xx")
        val node = parseBody(response)
        assertNotNull(node["code"], "response should be a ResultVo: $node")
        assertEquals(200, node["code"].asInt(), "pageSize=-5 should be clamped, not rejected: $node")
        val data = assertPageShape(node["data"])
        assertEquals(1, data["pageSize"].asInt(), "pageSize=-5 should be clamped to 1")
        assertEquals(1, data["records"].size(), "clamped pageSize=1 should yield exactly one record")
        assertEquals(3, data["total"].asInt())
    }

    @Test
    @Order(9)
    fun `filter combined with pagination counts only matching rows`() {
        // Narrow filter: exact single username -> total=1 regardless of paging.
        val single = assertPageShape(assertOk(userPage("keyword=${usernames[0]}&pageNum=1&pageSize=10")))
        assertEquals(1, single["total"].asInt(), "filter hitting one row should report total=1")
        assertEquals(1, single["records"].size())
        assertEquals(usernames[0], single["records"][0]["username"].asText())

        // Broad filter + pageSize=2 -> total=3, pages=2, split 2 + 1.
        val page1 = assertPageShape(assertOk(userPage("keyword=$prefix&pageNum=1&pageSize=2")))
        assertEquals(3, page1["total"].asInt())
        assertEquals(2, page1["pages"].asInt())
        assertEquals(2, page1["records"].size())

        val page2 = assertPageShape(assertOk(userPage("keyword=$prefix&pageNum=2&pageSize=2")))
        assertEquals(1, page2["records"].size(), "second page of 3 rows at size 2 should hold the 1 remainder")

        // Filter that matches nothing -> total=0, empty records, pages=0.
        val none = assertPageShape(assertOk(userPage("keyword=${prefix}_no_such_user&pageNum=1&pageSize=10")))
        assertEquals(0, none["total"].asInt())
        assertEquals(0, none["records"].size())
        assertEquals(0, none["pages"].asInt())
    }

    @Test
    @Order(10)
    fun `models page endpoint survives the same boundary inputs`() {
        // Secondary resource: no fixtures created, purely structural checks
        // that /api/admin/models/page tolerates the same boundary parameters.
        val huge = assertPageShape(assertOk(getJson("/api/admin/models/page?pageNum=99999&pageSize=10")))
        assertEquals(0, huge["records"].size(), "far-out-of-range model page should be empty")

        val defaults = assertPageShape(assertOk(getJson("/api/admin/models/page")))
        assertEquals(1, defaults["pageNum"].asInt())
        assertEquals(10, defaults["pageSize"].asInt())
        assertTrue(defaults["records"].size() <= 10)

        listOf("pageNum=-1&pageSize=10", "pageNum=1&pageSize=0", "pageNum=1&pageSize=-5", "pageNum=1&pageSize=10000")
            .forEach { query ->
                val response = exchange(HttpMethod.GET, "/api/admin/models/page?$query")
                assertTrue(!response.statusCode.is5xxServerError, "models page with '$query' must not cause HTTP 5xx")
                val node = parseBody(response)
                assertNotNull(node["code"], "models page with '$query' should return a ResultVo: $node")
                if (node["code"].asInt() == 200) assertPageShape(node["data"])
            }
    }

    @Test
    @Order(11)
    fun `cleanup deletes created users`() {
        userIds.forEach { id -> assertOk(deleteJson("/api/admin/users/$id")) }

        val leftover = findInPage("/api/admin/users/page", "keyword=$prefix") {
            it["username"]?.asText()?.startsWith(prefix) == true
        }
        assertTrue(leftover == null, "no boundary-test user should survive cleanup")
    }
}
