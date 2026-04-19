package com.vipamp.vipclaw

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * VIPClaw Admin 后端服务启动类
 */
@SpringBootApplication
@MapperScan("com.vipamp.vipclaw.admin.mapper")
class VipclawAdminApplication

fun main(args: Array<String>) {
    SpringApplication.run(VipclawAdminApplication::class.java, *args)
    println("VIPClaw Admin Service Started Successfully!")
}
