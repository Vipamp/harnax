package com.agnetix.harnax.scheduler

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
// This module's own mapper package, which holds the copies of the three task mappers release 2 moved in.
// Two consequences of leaving `com.agnetix.harnax.mapper` (harnax-entity) un-scanned, both intended and both
// temporary: the old interfaces stop being Spring beans, so the services here still typed against them —
// `SchedulerServiceImpl`, `TaskScheduleReconciler`, `AgentTaskExecutionGuard`, `AbstractAgentTaskJob`,
// `TaskQuartzRegistrar` — have to be re-pointed by task 6 of the same plan before a real boot wires again
// (the module's unit tests never see it, they register their own mapper mocks), and the old XMLs are still
// loaded by `classpath*:mapper/*.xml`, which is harmless and explained in application.yml.
@MapperScan("com.agnetix.harnax.scheduler.mapper")
class SchedulerApplication

fun main(args: Array<String>) {
    runApplication<SchedulerApplication>(*args)
}
