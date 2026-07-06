package com.itsjool.aperture.cli.spi;

/**
 * Extension point for additional top-level commands on the generated CLI.
 *
 * <p>The generated CLI is a standalone Maven project compiled (and, for native binaries,
 * GraalVM {@code native-image}-processed) long after this plugin has run — there is no JVM
 * running at that point into which a runtime plugin object could be loaded, and native-image
 * cannot discover or invoke arbitrary classes reflectively. So instead of contributing a live
 * Picocli command object, implementations act as <b>source emitters</b>: {@link #commandSource}
 * returns the complete Java source of a Picocli {@code @Command} class, which the CLI generator
 * writes to the generated project's {@code com.itsjool.aperture.cli.cmd} package and compiles
 * alongside the entity and auth commands. The result is an ordinary generated-project class —
 * fully native-image friendly, with no reflection or classloading trick involved.
 *
 * <p>Implementations are configured explicitly via the Maven plugin's {@code <cli><extensions>}
 * list, exactly like {@link CliAuthExtension} — ServiceLoader-based discovery is not used, and
 * the implementation class must be on the Maven plugin's classpath (e.g. via a
 * {@code <dependency>} on the {@code aperture-maven-plugin} {@code <plugin>} element). A single
 * class may implement both {@link CliAuthExtension} and {@code CliCommandContribution} if it
 * wants to contribute both auth commands and other top-level commands.
 */
public interface CliCommandContribution {
    /**
     * Unique identifier for this contribution (e.g. "simple-status"), used in error messages.
     */
    String id();

    /**
     * Simple (unqualified) class name of the contributed command, e.g. "StatusCommand".
     * The generator writes the source returned by {@link #commandSource} to
     * {@code <commandClassName>.java} in package {@code com.itsjool.aperture.cli.cmd}.
     */
    String commandClassName();

    /**
     * Complete Java source for the contributed command class. Must declare package
     * {@code com.itsjool.aperture.cli.cmd} and define a Picocli {@code @Command} class named
     * {@link #commandClassName()} that implements {@code Runnable}.
     *
     * @param binaryName the CLI's configured binary name (e.g. "aperture"), for use in help
     *                    text or usage hints, matching the convention used by the generator's
     *                    own built-in commands.
     */
    String commandSource(String binaryName);
}
