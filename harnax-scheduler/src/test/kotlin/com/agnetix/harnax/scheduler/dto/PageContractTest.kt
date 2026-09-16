package com.agnetix.harnax.scheduler.dto

import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals

/**
 * The shape of the pagination envelope is a contract three clients read — the webui, the mini-program and
 * the CLI all walk `data.records` and decide whether to fetch another page off `data.hasNext`. Renaming a
 * key here breaks all three at once and breaks it silently, because a missing key reads as null, not as an
 * error. This file is what keeps `Page` a copy of admin's class rather than a fork of it.
 */
class PageContractTest {

    private val mapper = ObjectMapper()

    @Test
    fun `the serialized page carries exactly the seven keys the clients read`() {
        val page = Page(
            pageNum = 2,
            pageSize = 10,
            total = 25,
            records = listOf("first", "second"),
        )
        val serialized = mapper.writeValueAsString(page)
        val keys = mapper.readValue(serialized, Map::class.java).keys.map { it.toString() }.sorted()

        assertEquals(
            listOf("hasNext", "hasPrevious", "pageNum", "pageSize", "pages", "records", "total"),
            keys,
            "the page envelope gained or lost a key: $serialized",
        )

        // And the four computed/echoed numbers have to mean what admin's Page means, not merely be present.
        val node = mapper.readValue(serialized, Map::class.java)
        assertEquals(2L, (node["pageNum"] as Number).toLong())
        assertEquals(10L, (node["pageSize"] as Number).toLong())
        assertEquals(25L, (node["total"] as Number).toLong())
        assertEquals(3L, (node["pages"] as Number).toLong(), "25 rows of 10 are three pages")
        assertEquals(true, node["hasPrevious"], "page 2 has a page before it")
        assertEquals(true, node["hasNext"], "page 2 of 3 has a page after it")
    }

    @Test
    fun `mapping the records keeps the envelope itself intact`() {
        // Task 6 hands the client a `Page<Response>` built off `Page<Entity>`; the pagination keys a client
        // re-reads after that transform must be the ones it sent.
        val mapped = Page(pageNum = 3, pageSize = 5, total = 11, records = listOf(1, 2, 3)).mapRecords { it.toString() }
        val keys = mapper.readValue(mapper.writeValueAsString(mapped), Map::class.java).keys.map { it.toString() }.sorted()

        assertEquals(
            listOf("hasNext", "hasPrevious", "pageNum", "pageSize", "pages", "records", "total"),
            keys,
            "mapRecords changed the envelope, not just the rows",
        )
        assertEquals(3L, mapped.pageNum)
        assertEquals(5L, mapped.pageSize)
        assertEquals(11L, mapped.total)
        assertEquals(listOf("1", "2", "3"), mapped.records)
    }
}
