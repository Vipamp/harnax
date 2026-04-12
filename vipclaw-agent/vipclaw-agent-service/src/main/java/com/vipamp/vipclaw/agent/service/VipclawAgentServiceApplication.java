package com.vipamp.vipclaw.agent.service;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan(value = {"com.vipamp.vipclaw.common.mapper"})
public class VipclawAgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VipclawAgentServiceApplication.class, args);
    }
}
