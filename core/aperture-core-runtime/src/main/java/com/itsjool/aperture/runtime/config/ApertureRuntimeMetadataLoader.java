package com.itsjool.aperture.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

public final class ApertureRuntimeMetadataLoader {
    public static final String RESOURCE_PATH = "aperture/aperture-runtime-metadata.json";

    private final ObjectMapper objectMapper;

    public ApertureRuntimeMetadataLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ApertureRuntimeMetadata load() {
        return load(new ClassPathResource(RESOURCE_PATH));
    }

    public ApertureRuntimeMetadata load(Resource resource) {
        if (!resource.exists()) {
            throw new IllegalStateException("Aperture runtime metadata resource is missing: "
                    + resource.getDescription());
        }
        try (var input = resource.getInputStream()) {
            return objectMapper.readValue(input, ApertureRuntimeMetadata.class);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid Aperture runtime metadata in "
                    + resource.getDescription() + ": " + exception.getMessage(), exception);
        }
    }
}
