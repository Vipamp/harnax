package com.agnetix.harnax.scheduler

import com.agnetix.harnax.mapper.AgentTaskExecutionMapper
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.quartz.Scheduler
import org.quartz.SchedulerContext
import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Component

/**
 * Turns the R3 claim into something executable instead of an argument: a real Spring container constructs
 * the whole `harnax-scheduler` bean graph here, which is precisely the check this module never had — it has
 * no `@SpringBootTest`, so when a lazy proxy was added to hold a construction cycle open, nothing ever
 * asked whether the context can start at all.
 *
 * Every Spring-managed class of this package is registered as itself, the way component scan does in
 * production; only the outside world (Quartz, MyBatis, Micrometer) is stood in for. A constructor cycle
 * between production beans therefore fails this test with [BeanCreationException] — the same failure an
 * operator sees at boot.
 */
class SchedulerBeanGraphTest {

    @Test
    fun `the scheduler beans form an acyclic construction graph a real container can start`() {
        val context = AnnotationConfigApplicationContext()
        try {
            // RouterClient would otherwise fetch a system key from admin during its @PostConstruct.
            context.environment.propertySources.addFirst(
                MapPropertySource("test", mapOf("scheduler.api-key" to "test-key")),
            )
            context.register(Collaborators::class.java)
            val production = componentClasses()
            check(production.size >= 6) { "the scan only found $production — it stopped covering the module" }
            production.forEach { context.register(it) }
            context.refresh()

            // The graph did wire, and the observation layer reaches the store through the inventory.
            assertEquals(
                production.size,
                production.count { context.getBean(it) != null },
                "every scanned bean must be constructible",
            )
            context.getBean(HealthIndicator::class.java)
            assertEquals(
                0.0,
                context.getBean(MeterRegistry::class.java).get("scheduler.jobs.scheduled").gauge().value(),
                "the gauge has to be registered by the container itself, with no lazy proxy in the way",
            )
        } finally {
            context.close()
        }
    }

    /**
     * Control case for the one above, and a miniature of the wiring R3 removed: an observation bean that
     * reads the live count through the business service, while that service counts its load attempts
     * through the observation bean. Same constructor-injection mechanism as the real beans, and it fails
     * the same startup — which is precisely what the lazy proxy used to paper over. Without this case the
     * passing assertion above would only be evidence that "nothing happened".
     */
    @Test
    fun `a meter that reads through the business service closes the cycle this refactor removed`() {
        val context = AnnotationConfigApplicationContext()
        try {
            context.register(LegacyMetrics::class.java, LegacySchedulerService::class.java)
            assertThrows(BeanCreationException::class.java) { context.refresh() }
        } finally {
            context.close()
        }
    }

    /**
     * Spring-managed classes of this module. The boot application is left out (registering it would pull in
     * auto-configuration and a second component scan), and so are member classes — this file's own nested
     * test configuration lives on the scanned path too.
     */
    private fun componentClasses(): List<Class<*>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(Component::class.java))
        return scanner.findCandidateComponents(SCAN_PACKAGE)
            .map { beanClassOf(it) }
            .filterNot { it.isAnnotationPresent(SpringBootApplication::class.java) || it.isMemberClass }
            .sortedBy { it.name }
    }

    private fun beanClassOf(definition: BeanDefinition): Class<*> = Class.forName(definition.beanClassName!!)

    /**
     * Quartz's [SchedulerFactoryBean] is a FactoryBean *and* a BeanFactoryPostProcessor, so it is supplied
     * through an `@Bean` method — the production shape — rather than as a hand-registered singleton.
     */
    @Configuration(proxyBeanMethods = false)
    class Collaborators {

        @Bean
        fun schedulerFactoryBean(): SchedulerFactoryBean {
            val quartz = Mockito.mock(Scheduler::class.java)
            Mockito.doReturn(SchedulerContext()).`when`(quartz).context
            val factory = Mockito.mock(SchedulerFactoryBean::class.java)
            Mockito.doReturn(quartz).`when`(factory).scheduler
            return factory
        }

        @Bean
        fun agentTaskMapper(): AgentTaskMapper = Mockito.mock(AgentTaskMapper::class.java)

        @Bean
        fun agentTaskLogMapper(): AgentTaskLogMapper = Mockito.mock(AgentTaskLogMapper::class.java)

        @Bean
        fun agentTaskExecutionMapper(): AgentTaskExecutionMapper = Mockito.mock(AgentTaskExecutionMapper::class.java)

        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    /** Stands in for the pre-R3 `SchedulerMetrics`: the live count read through the business service. */
    class LegacyMetrics(
        val service: LegacySchedulerService,
    )

    /** Stands in for `SchedulerServiceImpl`: it reports its load attempts through the meter bean. */
    class LegacySchedulerService(
        val metrics: LegacyMetrics,
    )

    companion object {
        private const val SCAN_PACKAGE = "com.agnetix.harnax.scheduler"
    }
}
