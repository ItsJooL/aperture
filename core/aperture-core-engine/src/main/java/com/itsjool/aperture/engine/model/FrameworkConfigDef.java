package com.itsjool.aperture.engine.model;

import com.itsjool.aperture.engine.config.TenancyMode;
import java.util.List;

public record FrameworkConfigDef(List<String> defaultRoles, TenancyMode tenancyMode, McpConfig mcp, CliConfig cli) {
    public FrameworkConfigDef {
        if (tenancyMode == null) tenancyMode = TenancyMode.POOL;
        if (cli == null) cli = new CliConfig(null);
    }
}
