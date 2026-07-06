package com.itsjool.aperture.generation.spi;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.diff.DiffResult;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;

import java.nio.file.Path;
import java.util.List;

public record ApertureGenerationRequest(
    ResolvedDomainModel model,
    ResolvedDomainModel previousModel,
    DiffResult diff,
    List<String> activeVersions,
    TenancyMode tenancyMode,
    Path projectBaseDirectory
) {}
