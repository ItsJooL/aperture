package com.itsjool.aperture.cli.generator;

import com.itsjool.aperture.cli.spi.CliAuthExtension;
import com.itsjool.aperture.cli.spi.CliCommandContribution;
import com.itsjool.aperture.generation.spi.ApertureGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import com.itsjool.aperture.generation.spi.ApertureGenerationTarget;

import java.nio.file.Path;
import java.util.List;

/**
 * Generates a standalone Maven CLI project under {@code target/generated-cli/aperture-cli/}.
 * Disabled by default — opt-in via {@code <cli><enabled>true</enabled></cli>} in the plugin config.
 * Integration: bind {@code exec-maven-plugin} in the consumer POM to {@code test-compile} phase.
 */
public class CliGenerationTarget implements ApertureGenerationTarget {

    private final boolean enabled;
    private final List<CliAuthExtension> authExtensions;
    private final List<CliCommandContribution> commandContributions;

    public CliGenerationTarget(boolean enabled) {
        this(enabled, List.of(), List.of());
    }

    public CliGenerationTarget(boolean enabled, List<CliAuthExtension> authExtensions) {
        this(enabled, authExtensions, List.of());
    }

    public CliGenerationTarget(
            boolean enabled, List<CliAuthExtension> authExtensions, List<CliCommandContribution> commandContributions) {
        this.enabled = enabled;
        this.authExtensions = List.copyOf(authExtensions);
        this.commandContributions = List.copyOf(commandContributions);
    }

    @Override
    public String name() {
        return "cli-generator";
    }

    @Override
    public boolean enabled(ApertureGenerationRequest request) {
        return enabled;
    }

    @Override
    public void generate(ApertureGenerationRequest request, ApertureGenerationContext context) throws Exception {
        Path cliProjectRoot = context.allocateTargetDirectory("generated-cli/aperture-cli");

        CliProjectGenerator generator = new CliProjectGenerator(request, cliProjectRoot, authExtensions, commandContributions);
        generator.generate();
    }
}
