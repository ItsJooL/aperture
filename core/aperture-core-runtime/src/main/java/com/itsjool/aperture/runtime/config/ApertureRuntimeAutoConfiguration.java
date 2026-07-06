package com.itsjool.aperture.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.runtime.cors.ApertureCorsFilter;
import com.itsjool.aperture.runtime.cors.CorsProperties;
import com.itsjool.aperture.runtime.config.ApertureRateLimitProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@ComponentScan(basePackages = "com.itsjool.aperture.runtime")
@EnableConfigurationProperties({CorsProperties.class, ApertureRateLimitProperties.class})
public class ApertureRuntimeAutoConfiguration {
    @Bean
    public ApertureRuntimeMetadata apertureRuntimeMetadata(ObjectMapper objectMapper) {
        return new ApertureRuntimeMetadataLoader(objectMapper).load();
    }

    @Bean
    @ConditionalOnProperty(name = "aperture.cors.enabled", havingValue = "true")
    public FilterRegistrationBean<ApertureCorsFilter> apertureCorsFilter(
            CorsProperties corsProperties,
            ApertureRuntimeMetadata metadata) {
        corsProperties.validate();
        FilterRegistrationBean<ApertureCorsFilter> registration =
                new FilterRegistrationBean<>(new ApertureCorsFilter(corsProperties, metadata));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }
}
