package com.agnetix.harnax.agent.service

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.agnetix.harnax.agent.service", "com.agnetix.harnax.agent.skill"])
@EnableScheduling
@MapperScan("com.agnetix.harnax.mapper")
class AgentServiceApplication

fun main(args: Array<String>) {
    runApplication<AgentServiceApplication>(*args)
}
