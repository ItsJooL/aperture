package com.itsjool.aperture.plugin;

import com.itsjool.aperture.cli.spi.CliAuthExtension;
import com.itsjool.aperture.cli.spi.CliCommandContribution;
import com.itsjool.aperture.cli.generator.CliGenerationTarget;
import com.itsjool.aperture.generation.GenerationOptions;
import com.itsjool.aperture.generation.GeneratorOrchestrator;
import com.itsjool.aperture.generation.spi.ApertureGenerationTarget;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class ApertureGenerateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}/manifests")
    private File manifestDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/aperture")
    private File outputDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-resources")
    private File generatedResourcesDirectory;

    @Parameter(defaultValue = "${project.basedir}/.aperture.lock")
    private File lockDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** {@code <cli><enabled>true</enabled></cli>} — enables CLI project generation. */
    @Parameter
    private CliConfig cli;

    public void setProject(MavenProject project) { this.project = project; }
    public void setManifestDirectory(File manifestDirectory) { this.manifestDirectory = manifestDirectory; }
    public void setOutputDirectory(File outputDirectory) { this.outputDirectory = outputDirectory; }
    public void setGeneratedResourcesDirectory(File d) { this.generatedResourcesDirectory = d; }
    public void setLockDirectory(File lockDirectory) { this.lockDirectory = lockDirectory; }
    public void setCli(CliConfig cli) { this.cli = cli; }

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Aperture API Server: Generating sources and changesets");
        GenerationOptions options = new GenerationOptions(
            manifestDirectory,
            outputDirectory,
            generatedResourcesDirectory,
            lockDirectory,
            project != null && project.getBasedir() != null ? project.getBasedir() : manifestDirectory.getParentFile()
        );
        try {
            List<ApertureGenerationTarget> extensions = buildExtensionTargets();
            new GeneratorOrchestrator().generate(options, getLog()::info, extensions);
        } catch (IllegalStateException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (Exception e) {
            throw new MojoExecutionException("Generation failed", e);
        }
        registerBuildOutput();
    }

    private List<ApertureGenerationTarget> buildExtensionTargets() {
        List<ApertureGenerationTarget> targets = new ArrayList<>();
        boolean cliEnabled = cli != null && cli.isEnabled();
        ExtensionInstances instances = instantiateExtensions();
        targets.add(new CliGenerationTarget(cliEnabled, instances.authExtensions(), instances.commandContributions()));
        return targets;
    }

    /**
     * Instantiates each configured {@code <cli><extensions>} class exactly once and sorts the
     * result by which SPI interface(s) it implements. A class may implement {@link CliAuthExtension},
     * {@link CliCommandContribution}, or both; a class implementing neither is a misconfiguration
     * and fails loudly rather than being silently ignored.
     */
    private ExtensionInstances instantiateExtensions() {
        List<CliAuthExtension> authExtensions = new ArrayList<>();
        List<CliCommandContribution> commandContributions = new ArrayList<>();
        if (cli == null || cli.getExtensions() == null) {
            return new ExtensionInstances(authExtensions, commandContributions);
        }
        for (String className : cli.getExtensions()) {
            if (className == null || className.isBlank()) {
                continue;
            }
            Object extension;
            try {
                extension = Class.forName(className).getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to instantiate CLI extension " + className, e);
            }
            boolean recognized = false;
            if (extension instanceof CliAuthExtension authExtension) {
                authExtensions.add(authExtension);
                recognized = true;
            }
            if (extension instanceof CliCommandContribution contribution) {
                commandContributions.add(contribution);
                recognized = true;
            }
            if (!recognized) {
                throw new IllegalStateException(className
                    + " does not implement CliAuthExtension or CliCommandContribution");
            }
        }
        if (authExtensions.size() > 1) {
            throw new IllegalStateException("Only one CliAuthExtension may be configured, but found "
                + authExtensions.size() + ": " + authExtensions.stream().map(CliAuthExtension::id).toList());
        }
        return new ExtensionInstances(authExtensions, commandContributions);
    }

    private record ExtensionInstances(
        List<CliAuthExtension> authExtensions,
        List<CliCommandContribution> commandContributions) {
    }

    private void registerBuildOutput() {
        if (project == null) return;
        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
        Resource resource = new Resource();
        resource.setDirectory(generatedResourcesDirectory.getAbsolutePath());
        project.addResource(resource);
    }

    public static class CliConfig {
        private boolean enabled;
        private List<String> extensions = new ArrayList<>();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getExtensions() { return extensions; }
        public void setExtensions(List<String> extensions) { this.extensions = extensions; }
    }
}
