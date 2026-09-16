package com.agnetix.harnax.scheduler

import com.agnetix.harnax.scheduler.job.AbstractAgentTaskJob
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.SchedulerService
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import com.agnetix.harnax.scheduler.service.impl.SchedulerServiceImpl
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mybatis.spring.annotation.MapperScan
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * What [SchedulerBeanGraphTest] cannot see, and how the module was left unable to boot.
 *
 * The bean-graph test builds the real container but supplies the three task mappers as hand-written `@Bean`
 * mocks *by type*, so it stays green for as long as production and this test agree on one type — whichever
 * type that is. `80eaff4` moved the mappers to `com.agnetix.harnax.scheduler.mapper` and re-pointed
 * `@MapperScan` there while five services kept injecting `com.agnetix.harnax.mapper.*`, and the test followed
 * production's import instead of the annotation: 160 green, no mapper bean at runtime, every injection
 * unsatisfiable on the first boot. The gap is structural — a mocked collaborator can never be missing.
 *
 * So this checks the two names that have to agree, by reflection and with no container: the task-domain
 * types the module's own services inject and pass around, and the package `@MapperScan` is told to scan.
 * A type from `harnax-entity` here is a bean nobody registers, which is exactly the bug; a mapper from a
 * package other than the scanned one is the same bug wearing a scheduler package name.
 *
 * Deliberately narrow, so it stays a red/green signal rather than a style check: only the six classes that
 * broke are swept, and only the task-domain types are judged. A cast inside a method body (like
 * `AbstractAgentTaskJob.taskToRun`'s `as? AgentTaskMapper`) is invisible to signature reflection and stays
 * under the plan's grep gate instead.
 */
class SchedulerMapperBindingTest {

    @Test
    fun `every task-domain type a service signature names is one this module scans`() {
        val scanned = scannedMapperPackage()
        val referenced = SERVICES
            .flatMap { service -> referencedTypes(service).map { service to it } }
            .filter { (_, type) -> type.simpleName in TASK_DOMAIN_TYPES }

        // An empty sweep passes every assertion below, so pin the sample first: AgentTaskMapper is the
        // injection that broke, and a sweep that stopped seeing it has stopped testing anything.
        assertTrue(
            referenced.any { it.second.simpleName == "AgentTaskMapper" },
            "the sweep found no AgentTaskMapper in the signatures of $SERVICES, so it proves nothing. " +
                "Referenced instead: ${referenced.map { it.second.name }.distinct()}",
        )

        val foreign = referenced.filter { (_, type) -> !type.name.startsWith(SCHEDULER_PACKAGE) }
        assertTrue(foreign.isEmpty()) {
            "task-domain types outside this module have no beans, since @MapperScan only scans $scanned. " +
                foreign.joinToString(prefix = "\n", separator = "\n") { (owner, type) ->
                    "${owner.name} uses ${type.name}"
                }
        }

        val unscanned = referenced.filter { (_, type) ->
            type.simpleName.endsWith("Mapper") && type.name.substringBeforeLast('.') != scanned
        }
        assertTrue(unscanned.isEmpty()) {
            "mapper types outside the @MapperScan package are never registered as beans. " +
                unscanned.joinToString(prefix = "\n", separator = "\n") { (owner, type) ->
                    "${owner.name} injects ${type.name}, which is not in the scanned $scanned"
                }
        }
    }

    /** The single package `@MapperScan` names on the boot application, read from the annotation itself. */
    private fun scannedMapperPackage(): String {
        val scan = checkNotNull(SchedulerApplication::class.java.getAnnotation(MapperScan::class.java)) {
            "SchedulerApplication lost its @MapperScan, so no mapper is a bean at all"
        }
        val packages = (scan.value.toList() + scan.basePackages.toList()).filter { it.isNotBlank() }.distinct()
        return checkNotNull(packages.singleOrNull()) {
            "@MapperScan names ${packages.size} packages ($packages); this test reads exactly one"
        }
    }

    /** Constructor parameters and method signatures — the places an unsatisfied injection lives. */
    private fun referencedTypes(service: Class<*>): Set<Class<*>> {
        val types = mutableSetOf<Class<*>>()
        service.constructors.forEach { c: Constructor<*> ->
            types += flatten(c.genericParameterTypes.toList())
        }
        service.declaredMethods.forEach { m: Method ->
            types += flatten(m.genericParameterTypes.toList())
            types += flatten(listOf(m.genericReturnType))
        }
        return types
    }

    /** A [Type] plus whatever it is parameterised with; `List` alone would hide `List<AgentTask>`. */
    private fun flatten(types: List<Type>): List<Class<*>> = types.flatMap { type -> classesOf(type) }

    private fun classesOf(type: Type): List<Class<*>> = when (type) {
        is Class<*> -> listOf(type)
        is ParameterizedType -> classesOf(type.rawType) + type.actualTypeArguments.flatMap { classesOf(it) }
        is WildcardType -> type.upperBounds.flatMap { classesOf(it) }
        else -> emptyList()
    }

    companion object {

        /** The classes that were still typed against `harnax-entity`'s mappers after the move. */
        private val SERVICES = listOf(
            AbstractAgentTaskJob::class.java,
            TaskQuartzRegistrar::class.java,
            AgentTaskExecutionGuard::class.java,
            SchedulerService::class.java,
            TaskScheduleReconciler::class.java,
            SchedulerServiceImpl::class.java,
        )

        /** The moved domain, by simple name: the types whose package decides whether a bean exists. */
        private val TASK_DOMAIN_TYPES = setOf(
            "AgentTask",
            "AgentTaskLog",
            "AgentTaskExecution",
            "AgentTaskMapper",
            "AgentTaskLogMapper",
            "AgentTaskExecutionMapper",
        )

        private const val SCHEDULER_PACKAGE = "com.agnetix.harnax.scheduler."
    }
}
