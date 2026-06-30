package com.itsjool.aperture.starter.oas;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "aperture.oas.enabled", havingValue = "true", matchIfMissing = true)
public class ApertureOasAutoConfiguration {

    @Bean
    @ConditionalOnResource(resources = "classpath:aperture-openapi.yaml")
    public ApertureOasController apertureOasController() {
        return new ApertureOasController();
    }
}
