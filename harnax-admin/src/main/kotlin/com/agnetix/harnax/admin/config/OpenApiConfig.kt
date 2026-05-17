package com.agnetix.harnax.admin.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger/OpenAPI configuration
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun customOpenAPI(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Harnax Admin API")
                .version("1.0.0")
                .description("Harnax Backend Management System API Documentation")
                .contact(
                    Contact()
                        .name("Harnax Team")
                        .email("support@example.com"),
                )
                .license(
                    License()
                        .name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0"),
                ),
        )
}
