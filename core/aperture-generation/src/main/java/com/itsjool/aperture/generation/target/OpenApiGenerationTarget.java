package com.itsjool.aperture.generation.target;

import com.itsjool.aperture.engine.oas.OasGenerator;
import com.itsjool.aperture.generation.spi.ApertureGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import com.itsjool.aperture.generation.spi.ApertureGenerationTarget;

import java.nio.file.Path;

public class OpenApiGenerationTarget implements ApertureGenerationTarget {

    @Override
    public String name() {
        return "openapi";
    }

    @Override
    public boolean enabled(ApertureGenerationRequest request) {
        return true;
    }

    @Override
    public void generate(ApertureGenerationRequest request, ApertureGenerationContext context) throws Exception {
        String yaml = new OasGenerator().generate(request.model(), request.tenancyMode(), request.activeVersions());
        context.writeResource(Path.of("aperture-openapi.yaml"), yaml);
    }
}
