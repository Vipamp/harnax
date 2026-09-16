package com.agnetix.harnax.scheduler.mapper

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertNotNull

/**
 * Proof that the move of the three task mappers was carried through the whole way, without a container.
 *
 * A MyBatis XML and the interface it binds to can disagree in total silence: the statement set still loads,
 * the interface still resolves, and the first caller to ask for a statement is the one that finds out. So
 * the agreement between the two is asserted here, on the resources and the classes themselves.
 */
class SchedulerMapperWiringTest {

    @Test
    fun `every scheduler mapper statement resolves to a scheduler-side interface`() {
        // A namespace that still points at harnax-entity's interface would bind a mapper nobody scans,
        // and the failure would surface at the first SQL call rather than at boot.
        val namespaces = listOf("AgentTaskMapper", "AgentTaskLogMapper", "AgentTaskExecutionMapper")
            .map { readNamespace("mapper/$it.xml") }
        namespaces.forEach {
            assertTrue(it.startsWith("com.agnetix.harnax.scheduler.mapper."), "namespace $it did not move")
            Class.forName(it) // the interface must exist where the XML says it does
        }
    }

    @Test
    fun `the entities the resultMaps name are this module's own`() {
        // Same argument one step further down: a resultMap still pointing at harnax-entity's AgentTask would
        // go on filling happily with a type this module's code does not use.
        listOf(
            "AgentTaskMapper" to "com.agnetix.harnax.scheduler.entity.AgentTask",
            "AgentTaskLogMapper" to "com.agnetix.harnax.scheduler.entity.AgentTaskLog",
            "AgentTaskExecutionMapper" to "com.agnetix.harnax.scheduler.entity.AgentTaskExecution",
        ).forEach { (file, type) ->
            val body = readResource("mapper/$file.xml")
            assertTrue(body.contains("type=\"$type\""), "$file still names a resultMap type outside $type")
            Class.forName(type)
        }
    }

    private fun readNamespace(resource: String): String {
        val match = assertNotNull(NAMESPACE.find(readResource(resource)), "$resource declares no mapper namespace")
        return match.groupValues[1]
    }

    // Reads this module's own copy of [resource].
    //
    // Nothing else should be on the classpath under this name now that release 2's Task 8 retired
    // harnax-entity's copies, but the filter stays: the whole point of this test is that it judges *this
    // module's* file, and a copy reappearing through some other jar would otherwise be read silently —
    // "whichever one the loader hands back first" is an accident of classpath ordering, not a statement
    // about this module.
    private fun readResource(resource: String): String {
        val loader = SchedulerMapperWiringTest::class.java.classLoader
        val candidates = Collections.list(loader.getResources(resource))
        val own = assertNotNull(
            candidates.find { !it.toString().contains("harnax-entity") },
            "$resource is only reachable through harnax-entity: $candidates",
        )
        return own.openStream().bufferedReader().use { it.readText() }
    }

    companion object {
        private val NAMESPACE = Regex("<mapper\\s+namespace=\"([^\"]+)\"")
    }
}
