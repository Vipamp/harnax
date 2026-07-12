package com.agnetix.harnax.admin

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * Harnax Admin Backend Service Application Entry Point
 */
@SpringBootApplication(
    scanBasePackages = [
        "com.agnetix.harnax.admin",
        "com.agnetix.harnax.tools",
    ],
)
@MapperScan(basePackages = ["com.agnetix.harnax.mapper", "com.agnetix.harnax.admin.mapper"])
class HarnaxAdminApplication

fun main(args: Array<String>) {
    SpringApplication.run(HarnaxAdminApplication::class.java, *args)
    println("Harnax Admin Service Started Successfully!")
}
