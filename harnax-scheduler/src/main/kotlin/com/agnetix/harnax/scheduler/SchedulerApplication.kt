package com.agnetix.harnax.scheduler

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
// This module's own mapper package, which holds the copies of the three task mappers release 2 moved in.
// Every production class here is typed against these interfaces and against `scheduler.entity`, so nothing in
// this module binds `com.agnetix.harnax.mapper` (harnax-entity) any more — that package is simply not
// scanned, and the only thing still shared with harnax-entity is its copies of the three XMLs, which
// `classpath*:mapper/*.xml` loads next to this module's. Their namespaces point at interfaces nothing scans,
// so MyBatis registers them in a Configuration no caller reaches: harmless, and explained in application.yml
// (Task 8 of docs/superpowers/plans/2026-09-14-scheduler-domain-migration.md deletes them).
@MapperScan("com.agnetix.harnax.scheduler.mapper")
class SchedulerApplication

fun main(args: Array<String>) {
    runApplication<SchedulerApplication>(*args)
}
