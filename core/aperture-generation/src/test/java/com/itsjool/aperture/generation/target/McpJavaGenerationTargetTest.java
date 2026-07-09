package com.itsjool.aperture.generation.target;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.FrameworkConfigDef;
import com.itsjool.aperture.engine.model.McpConfig;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import com.itsjool.aperture.generation.context.StagingGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import com.itsjool.aperture.mcp.spi.McpToolContribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the guardrails around SPI-contributed MCP tool classes. The staging writer names each
 * file after the type its source declares, so a contribution whose {@code toolClassName()} lies
 * about its source — or collides with a generated entity tool class — would otherwise silently
 * write the wrong file or clobber a real one.
 */
class McpJavaGenerationTargetTest {

    /** The class name {@code McpToolGenerator} derives for entity Project at API version 1. */
    private static final String PROJECT_TOOLS = "ProjectV1McpTools";

    @TempDir
    Path tempDir;

    private StagingGenerationContext staging;
    private ApertureGenerationRequest request;

    @BeforeEach
    void setUp() throws Exception {
        Path sources = Files.createDirectories(tempDir.resolve("generated-sources"));
        Path resources = Files.createDirectories(tempDir.resolve("generated-resources"));
        Path locks = Files.createDirectories(tempDir.resolve("locks"));
        staging = new StagingGenerationContext(sources, resources, locks);
        request = requestWithProjectEntity();
    }

    @Test
    void writesEntityToolsAndValidContributionSideBySide() throws Exception {
        McpToolContribution billing = contribution("billing-tools", "BillingMcpTools",
            toolSource("BillingMcpTools"));

        new McpJavaGenerationTarget(List.of(billing)).generate(request, staging);

        assertTrue(Files.exists(mcpSource(PROJECT_TOOLS)), "entity tool class should be written");
        assertTrue(Files.exists(mcpSource("BillingMcpTools")), "contributed tool class should be written");
    }

    @Test
    void rejectsContributionWhoseSourceDeclaresADifferentClass() {
        McpToolContribution liar = contribution("liar", "Foo", toolSource("Bar"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new McpJavaGenerationTarget(List.of(liar)).generate(request, staging));

        assertTrue(e.getMessage().contains("'liar'"), e.getMessage());
        assertTrue(e.getMessage().contains("Foo"), e.getMessage());
        assertTrue(e.getMessage().contains("Bar"), e.getMessage());
        assertTrue(Files.notExists(mcpSource("Bar")), "mismatched source must not be written");
    }

    @Test
    void rejectsContributionCollidingWithAGeneratedEntityToolClass() throws Exception {
        McpToolContribution squatter = contribution("squatter", PROJECT_TOOLS, toolSource(PROJECT_TOOLS));

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new McpJavaGenerationTarget(List.of(squatter)).generate(request, staging));

        assertTrue(e.getMessage().contains(PROJECT_TOOLS), e.getMessage());
        assertTrue(e.getMessage().contains("entity 'Project'"), e.getMessage());
        // The real entity tool class was written before the contribution was rejected; it must
        // survive intact rather than be overwritten by the squatter's source.
        assertTrue(Files.readString(mcpSource(PROJECT_TOOLS)).contains("list_projects"),
            "generated entity tool class must not be overwritten");
    }

    @Test
    void rejectsTwoContributionsEmittingTheSameClass() {
        McpToolContribution first = contribution("first", "SharedMcpTools", toolSource("SharedMcpTools"));
        McpToolContribution second = contribution("second", "SharedMcpTools", toolSource("SharedMcpTools"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new McpJavaGenerationTarget(List.of(first, second)).generate(request, staging));

        assertTrue(e.getMessage().contains("'first'"), e.getMessage());
        assertTrue(e.getMessage().contains("'second'"), e.getMessage());
    }

    @Test
    void rejectsContributionOutsideTheGeneratedMcpPackage() {
        String source = "package com.acme.tools;\npublic class StrayMcpTools {}\n";
        McpToolContribution stray = contribution("stray", "StrayMcpTools", source);

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new McpJavaGenerationTarget(List.of(stray)).generate(request, staging));

        assertTrue(e.getMessage().contains("com.itsjool.aperture.generated.mcp"), e.getMessage());
    }

    @Test
    void rejectsContributionWithAnInvalidClassName() {
        McpToolContribution bad = contribution("bad", "not a class", toolSource("Whatever"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new McpJavaGenerationTarget(List.of(bad)).generate(request, staging));

        assertTrue(e.getMessage().contains("invalid Java class name"), e.getMessage());
    }

    @Test
    void topLevelTypeNameMatchesWhatTheWriterUsesForTheFileName() throws Exception {
        String source = toolSource("SomeMcpTools");
        assertEquals("SomeMcpTools", StagingGenerationContext.topLevelTypeName(source));

        staging.writeJavaSourceFromString(source);
        assertTrue(Files.exists(mcpSource("SomeMcpTools")));
    }

    private Path mcpSource(String className) {
        return staging.sourcesStaging()
            .resolve("com/itsjool/aperture/generated/mcp/" + className + ".java");
    }

    private static String toolSource(String className) {
        return "package com.itsjool.aperture.generated.mcp;\n\npublic class " + className + " {}\n";
    }

    private static McpToolContribution contribution(String id, String toolClassName, String source) {
        return new McpToolContribution() {
            @Override public String id() { return id; }
            @Override public String toolClassName() { return toolClassName; }
            @Override public String toolSource(String latestApiVersion) { return source; }
        };
    }

    private static ApertureGenerationRequest requestWithProjectEntity() {
        FieldDef name = new FieldDef("String", true, false, false, false,
            null, null, null, null, null, null, null);
        EntityDef project = new EntityDef("Project", "projects", "A project", null,
            false, false, false, Map.of("name", name),
            Map.of(), Map.of(), List.of(), Map.of(), Map.of());
        FrameworkConfigDef frameworkConfig = new FrameworkConfigDef(
            List.of(), TenancyMode.POOL, new McpConfig(true, "http", List.of("list", "get")), null);
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(project), List.of(), frameworkConfig, List.of(), List.of(), List.of(), List.of());
        return new ApertureGenerationRequest(model, null, null, List.of("1"), TenancyMode.POOL, null);
    }
}
