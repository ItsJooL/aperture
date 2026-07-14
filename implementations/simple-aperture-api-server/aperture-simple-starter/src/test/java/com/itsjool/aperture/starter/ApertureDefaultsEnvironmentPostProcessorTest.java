package com.itsjool.aperture.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the lowest-precedence defaults {@link ApertureDefaultsEnvironmentPostProcessor}
 * contributes to the environment.
 */
class ApertureDefaultsEnvironmentPostProcessorTest {

    private final ApertureDefaultsEnvironmentPostProcessor processor = new ApertureDefaultsEnvironmentPostProcessor();

    @Test
    void setsTracingSamplingProbabilityDefault() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");
    }

    @Test
    void disablesOtlpMetricsExportByDefault() {
        // micrometer-registry-otlp is an unconditional starter dependency, so Spring Boot's OTLP
        // metrics auto-config activates by classpath presence alone. With no receiver configured
        // (the reference implementation exposes metrics via the Prometheus pull endpoint instead),
        // leaving this enabled pushes to http://localhost:4318/v1/metrics every 60s and logs a WARN
        // on every failed attempt — runtime-confirmed as repeated log spam in the demo.
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("false");
    }

    @Test
    void defaultsAreLowestPrecedenceAndDoNotOverrideExplicitConfig() {
        // A consumer who explicitly opts back into OTLP metrics push (having configured a receiver)
        // must not be overridden by this default — MapPropertySource.addLast() must land below any
        // property source the application/consumer itself supplies.
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("management.otlp.metrics.export.enabled", "true");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("true");
    }
}
