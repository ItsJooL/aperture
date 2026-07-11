package com.itsjool.aperture.starter;

import com.itsjool.aperture.audit.AuditQueryController;
import com.itsjool.aperture.audit.JdbcAuditWriter;
import com.itsjool.aperture.auth.ApiKeyService;
import com.itsjool.aperture.auth.AuthController;
import com.itsjool.aperture.auth.AuthUserService;
import com.itsjool.aperture.auth.JwtTokenService;
import com.itsjool.aperture.auth.RefreshTokenService;
import com.itsjool.aperture.auth.SimpleCredentialValidator;
import com.itsjool.aperture.auth.SimpleIdentityAdministrationProvider;
import com.itsjool.aperture.auth.SimplePrincipalMapper;
import com.itsjool.aperture.auth.SimpleServiceAccountIssuer;
import com.itsjool.aperture.auth.SimpleTenantLifecycleProvider;
import com.itsjool.aperture.encryption.LocalEncryptionService;
import com.itsjool.aperture.ratelimit.InMemoryRateLimitProvider;
import com.itsjool.aperture.ratelimit.ValkeyRateLimitProvider;
import com.itsjool.aperture.runtime.config.ApertureRateLimitProperties;
import com.itsjool.aperture.spi.CredentialValidator;
import com.itsjool.aperture.spi.PrincipalMapper;
import com.itsjool.aperture.spi.RateLimitProvider;
import com.itsjool.aperture.spi.ServiceAccountIssuer;
import com.yahoo.elide.ElideSettingsBuilderCustomizer;
import com.yahoo.elide.core.dictionary.EntityBinding;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.core.type.ClassType;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

@Configuration
@EntityScan("com.itsjool.aperture.generated")
@org.springframework.context.annotation.Import(com.itsjool.aperture.runtime.tenant.TenantWebMvcConfiguration.class)
public class ApertureSimpleAutoConfiguration {

    static {
        try {
            Class.forName("com.itsjool.aperture.generated.package-info");
        } catch (Exception ignored) {}
    }

    private final Environment env;
    private final com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata metadata;

    public ApertureSimpleAutoConfiguration(Environment env, com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata metadata) {
        this.env = env;
        this.metadata = metadata;

        // Load package-info classes early so Elide's auto-scanner sees the @ApiVersion annotations
        org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(com.yahoo.elide.annotation.Include.class));
        java.util.Set<String> packageInfosLoaded = new java.util.HashSet<>();
        for (org.springframework.beans.factory.config.BeanDefinition bd : scanner.findCandidateComponents("com.itsjool.aperture.generated")) {
            String className = bd.getBeanClassName();
            String pkgName = className.substring(0, className.lastIndexOf('.'));
            if (packageInfosLoaded.add(pkgName)) {
                try {
                    Class.forName(pkgName + ".package-info");
                } catch (Exception e) {
                    // Package-info loading is best-effort; Elide can still discover unversioned models.
                }
            }
        }
    }

    @PostConstruct
    public void validateConfiguration() {
        if (Arrays.asList(env.getActiveProfiles()).contains("production")) {
            String allowLocalEnc = env.getProperty("APERTURE_ALLOW_LOCAL_ENCRYPTION");
            if (!"true".equals(allowLocalEnc)) {
                throw new IllegalStateException("Local encryption is not allowed in production without APERTURE_ALLOW_LOCAL_ENCRYPTION=true");
            }
        }
    }

    // ── Beans always present regardless of auth provider ────────────────────

    @Bean
    @ConditionalOnMissingBean(com.itsjool.aperture.spi.EncryptionService.class)
    public LocalEncryptionService encryptionService(@Value("${aperture.encryption.local.key:MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=}") String key) {
        return new LocalEncryptionService(key);
    }

    @Bean
    @ConditionalOnMissingBean(RateLimitProvider.class)
    public RateLimitProvider rateLimitProvider(
            ObjectProvider<ApertureRateLimitProperties> rateLimitPropertiesProvider,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider) {
        ApertureRateLimitProperties rateLimitProperties = rateLimitPropertiesProvider.getIfAvailable();
        if (rateLimitProperties != null && "valkey".equalsIgnoreCase(rateLimitProperties.getBackend())) {
            return new ValkeyRateLimitProvider(rateLimitProperties.getValkey());
        }
        return new InMemoryRateLimitProvider(meterRegistryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock apertureClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public SecureRandom apertureSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder aperturePasswordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    @ConditionalOnMissingBean(ApiKeyService.class)
    public ApiKeyService apiKeyService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            Clock apertureClock,
            SecureRandom apertureSecureRandom,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new ApiKeyService(jdbcTemplate, transactionTemplate, apertureClock, apertureSecureRandom, objectMapper);
    }

    @Bean
    public SimpleTenantLifecycleProvider tenantLifecycleProvider(JdbcTemplate jdbcTemplate) {
        return new SimpleTenantLifecycleProvider(jdbcTemplate);
    }

    @Bean
    public SimpleIdentityAdministrationProvider simpleIdentityAdministrationProvider(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            PasswordEncoder passwordEncoder,
            com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata metadata,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            ApiKeyService apiKeyService,
            ServiceAccountIssuer serviceAccountIssuer) {
        return new SimpleIdentityAdministrationProvider(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbcTemplate),
                transactionTemplate,
                passwordEncoder,
                metadata.roleCatalog(),
                objectMapper,
                apiKeyService,
                serviceAccountIssuer,
                metadata.securityAttributeDefinitions());
    }

    @Bean
    @ConditionalOnMissingBean
    public com.itsjool.aperture.runtime.hook.HookExecutor hookExecutor(
        @Value("${aperture.hooks.secret:default-hook-secret}") String hookSecret,
        @Value("${aperture.hooks.base-url:}") String hookBaseUrl,
        @Value("${aperture.hooks.timeout.commit:PT5S}") Duration commitTimeout,
        @Value("${aperture.hooks.timeout.async:PT30S}") Duration asyncTimeout,
        @Value("${aperture.hooks.timeout.connect:PT2S}") Duration connectTimeout,
        org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistryProvider) {
        return new com.itsjool.aperture.runtime.hook.HookExecutor(hookSecret, hookBaseUrl, commitTimeout, asyncTimeout, connectTimeout, observationRegistryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(PrincipalMapper.class)
    public SimplePrincipalMapper principalMapper(com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata metadata) {
        return new SimplePrincipalMapper(metadata.securityAttributeKeys());
    }

    @Bean
    @ConditionalOnMissingBean
    public com.itsjool.aperture.audit.JdbcAuditWriter auditWriter(
        JdbcTemplate jdbcTemplate,
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider,
        org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistryProvider) {
        return new com.itsjool.aperture.audit.JdbcAuditWriter(jdbcTemplate, meterRegistryProvider.getIfAvailable(), observationRegistryProvider.getIfAvailable());
    }

    @Bean
    public ApertureObservationFilter apertureObservationFilter() {
        return new ApertureObservationFilter();
    }

    @Bean
    public com.itsjool.aperture.runtime.audit.AuditBridge auditBridge(
            com.itsjool.aperture.spi.AuditWriter auditWriter,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        // Use the Spring-managed ObjectMapper (Spring Boot registers JavaTimeModule on it) rather
        // than the bridge's own no-arg-constructor default, which cannot serialize java.time types
        // (e.g. LocalDateTime datetime fields) and would silently fall back on every such update.
        return new com.itsjool.aperture.runtime.audit.AuditBridge(auditWriter, objectMapper);
    }

    @Bean
    public AuditQueryController auditQueryController(JdbcTemplate jdbcTemplate) {
        return new AuditQueryController(jdbcTemplate);
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        if (metadata.tenancyMode() == com.itsjool.aperture.runtime.config.TenancyMode.SILO) {
            throw new IllegalStateException(
                "TenancyMode SILO is defined in the manifest but not yet implemented at runtime. " +
                "Use POOL for multi-tenant or NONE for single-tenant.");
        }

        EntityDictionary dict = event.getApplicationContext().getBean(EntityDictionary.class);
        bindOneOfMarkers(dict);
        java.util.Map<String, com.yahoo.elide.core.security.checks.Check> checks = event.getApplicationContext().getBeansOfType(com.yahoo.elide.core.security.checks.Check.class);
        for (com.yahoo.elide.core.security.checks.Check check : checks.values()) {
            dict.addSecurityCheck(check.getClass());
        }
    }

    private void bindOneOfMarkers(EntityDictionary dict) {
        if (metadata.oneOfs().isEmpty()) {
            return;
        }

        for (String version : metadata.activeVersions()) {
            for (String oneOfName : metadata.oneOfs().keySet()) {
                String className = "com.itsjool.aperture.generated.v" + version + "." + oneOfName + "V" + version;
                try {
                    Class<?> markerClass = Class.forName(className);
                    ClassType<?> markerType = ClassType.of(markerClass);
                    EntityBinding binding = new EntityBinding(
                            dict.getInjector(),
                            markerType,
                            oneOfName.toLowerCase(Locale.ROOT),
                            version,
                            ignored -> false);
                    dict.bindEntity(binding);
                } catch (ClassNotFoundException ignored) {
                    // Generated projects without a marker for this version have no oneof binding to add.
                }
            }
        }
    }

    @Bean
    public ElideSettingsBuilderCustomizer paginationCustomizer() {
        return builder -> builder.maxPageSize(10000).defaultPageSize(100);
    }

    @Bean
    public com.itsjool.aperture.runtime.config.TenancyMode tenancyMode(com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata metadata) {
        return metadata.tenancyMode();
    }

    @Bean
    public com.itsjool.aperture.starter.filter.OptimisticLockingFilter optimisticLockingFilter(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata apertureRuntimeMetadata) {
        return new com.itsjool.aperture.starter.filter.OptimisticLockingFilter(jdbcTemplate, apertureRuntimeMetadata);
    }

    // ── Active only when simple (JWT) auth is not disabled ───────────────────
    // Set aperture.auth.simple.enabled=false to use a custom CredentialValidator
    // (e.g. Keycloak). When disabled, no JWT secret or AuthController is needed.

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "aperture.auth.simple.enabled", havingValue = "true", matchIfMissing = true)
    public static class SimpleAuthConfiguration {

        @Bean
        public JwtTokenService jwtTokenService(
                @Value("${aperture.auth.jwt.secret}") String secret,
                @Value("${aperture.auth.jwt.issuer}") String issuer,
                @Value("${aperture.auth.jwt.audience}") String audience,
                @Value("${aperture.auth.jwt.access-duration}") Duration accessDuration,
                Clock apertureClock) {
            return new JwtTokenService(secret, issuer, audience, accessDuration, apertureClock);
        }

        @Bean
        public RefreshTokenService refreshTokenService(
                JdbcTemplate jdbcTemplate,
                TransactionTemplate transactionTemplate,
                Clock apertureClock,
                @Value("${aperture.auth.refresh-duration}") Duration refreshDuration,
                SecureRandom apertureSecureRandom) {
            return new RefreshTokenService(jdbcTemplate, transactionTemplate, apertureClock, refreshDuration, apertureSecureRandom);
        }

        @Bean
        public AuthUserService authUserService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
            return new AuthUserService(jdbcTemplate, passwordEncoder);
        }

        @Bean
        @ConditionalOnMissingBean(ServiceAccountIssuer.class)
        public SimpleServiceAccountIssuer serviceAccountIssuer(
                JdbcTemplate jdbcTemplate,
                JwtTokenService jwtService,
                PasswordEncoder passwordEncoder,
                Clock apertureClock,
                SecureRandom apertureSecureRandom,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new SimpleServiceAccountIssuer(jdbcTemplate, jwtService, passwordEncoder, apertureClock, apertureSecureRandom, objectMapper);
        }

        @Bean
        public AuthController authController(
                AuthUserService userService,
                JwtTokenService jwtService,
                RefreshTokenService refreshTokenService,
                ServiceAccountIssuer serviceAccountIssuer,
                com.itsjool.aperture.spi.IdentityAdministrationProvider identityAdministrationProvider) {
            return new AuthController(userService, jwtService, refreshTokenService, serviceAccountIssuer, identityAdministrationProvider);
        }

        @Bean
        public SimpleCredentialValidator credentialValidator(
                JwtTokenService jwtService, ApiKeyService apiKeyService) {
            return new SimpleCredentialValidator(jwtService, apiKeyService);
        }
    }
}
