package com.itsjool.aperture.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

public class ApertureDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new HashMap<>();

        // Telemetry defaults (Plan 013)
        defaults.put("management.tracing.sampling.probability", "0.1");

        // Note: trace/span IDs are added to logs automatically by Spring Boot's
        // logging.pattern.correlation once a Tracer bean is present (micrometer-tracing-bridge-otel
        // is a starter dependency). Do not also edit logging.pattern.level — that double-prints them.

        // Add defaults with lowest precedence
        environment.getPropertySources().addLast(new MapPropertySource("apertureDefaults", defaults));
    }
}
