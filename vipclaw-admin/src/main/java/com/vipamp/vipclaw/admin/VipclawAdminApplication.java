package com.vipamp.vipclaw.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * VIPClaw Admin 后端服务启动类
 */
@SpringBootApplication
@MapperScan(value = {"com.vipamp.vipclaw.common.mapper", "com.vipamp.vipclaw.admin.mapper"})
public class VipclawAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(VipclawAdminApplication.class, args);
        System.out.println("VIPClaw Admin Service Started Successfully!");
    }
}
