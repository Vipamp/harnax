package com.agnetix.harnax.channel.service

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@MapperScan("com.agnetix.harnax.mapper")
@EnableScheduling
class ChannelServiceApplication

fun main(args: Array<String>) {
    runApplication<ChannelServiceApplication>(*args)
}
