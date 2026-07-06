package com.itsjool.aperture.generation.spi;

import java.io.IOException;
import java.nio.file.Path;

public interface ApertureGenerationContext {
    void writeJavaSource(String packageName, String className, String source) throws IOException;
    void writeResource(Path relativePath, String content) throws IOException;
    void writeLock(String fileName, String content) throws IOException;
    Path allocateTargetDirectory(String classifier) throws IOException;
}
