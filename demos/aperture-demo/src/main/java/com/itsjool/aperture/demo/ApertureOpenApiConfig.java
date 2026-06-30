package com.itsjool.aperture.demo;

import com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApertureOpenApiConfig {

    @Bean
    public OpenAPI apertureOpenAPI(ApertureRuntimeMetadata metadata) {
        return new OpenAPI()
            .info(new Info()
                .title("Aperture Demo API")
                .description("Generated multi-tenant SaaS API — framework auth, tenant management, and entity CRUD.")
                .version("1.0.0"))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
            .components(new Components()
                .addSecuritySchemes("bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
