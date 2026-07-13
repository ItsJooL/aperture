package com.itsjool.aperture.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.elide.spring.controllers.JsonApiController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The generated MCP tool classes take a single {@link McpRequestAdapter} constructor argument, so
 * the context must contain exactly one. Applications scan {@code com.itsjool.aperture}, which
 * covers this very package — these tests pin that scanning cannot smuggle in a second adapter
 * behind the auto-configuration's back, nor register MCP beans when MCP is switched off.
 */
class ApertureMcpAutoConfigurationTest {

    /** Mirrors the demo application's {@code @SpringBootApplication(scanBasePackages = ...)}. */
    private static final String SCANNED_PACKAGE = "com.itsjool.aperture.mcp";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ApertureMcpAutoConfiguration.class))
        .withBean(JsonApiController.class, () -> mock(JsonApiController.class))
        .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void registersTheDefaultAdapterWhenMcpIsEnabled() {
        runner.withPropertyValues("aperture.mcp.enabled=true").run(context -> {
            assertThat(context.getBeanNamesForType(McpRequestAdapter.class)).hasSize(1);
            assertThat(context.getBean(McpRequestAdapter.class)).isInstanceOf(McpElideAdapter.class);
        });
    }

    @Test
    void defaultAdapterBacksOffWhenAConsumerProvidesTheirOwn() {
        McpRequestAdapter custom = mock(McpRequestAdapter.class);

        runner.withPropertyValues("aperture.mcp.enabled=true")
            .withBean("customAdapter", McpRequestAdapter.class, () -> custom)
            .run(context -> {
                assertThat(context.getBeanNamesForType(McpRequestAdapter.class))
                    .as("a consumer adapter must not coexist with the default one")
                    .hasSize(1);
                assertThat(context.getBean(McpRequestAdapter.class)).isSameAs(custom);
            });
    }

    @Test
    void registersNoMcpBeansWhenMcpIsDisabled() {
        runner.withPropertyValues("aperture.mcp.enabled=false").run(context -> {
            assertThat(context.getBeanNamesForType(McpRequestAdapter.class)).isEmpty();
            assertThat(context.getBeanNamesForType(McpSanitizationFilter.class)).isEmpty();
            assertThat(context.getBeanNamesForType(McpToolListFilter.class)).isEmpty();
        });
    }

    @Test
    void registersThePrincipalScopedToolListFilterByDefault() {
        runner.withPropertyValues("aperture.mcp.enabled=true").run(context ->
            assertThat(context.getBeanNamesForType(McpToolListFilter.class)).hasSize(1));
    }

    @Test
    void staticToolListScope_doesNotRegisterTheFilter() {
        runner.withPropertyValues("aperture.mcp.enabled=true", "aperture.mcp.tool-list-scope=STATIC")
            .run(context -> assertThat(context.getBeanNamesForType(McpToolListFilter.class)).isEmpty());
    }

    @Test
    void componentScanningThePackageRegistersNoMcpBeansWhenMcpIsDisabled() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan(SCANNED_PACKAGE);
            context.refresh();

            assertThat(context.getBeanNamesForType(McpRequestAdapter.class))
                .as("component scanning must not register an adapter outside the enable flag")
                .isEmpty();
            assertThat(context.getBeanNamesForType(McpSanitizationFilter.class))
                .as("component scanning must not install the MCP filter outside the enable flag")
                .isEmpty();
        }
    }

    @Test
    void componentScanningThePackageLeavesAConsumerAdapterUnambiguous() {
        McpRequestAdapter custom = mock(McpRequestAdapter.class);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("aperture.mcp.enabled=true").applyTo(context);
            // Lambdas, not method references: registerBean(Class, Supplier) and
            // registerBean(Class, BeanDefinitionCustomizer...) are ambiguous for a bare `X::new`.
            context.registerBean(JsonApiController.class, () -> mock(JsonApiController.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean("customAdapter", McpRequestAdapter.class, () -> custom);
            context.scan(SCANNED_PACKAGE);
            context.refresh();

            Map<String, McpRequestAdapter> adapters = context.getBeansOfType(McpRequestAdapter.class);
            assertThat(adapters)
                .as("expected exactly one adapter, found: " + adapters.keySet())
                .hasSize(1);
            assertThat(adapters.values().iterator().next()).isSameAs(custom);
        }
    }
}
