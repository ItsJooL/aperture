package com.itsjool.aperture.generation.target;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.AbacPolicyDef;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.ApertureConfigDef;
import com.itsjool.aperture.engine.model.McpConfig;
import com.itsjool.aperture.engine.model.McpEntityConfig;
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
    void entityWithEmptyEffectiveToolSet_emitsNoClassFile() throws Exception {
        // An entity with no permissions, policies, or publicOperations derives zero MCP tools
        // (plan 016). The target must skip it entirely rather than emit a tool class with no
        // @Tool methods.
        FieldDef name = new FieldDef("String", true, false, false, false,
            null, null, null, null, null, null, null);
        EntityDef noAccessRules = new EntityDef("Ghost", "ghosts", null, null,
            false, false, false, Map.of("name", name),
            Map.of(), Map.of(), List.of(), Map.of(), Map.of());
        ApertureConfigDef apertureConfig = new ApertureConfigDef(
            List.of(), TenancyMode.POOL, new McpConfig(true, "http", List.of("list", "get")), null);
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(noAccessRules), List.of(), apertureConfig, List.of(), List.of(), List.of(), List.of());
        ApertureGenerationRequest ghostRequest = new ApertureGenerationRequest(model, null, null, List.of("1"), TenancyMode.POOL, null);

        new McpJavaGenerationTarget().generate(ghostRequest, staging);

        assertTrue(Files.notExists(mcpSource("GhostV1McpTools")),
            "entity deriving zero MCP tools must not get a generated tool class");
    }

    @Test
    void entityExplicitlyDisabled_emitsNoClassFileEvenWithFullPermissions() throws Exception {
        // enabled: false always excludes the entity, regardless of what its own permissions would
        // otherwise derive. Distinguishes this from the "absent enabled key" (inherit) case.
        FieldDef name = new FieldDef("String", true, false, false, false,
            null, null, null, null, null, null, null);
        McpEntityConfig disabled = new McpEntityConfig(Boolean.FALSE, null);
        EntityDef fullyPermittedButDisabled = new EntityDef("Secret", "secrets", null, disabled,
            false, false, false, Map.of("name", name),
            Map.of("Admin", List.of("read", "create", "update", "delete")), Map.of(), List.of(), Map.of(), Map.of());
        ApertureConfigDef apertureConfig = new ApertureConfigDef(
            List.of(), TenancyMode.POOL, new McpConfig(true, "http", null), null);
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(fullyPermittedButDisabled), List.of(), apertureConfig, List.of(), List.of(), List.of(), List.of());
        ApertureGenerationRequest secretRequest = new ApertureGenerationRequest(model, null, null, List.of("1"), TenancyMode.POOL, null);

        new McpJavaGenerationTarget().generate(secretRequest, staging);

        assertTrue(Files.notExists(mcpSource("SecretV1McpTools")),
            "explicitly disabled entity must not get a generated tool class");
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

    @Test
    void rejectsContributionCollidingWithTheGeneratedToolRegistry() {
        // The registry (plan 016 phase 2) lands in the same package as every generated tool
        // class, so its name must be reserved through the same claimClassName collision check.
        McpToolContribution squatter = contribution("registry-squatter", "McpToolRegistry", toolSource("McpToolRegistry"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new McpJavaGenerationTarget(List.of(squatter)).generate(request, staging));

        assertTrue(e.getMessage().contains("McpToolRegistry"), e.getMessage());
        assertTrue(e.getMessage().contains("the generated MCP tool access registry"), e.getMessage());
    }

    @Test
    void registryIsAlwaysEmittedEvenWithNoEntityTools() throws Exception {
        // An app whose only MCP tools come from McpToolContribution still gets a (possibly empty)
        // registry, so the McpToolRegistry class name is unconditionally reserved rather than only
        // sometimes — a contribution squatting on it must always be rejected.
        FieldDef name = new FieldDef("String", true, false, false, false,
            null, null, null, null, null, null, null);
        EntityDef noAccessRules = new EntityDef("Ghost", "ghosts", null, null,
            false, false, false, Map.of("name", name),
            Map.of(), Map.of(), List.of(), Map.of(), Map.of());
        ApertureConfigDef apertureConfig = new ApertureConfigDef(
            List.of(), TenancyMode.POOL, new McpConfig(true, "http", null), null);
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(noAccessRules), List.of(), apertureConfig, List.of(), List.of(), List.of(), List.of());
        ApertureGenerationRequest ghostRequest = new ApertureGenerationRequest(model, null, null, List.of("1"), TenancyMode.POOL, null);

        new McpJavaGenerationTarget().generate(ghostRequest, staging);

        assertTrue(Files.exists(mcpSource("McpToolRegistry")), "registry must be emitted even with zero entity tools");
        assertTrue(Files.readString(mcpSource("McpToolRegistry")).contains("unmodifiableMap(m)"),
            "empty registry must still declare an (empty) TOOLS map");
    }

    @Test
    void registryCapturesRolesPolicesAndPublicOperationsPerTool() throws Exception {
        // Project: Admin gets full CRUD, Assistant/ReadOnly only read, and a named policy grants
        // update only to callers for which a #user-only expression holds.
        FieldDef nameField = new FieldDef("String", true, false, false, false,
            null, null, null, null, null, null, null);
        EntityDef project = new EntityDef("Project", "projects", "A project", null,
            false, false, false, Map.of("name", nameField),
            Map.of("Admin", List.of("create", "read", "update"), "ReadOnly", List.of("read")),
            Map.of("FinanceOnly", List.of("update")),
            List.of("delete"), Map.of(), Map.of());
        ApertureConfigDef apertureConfig = new ApertureConfigDef(
            List.of(), TenancyMode.POOL, new McpConfig(true, "http", null), null);
        List<AbacPolicyDef> abacPolicies = List.of(
            new AbacPolicyDef("FinanceOnly", "#user.securityAttributes['department'] == 'finance'"));
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(project), List.of(), apertureConfig, List.of(), abacPolicies, List.of(), List.of());
        ApertureGenerationRequest projectRequest = new ApertureGenerationRequest(model, null, null, List.of("1"), TenancyMode.POOL, null);

        new McpJavaGenerationTarget().generate(projectRequest, staging);

        String registrySource = Files.readString(mcpSource("McpToolRegistry"));
        // list_projects/get_project (read): roles Admin + ReadOnly, no policy.
        assertTrue(registrySource.contains("\"list_projects\""), registrySource);
        assertTrue(registrySource.contains("\"Admin\""), registrySource);
        assertTrue(registrySource.contains("\"ReadOnly\""), registrySource);
        // update_project: policy expression is #user-only, so it must be captured verbatim.
        assertTrue(registrySource.contains("#user.securityAttributes['department'] == 'finance'"), registrySource);
        // delete_project: publicOperation must be true (delete is in publicOperations).
        assertTrue(registrySource.contains("\"delete_project\""), registrySource);
        assertTrue(registrySource.contains("new McpToolAccess(\"Project\", \"delete\", true"), registrySource);
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
        // "read" must be reachable so derived(entity) includes list/get — plan 016's MCP tool
        // surface is derived from permissions/policies/publicOperations, not just the framework
        // ceiling, so an entity with no access rules at all now derives zero MCP tools.
        EntityDef project = new EntityDef("Project", "projects", "A project", null,
            false, false, false, Map.of("name", name),
            Map.of("Admin", List.of("read")), Map.of(), List.of(), Map.of(), Map.of());
        ApertureConfigDef apertureConfig = new ApertureConfigDef(
            List.of(), TenancyMode.POOL, new McpConfig(true, "http", List.of("list", "get")), null);
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(project), List.of(), apertureConfig, List.of(), List.of(), List.of(), List.of());
        return new ApertureGenerationRequest(model, null, null, List.of("1"), TenancyMode.POOL, null);
    }
}
