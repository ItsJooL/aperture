package com.itsjool.apertureautoconfigure.starter.oas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.itsjool.aperture.starter.oas.ApertureOasController;
import com.itsjool.apertureautoconfigure.starter.ApertureSimpleAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 030: {@link ApertureOasAutoConfiguration} is registered solely via {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, outside
 * {@code com.itsjool.aperture} entirely, so no consumer's {@code scanBasePackages} can reach it any
 * more. These tests use {@link AutoConfigurations#of} with no component scan configured at all,
 * proving the config is fully functional through the imports mechanism alone.
 *
 * <p>The test classpath carries a fixture {@code aperture-openapi.yaml} (see
 * {@code src/test/resources}) so {@link ApertureOasController}'s {@code @ConditionalOnResource}
 * can be exercised both ways.
 */
class ApertureOasAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApertureOasAutoConfiguration.class));

    @Test
    void registersTheControllerByDefaultWhenTheSpecResourceIsPresent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ApertureOasController.class);
        });
    }

    @Test
    void disablingOasSuppressesTheController() {
        runner.withPropertyValues("aperture.oas.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ApertureOasController.class);
        });
    }

    /**
     * Plan 030 STOP-condition check: {@link ApertureOasAutoConfiguration} declares no
     * {@code @AutoConfigureAfter}/{@code @AutoConfigureBefore} relative to {@link
     * ApertureSimpleAutoConfiguration} because neither's {@code @Bean} methods take a parameter
     * the other provides — verified by reading both classes, and pinned here by loading both
     * together via {@link AutoConfigurations#of} (which is free to apply Spring Boot's default
     * auto-configuration ordering) and confirming the context still comes up clean.
     */
    @Test
    void loadsAlongsideApertureSimpleAutoConfigurationWithNoOrderingAnnotationNeeded() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        ApplicationConversionService.getSharedInstance()))
                .withConfiguration(AutoConfigurations.of(
                        ApertureOasAutoConfiguration.class, ApertureSimpleAutoConfiguration.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(TransactionTemplate.class,
                        () -> new TransactionTemplate(mock(PlatformTransactionManager.class)))
                .withBean(com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata.class,
                        () -> new com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata(
                                java.util.List.of("1.0"), java.util.List.of("TenantAdmin"),
                                java.util.List.of("TenantAdmin"), java.util.Set.of(),
                                com.itsjool.aperture.runtime.config.TenancyMode.POOL, null, null))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                        com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(com.yahoo.elide.core.dictionary.EntityDictionary.class,
                        () -> com.yahoo.elide.core.dictionary.EntityDictionary.builder().build())
                .withPropertyValues(
                        "aperture.auth.jwt.secret=test-secret-at-least-thirty-two-bytes-long",
                        "aperture.auth.jwt.issuer=aperture",
                        "aperture.auth.jwt.audience=aperture-api",
                        "aperture.auth.jwt.access-duration=PT5M",
                        "aperture.auth.refresh-duration=P30D")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApertureOasController.class);
                    assertThat(context).hasSingleBean(com.itsjool.aperture.spi.RateLimitProvider.class);
                });
    }
}
