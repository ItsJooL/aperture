package com.itsjool.aperture.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.apertureautoconfigure.mcp.ApertureMcpAutoConfiguration;
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
 * the context must contain exactly one. {@link ApertureMcpAutoConfiguration} itself now lives in
 * {@code com.itsjool.apertureautoconfigure.mcp} (plan 030), outside any consumer's
 * {@code scanBasePackages = {"com.itsjool.aperture"}}, reachable only via {@code
 * AutoConfiguration.imports}. This package still holds the sibling helper classes
 * ({@link McpElideAdapter}, {@link McpSanitizationFilter}, {@link McpToolListFilter}) which are
 * deliberately not {@code @Component}-annotated — these tests pin that scanning this package
 * cannot smuggle in a second adapter, nor register MCP beans when MCP is switched off.
 */
class ApertureMcpAutoConfigurationTest {

    /** The package that must never accidentally discover MCP beans via component scan. */
    private static final String SCANNED_PACKAGE = "com.itsjool.aperture.mcp";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ApertureMcpAutoConfiguration.class))
        .withBean(JsonApiController.class, () -> mock(JsonApiController.class))
        .withBean(ObjectMapper.class, ObjectMapper::new);

    /**
     * Plan 030 done criterion: proves the whole family of MCP beans (adapter, tool callback
     * provider, sanitization filter, tools/list filter) comes up together purely through {@link
     * AutoConfigurations#of} — the runner above has no component scan configured at all.
     */
    @Test
    void loadsViaAutoConfigurationImportsAloneWithNoComponentScan() {
        runner.withPropertyValues("aperture.mcp.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(McpRequestAdapter.class);
            assertThat(context).hasSingleBean(
                org.springframework.ai.tool.ToolCallbackProvider.class);
            assertThat(context).hasSingleBean(McpSanitizationFilter.class);
            assertThat(context).hasSingleBean(McpToolListFilter.class);
        });
    }

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
