package com.itsjool.aperture.cli.generator;

import com.itsjool.aperture.cli.spi.CliCommandContribution;
import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.diff.ChangeType;
import com.itsjool.aperture.engine.diff.DiffResult;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

final class GeneratedCliHarness {
    private final Path projectRoot;
    private final Path classesDir;
    private final Path home;

    private GeneratedCliHarness(Path projectRoot, Path classesDir, Path home) {
        this.projectRoot = projectRoot;
        this.classesDir = classesDir;
        this.home = home;
    }

    static GeneratedCliHarness generateAndCompile(ResolvedDomainModel model, Path workDir) throws Exception {
        return generateAndCompile(model, workDir, List.of());
    }

    static GeneratedCliHarness generateAndCompile(
            ResolvedDomainModel model, Path workDir, List<CliCommandContribution> contributions) throws Exception {
        Path projectRoot = workDir.resolve("aperture-cli");
        CliProjectGenerator generator =
            new CliProjectGenerator(request(model, workDir), projectRoot, List.of(), contributions);
        generator.generate();

        List<Path> sources;
        try (var stream = Files.walk(projectRoot.resolve("src/main/java"))) {
            sources = stream
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
        }

        Path classesDir = Files.createDirectories(workDir.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new AssertionError("JDK compiler not available");
        }

        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(classesDir.toString());
        args.add("-cp");
        args.add(System.getProperty("java.class.path"));
        sources.stream().map(Path::toString).forEach(args::add);

        int rc = javac.run(null, null, null, args.toArray(String[]::new));
        if (rc != 0) {
            throw new AssertionError("generated CLI failed to compile");
        }

        return new GeneratedCliHarness(projectRoot, classesDir, Files.createDirectories(workDir.resolve("home")));
    }

    CliResult run(String... args) throws Exception {
        return run(Map.of(), args);
    }

    CliResult run(Map<String, String> extraEnv, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Duser.home=" + home,
            "-cp", classesDir + File.pathSeparator + System.getProperty("java.class.path"),
            "com.itsjool.aperture.cli.ApertureCli"));
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.remove("XDG_CONFIG_HOME");
        env.keySet().removeIf(key -> key.startsWith("APERTURE_"));
        env.putAll(extraEnv);

        Process process = pb.start();
        process.getOutputStream().close();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("CLI timed out");
        }
        return new CliResult(process.exitValue(), stdout, stderr);
    }

    Path homeDir() {
        return home;
    }

    String generatedSource(String relativePath) throws Exception {
        return Files.readString(projectRoot.resolve("src/main/java/com/itsjool/aperture/cli").resolve(relativePath));
    }

    private static ApertureGenerationRequest request(ResolvedDomainModel model, Path workDir) {
        ResolvedDomainModel previousModel = new ResolvedDomainModel(List.of());
        DiffResult diff = new DiffResult(
            List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(), ChangeType.SAFE, Map.of());
        return new ApertureGenerationRequest(
            model, previousModel, diff, List.of("1"), TenancyMode.POOL, workDir);
    }

    record CliResult(int exitCode, String stdout, String stderr) {}
}
