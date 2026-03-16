package com.vipamp.vipclaw.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * VIPClaw Admin 后端服务启动类
 */
@SpringBootApplication
public class VipclawAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(VipclawAdminApplication.class, args);
        System.out.println("VIPClaw Admin Service Started Successfully!");
    }
}
