package com.itsjool.aperture.generation.context;

import com.itsjool.aperture.generation.spi.ApertureGenerationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes generated artifacts into temporary staging directories.
 * The caller is responsible for atomically promoting staged output to the final locations.
 */
public class StagingGenerationContext implements ApertureGenerationContext {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package ([a-zA-Z0-9_.]+);");
    // "public " is optional so package-private top-level declarations also match. This relies on
    // find() returning the top-level declaration first (before any nested/inner type); every current
    // generator (CodeGenerator) emits exactly one top-level type per source string, so this holds.
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\b(?:public )?(?:(?:abstract|final|sealed) )*(?:class|interface|record|enum) ([A-Za-z0-9_]+)");

    private final Path sourcesStaging;
    private final Path resourcesStaging;
    private final Path locksStaging;

    public StagingGenerationContext(Path sourcesStaging, Path resourcesStaging, Path locksStaging) {
        this.sourcesStaging = sourcesStaging;
        this.resourcesStaging = resourcesStaging;
        this.locksStaging = locksStaging;
    }

    /** Returns the build directory (parent of generated-resources staging), used for sidecar projects. */
    public Path buildDirectory() {
        return resourcesStaging.getParent();
    }

    @Override
    public void writeJavaSource(String packageName, String className, String source) throws IOException {
        Path packageDir = sourcesStaging;
        for (String segment : packageName.split("\\.")) {
            packageDir = packageDir.resolve(segment);
        }
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve(className + ".java"), source);
    }

    public void writeJavaSourceFromString(String source) throws IOException {
        String pkg = extractPackage(source);
        if (pkg == null) {
            String preview = source.length() > 80 ? source.substring(0, 80) : source;
            throw new IllegalArgumentException(
                "Generated Java source has no package declaration; cannot determine where to write it. "
                    + "Source starts with: " + preview);
        }
        String cls = extractClassName(source);
        if (cls != null) {
            writeJavaSource(pkg, cls, source);
        } else {
            // No class/interface/record/enum declaration means this is a package-info source
            // (e.g. CodeGenerator.generatePackageInfo(), which carries the @ApiVersion annotation).
            writePackageInfo(pkg, source);
        }
    }

    private void writePackageInfo(String packageName, String source) throws IOException {
        Path packageDir = sourcesStaging;
        for (String segment : packageName.split("\\.")) {
            packageDir = packageDir.resolve(segment);
        }
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("package-info.java"), source);
    }

    @Override
    public void writeResource(Path relativePath, String content) throws IOException {
        Path target = resourcesStaging.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    @Override
    public void writeLock(String fileName, String content) throws IOException {
        Files.createDirectories(locksStaging);
        Files.writeString(locksStaging.resolve(fileName), content);
    }

    @Override
    public Path allocateTargetDirectory(String classifier) throws IOException {
        // Resolve sidecar project directories relative to the build root (e.g., target/),
        // which is the parent of the generated-resources directory — not generated-sources.
        Path dir = buildDirectory().resolve(classifier);
        Files.createDirectories(dir);
        return dir;
    }

    public Path sourcesStaging() { return sourcesStaging; }
    public Path resourcesStaging() { return resourcesStaging; }
    public Path locksStaging() { return locksStaging; }

    private String extractPackage(String source) {
        Matcher m = PACKAGE_PATTERN.matcher(source);
        return m.find() ? m.group(1) : null;
    }

    private String extractClassName(String source) {
        Matcher m = CLASS_PATTERN.matcher(source);
        return m.find() ? m.group(1) : null;
    }
}
