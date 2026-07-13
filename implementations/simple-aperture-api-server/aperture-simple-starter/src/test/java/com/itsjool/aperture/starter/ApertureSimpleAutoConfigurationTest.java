package com.itsjool.aperture.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import com.itsjool.aperture.auth.ApiKeyService;
import com.itsjool.apertureautoconfigure.starter.ApertureSimpleAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.itsjool.aperture.spi.ServiceAccountIssuer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ApertureSimpleAutoConfigurationTest {
    @Test
    void wiresRefreshTokenSecurityAndTransactionDependencies() {
        ApertureSimpleAutoConfiguration outer =
                new ApertureSimpleAutoConfiguration(
                    mock(Environment.class),
                    new com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata(
                        java.util.List.of("1.0"),
                        java.util.List.of("TenantAdmin"),
                        java.util.List.of("TenantAdmin"),
                        java.util.Set.of(),
                        com.itsjool.aperture.runtime.config.TenancyMode.POOL,
                        null, null));
        SecureRandom random = outer.apertureSecureRandom();
        TransactionTemplate transactions =
                new TransactionTemplate(mock(PlatformTransactionManager.class));

        // refreshTokenService moved to SimpleAuthConfiguration (skipped when CredentialValidator present)
        ApertureSimpleAutoConfiguration.SimpleAuthConfiguration auth =
                new ApertureSimpleAutoConfiguration.SimpleAuthConfiguration();
        assertThat(auth.refreshTokenService(
                mock(JdbcTemplate.class), transactions, Clock.systemUTC(),
                Duration.ofDays(30), random)).isNotNull();
        assertThat(random).isInstanceOf(SecureRandom.class);
        assertThat(outer.apiKeyService(
                mock(JdbcTemplate.class), transactions, Clock.systemUTC(), random,
                new com.fasterxml.jackson.databind.ObjectMapper())).isNotNull();
    }

    /**
     * Plan 030: {@link ApertureSimpleAutoConfiguration} is registered solely via {@code
     * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} now,
     * outside {@code com.itsjool.aperture} entirely, so no consumer's broad {@code
     * scanBasePackages} can reach it any more. This runner has no component scan configured at
     * all — {@link AutoConfigurations#of} exercises exactly the auto-configuration-import
     * mechanism a real application uses, proving the starter (including the nested {@link
     * ApertureSimpleAutoConfiguration.SimpleAuthConfiguration}) is fully functional through that
     * mechanism alone.
     */
    @Test
    void loadsViaAutoConfigurationImportsAloneWithNoComponentScan() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        ApplicationConversionService.getSharedInstance()))
                .withConfiguration(AutoConfigurations.of(ApertureSimpleAutoConfiguration.class))
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
                    assertThat(context).hasSingleBean(com.itsjool.aperture.spi.RateLimitProvider.class);
                    assertThat(context).hasSingleBean(com.itsjool.aperture.spi.AuditWriter.class);
                    assertThat(context).hasSingleBean(com.itsjool.aperture.spi.EncryptionService.class);
                    // Proves the nested SimpleAuthConfiguration is processed too, without a separate import.
                    assertThat(context).hasSingleBean(com.itsjool.aperture.auth.AuthController.class);
                });
    }

    @Test
    void applicationProvidedApiKeyServiceBacksOffDefault() {
        ApiKeyService custom = mock(ApiKeyService.class);
        contextRunner()
                .withBean(ApiKeyService.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ApiKeyService.class)).hasSize(1);
                    assertThat(context).getBean(ApiKeyService.class).isSameAs(custom);
                });
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(ApertureSimpleAutoConfiguration.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(TransactionTemplate.class,
                        () -> new TransactionTemplate(mock(PlatformTransactionManager.class)))
                .withBean(com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata.class,
                        () -> new com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata(java.util.List.of("1.0"), java.util.List.of("TenantAdmin"), java.util.List.of("TenantAdmin"), java.util.Set.of(), com.itsjool.aperture.runtime.config.TenancyMode.POOL, null, null))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
                // apertureObservationFilter() needs a real (if empty) EntityDictionary to validate
                // aperture.entity tags against; this slice doesn't run Elide's own auto-config.
                .withBean(com.yahoo.elide.core.dictionary.EntityDictionary.class,
                        () -> com.yahoo.elide.core.dictionary.EntityDictionary.builder().build())
                .withPropertyValues(
                        "aperture.auth.jwt.secret=test-secret-at-least-thirty-two-bytes-long",
                        "aperture.auth.jwt.issuer=aperture",
                        "aperture.auth.jwt.audience=aperture-api",
                        "aperture.auth.jwt.access-duration=PT5M",
                        "aperture.auth.refresh-duration=P30D");
    }

    @Test
    void applicationProvidedServiceAccountIssuerBacksOffDefault() {
        ServiceAccountIssuer custom = mock(ServiceAccountIssuer.class);
        contextRunner()
                .withUserConfiguration(CustomIssuerConfiguration.class)
                .withPropertyValues(
                        "aperture.auth.jwt.secret=test-secret-at-least-thirty-two-bytes-long",
                        "aperture.auth.jwt.issuer=aperture",
                        "aperture.auth.jwt.audience=aperture-api",
                        "aperture.auth.jwt.access-duration=PT5M",
                        "aperture.auth.refresh-duration=P30D")
                .withBean("customServiceAccountIssuer", ServiceAccountIssuer.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(ServiceAccountIssuer.class).isSameAs(custom);
                    assertThat(context).doesNotHaveBean(com.itsjool.aperture.auth.SimpleServiceAccountIssuer.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomIssuerConfiguration {
    }
}
