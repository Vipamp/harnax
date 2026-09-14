package com.agnetix.harnax.scheduler.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

/**
 * Pins the cluster wiring as data rather than as an argument in a review: `job-store-type`, the Flyway
 * history table name, and the absence of an explicit `jobStore.class` are each a failure at boot if they
 * drift, and none of them are visible in a unit test that mocks the scheduler.
 */
class SchedulerQuartzConfigTest {

    private val props: java.util.Properties = org.springframework.beans.factory.config
        .YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }
        .also { it.afterPropertiesSet() }
        .getObject()!!

    private fun value(key: String): String? = props.getProperty("spring.$key")

    @Test
    fun `the job store is jdbc by default so a cluster shares one store`() {
        // The placeholder form is `${QUARTZ_JOB_STORE:jdbc}` — the default after the colon is the claim.
        // The trailing brace is part of the match because YamlPropertiesFactoryBean hands back the raw
        // placeholder, braces included.
        assertTrue(value("quartz.job-store-type")!!.endsWith(":jdbc}"), value("quartz.job-store-type"))
    }

    @Test
    fun `Spring is not told which jobStore class to use`() {
        // Boot must get to install LocalDataSourceJobStore itself; a hard-coded JobStoreTX disconnects
        // the Spring-managed DataSource and the cluster fails to check in.
        assertNull(
            props.propertyNames().toList().filterIsInstance<String>()
                .firstOrNull { it.endsWith("quartz.jobStore.class") },
        )
    }

    @Test
    fun `clustering is on with a check-in well inside the takeover window`() {
        assertEquals("true", value("quartz.properties.org.quartz.jobStore.isClustered"))
        assertEquals("15000", value("quartz.properties.org.quartz.jobStore.clusterCheckinInterval"))
        assertEquals("60000", value("quartz.properties.org.quartz.jobStore.misfireThreshold"))
        assertEquals("true", value("quartz.properties.org.quartz.jobStore.acquireTriggersWithinLock"))
    }

    @Test
    fun `job data maps are restricted to strings, which is why they carry ids not entities`() {
        assertEquals("true", value("quartz.properties.org.quartz.jobStore.useProperties"))
    }

    @Test
    fun `flyway owns the quartz schema and never lets Spring run its script on a restart`() {
        // YamlPropertiesFactoryBean does not resolve placeholders, so these assertions read the default
        // after the colon rather than a bound value.
        assertTrue(value("flyway.enabled")!!.endsWith(":true}"), value("flyway.enabled"))
        assertEquals("flyway_schema_history_scheduler", value("flyway.table"))
        assertEquals("never", value("quartz.jdbc.initialize-schema"))
    }
}
