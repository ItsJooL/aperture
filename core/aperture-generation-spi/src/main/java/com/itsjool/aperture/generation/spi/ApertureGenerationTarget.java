package com.itsjool.aperture.generation.spi;

public interface ApertureGenerationTarget {
    String name();
    boolean enabled(ApertureGenerationRequest request);
    void generate(ApertureGenerationRequest request, ApertureGenerationContext context) throws Exception;
}
