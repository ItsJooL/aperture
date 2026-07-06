package com.itsjool.aperture.generation;

import java.io.File;

public record GenerationOptions(
    File manifestDirectory,
    File outputDirectory,
    File generatedResourcesDirectory,
    File lockDirectory,
    File projectBaseDirectory
) {}
