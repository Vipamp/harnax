package com.agnetix.harnax

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * Test-only Spring Boot configuration for harnax-entity module.
 * This module does not have a main application class,
 * so this is needed for @MybatisTest slice tests to bootstrap.
 */
@SpringBootApplication
@MapperScan("com.agnetix.harnax.mapper")
class EntityTestApplication
