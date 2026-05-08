package com.vipamp.vipclaw.admin.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger/OpenAPI 配置类
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun customOpenAPI(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("VIPClaw Admin API")
                .version("1.0.0")
                .description("VIPClaw 后端管理系统 API 文档")
                .contact(
                    Contact()
                        .name("VIPClaw Team")
                        .email("support@example.com"),
                )
                .license(
                    License()
                        .name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0"),
                ),
        )
}
