package com.itsjool.aperture.generation.target;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.McpConfig;
import com.itsjool.aperture.engine.model.McpEntityConfig;
import com.itsjool.aperture.generation.context.StagingGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import com.itsjool.aperture.generation.spi.ApertureGenerationTarget;
import com.itsjool.aperture.mcp.McpToolGenerator;
import com.itsjool.aperture.mcp.spi.McpToolContribution;

import javax.lang.model.SourceVersion;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class McpJavaGenerationTarget implements ApertureGenerationTarget {

    private static final Pattern MCP_PACKAGE_PATTERN =
        Pattern.compile("^\\s*package\\s+com\\.itsjool\\.aperture\\.generated\\.mcp\\s*;", Pattern.MULTILINE);

    private final List<McpToolContribution> toolContributions;

    public McpJavaGenerationTarget() {
        this(List.of());
    }

    public McpJavaGenerationTarget(List<McpToolContribution> toolContributions) {
        this.toolContributions = List.copyOf(toolContributions);
    }

    @Override
    public String name() {
        return "mcp-java";
    }

    @Override
    public boolean enabled(ApertureGenerationRequest request) {
        McpConfig mcpConfig = request.model().frameworkConfig() != null
            ? request.model().frameworkConfig().mcp() : null;
        return mcpConfig != null && mcpConfig.enabled();
    }

    @Override
    public void generate(ApertureGenerationRequest request, ApertureGenerationContext context) throws Exception {
        StagingGenerationContext staging = (StagingGenerationContext) context;
        McpConfig mcpConfig = request.model().frameworkConfig().mcp();
        String latestVersion = request.activeVersions().getLast();
        Map<String, String> resourceTypesByEntity = new HashMap<>();
        for (EntityDef entity : request.model().entities()) {
            resourceTypesByEntity.put(entity.name(), McpToolGenerator.resourceTypeOf(entity.name(), entity.plural()));
        }
        McpToolGenerator mcpGen = new McpToolGenerator();
        // Every class written into com.itsjool.aperture.generated.mcp, mapped to a description of
        // what emitted it. Writes are last-one-wins at the filesystem level, so a collision would
        // otherwise silently drop a tool class.
        Map<String, String> emittedBy = new LinkedHashMap<>();
        for (EntityDef entity : request.model().entities().stream()
                .sorted(Comparator.comparing(EntityDef::name)).toList()) {
            McpEntityConfig entityMcp = entity.mcpConfig();
            if (entityMcp != null && !entityMcp.enabled()) continue;
            String source = mcpGen.generateForEntity(entity, mcpConfig, entityMcp, latestVersion, resourceTypesByEntity);
            claimClassName(emittedBy, StagingGenerationContext.topLevelTypeName(source),
                "the generated tool class for entity '" + entity.name() + "'");
            staging.writeJavaSourceFromString(source);
        }
        writeToolContributions(staging, latestVersion, emittedBy);
    }

    /**
     * Emits each SPI-contributed MCP tool class alongside the generated entity tool classes.
     * These land in the same {@code com.itsjool.aperture.generated.mcp} package, so the
     * reflective {@code ToolCallbackProvider} in {@code ApertureMcpAutoConfiguration} discovers
     * them automatically — no separate registration is required. Class names and package
     * declarations are validated up front so a misconfigured contribution fails loudly rather
     * than producing a broken generated project or an obscure javac error later.
     */
    private void writeToolContributions(StagingGenerationContext staging, String latestVersion,
                                        Map<String, String> emittedBy) throws Exception {
        for (McpToolContribution contribution : toolContributions) {
            String className = contribution.toolClassName();
            if (className == null || !SourceVersion.isIdentifier(className) || SourceVersion.isKeyword(className)) {
                throw new IllegalStateException("MCP tool contribution '" + contribution.id()
                    + "' returned an invalid Java class name: " + className);
            }
            String source = contribution.toolSource(latestVersion);
            if (source == null || !MCP_PACKAGE_PATTERN.matcher(source).find()) {
                throw new IllegalStateException("MCP tool contribution '" + contribution.id()
                    + "' (class " + className + ") must declare package com.itsjool.aperture.generated.mcp");
            }
            // The writer names the file after the type the source actually declares, not after
            // toolClassName(), so the two disagreeing would write a file nobody asked for.
            String declared = StagingGenerationContext.topLevelTypeName(source);
            if (!className.equals(declared)) {
                throw new IllegalStateException("MCP tool contribution '" + contribution.id()
                    + "' declares toolClassName() " + className + " but its source declares top-level type "
                    + declared + "; they must match");
            }
            claimClassName(emittedBy, className, "MCP tool contribution '" + contribution.id() + "'");
            staging.writeJavaSourceFromString(source);
        }
    }

    /** Reserves {@code className} for {@code owner}, rejecting a second claim on the same name. */
    private static void claimClassName(Map<String, String> emittedBy, String className, String owner) {
        String previousOwner = emittedBy.putIfAbsent(className, owner);
        if (previousOwner != null) {
            throw new IllegalStateException("Both " + previousOwner + " and " + owner + " emit class "
                + className + " into com.itsjool.aperture.generated.mcp; the second would overwrite the first");
        }
    }
}
