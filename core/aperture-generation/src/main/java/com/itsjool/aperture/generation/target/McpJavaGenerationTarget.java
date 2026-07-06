package com.itsjool.aperture.generation.target;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.McpConfig;
import com.itsjool.aperture.engine.model.McpEntityConfig;
import com.itsjool.aperture.generation.context.StagingGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import com.itsjool.aperture.generation.spi.ApertureGenerationTarget;
import com.itsjool.aperture.mcp.McpToolGenerator;

import java.util.Comparator;

public class McpJavaGenerationTarget implements ApertureGenerationTarget {

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
        McpToolGenerator mcpGen = new McpToolGenerator();
        for (EntityDef entity : request.model().entities().stream()
                .sorted(Comparator.comparing(EntityDef::name)).toList()) {
            McpEntityConfig entityMcp = entity.mcpConfig();
            if (entityMcp != null && !entityMcp.enabled()) continue;
            String source = mcpGen.generateForEntity(entity, mcpConfig, entityMcp, latestVersion);
            staging.writeJavaSourceFromString(source);
        }
    }
}
