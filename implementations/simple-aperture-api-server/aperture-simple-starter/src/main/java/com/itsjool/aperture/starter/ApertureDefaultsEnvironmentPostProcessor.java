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

        // micrometer-registry-otlp is an unconditional starter dependency (backs the OTLP metrics
        // exporter for consumers who wire a collector), which means Spring Boot's OTLP metrics
        // auto-config is enabled by default the moment it's on the classpath — with nowhere
        // configured to send it, it pushes to http://localhost:4318/v1/metrics every 60s and logs a
        // WARN on every failed attempt. The reference implementation's metrics path is the
        // Prometheus *pull* endpoint (/actuator/prometheus); disable the OTLP metrics *push* by
        // default so consumers who haven't configured an OTLP metrics receiver don't get spammed.
        // Consumers who do want OTLP metrics push can override this with
        // management.otlp.metrics.export.enabled=true once they've configured a receiver.
        defaults.put("management.otlp.metrics.export.enabled", "false");

        // Add defaults with lowest precedence
        environment.getPropertySources().addLast(new MapPropertySource("apertureDefaults", defaults));
    }
}
