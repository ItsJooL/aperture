package com.itsjool.aperture.mcp.spi;

/**
 * Extension point for additional MCP tool classes emitted alongside the generator's own
 * per-entity tool classes.
 *
 * <p>Generated MCP tool classes are ordinary Spring {@code @Component} beans, discovered at
 * runtime by the reflective {@code ToolCallbackProvider} that scans for {@code @Component}
 * beans with {@code @Tool}-annotated methods (see {@code ApertureMcpAutoConfiguration} in
 * {@code aperture-simple-mcp}). There is no live MCP server object to register a tool
 * instance with at generation time — generation runs inside the build (a Maven plugin
 * execution), long before the generated project's own JVM starts. So, exactly like
 * {@code CliCommandContribution} for the generated CLI, implementations act as
 * <b>source emitters</b>: {@link #toolSource} returns the complete Java source of a
 * {@code @Component} class with one or more {@code @Tool}-annotated methods, which the MCP
 * generation target writes to the generated project's {@code com.itsjool.aperture.generated.mcp}
 * package alongside the entity tool classes. The result is an ordinary generated-project class,
 * picked up automatically by the same component scan and reflective discovery that finds the
 * generated entity tools — no additional wiring is needed on the consumer's part.
 *
 * <p>Implementations are configured explicitly via the Maven plugin's {@code <mcp><extensions>}
 * list — ServiceLoader-based discovery is not used, and the implementation class must be on the
 * Maven plugin's classpath (e.g. via a {@code <dependency>} on the {@code aperture-maven-plugin}
 * {@code <plugin>} element).
 */
public interface McpToolContribution {
    /**
     * Unique identifier for this contribution (e.g. "billing-tools"), used in error messages.
     */
    String id();

    /**
     * Simple (unqualified) class name of the contributed tool class, e.g. "BillingMcpTools".
     * The generator writes the source returned by {@link #toolSource} to
     * {@code <toolClassName>.java} in package {@code com.itsjool.aperture.generated.mcp}.
     */
    String toolClassName();

    /**
     * Complete Java source for the contributed tool class. Must declare package
     * {@code com.itsjool.aperture.generated.mcp} and define a Spring {@code @Component} class
     * named {@link #toolClassName()} with one or more {@code @Tool}-annotated methods, following
     * the same conventions as the generator's own entity tool classes (e.g. a {@code McpRequestAdapter}
     * constructor dependency for calling back into the running API, {@code @ToolParam} on
     * parameters).
     *
     * @param latestApiVersion the highest active API version (e.g. "2"), for use in generated
     *                          request paths or documentation, matching the convention used by
     *                          the generator's own built-in tool classes.
     */
    String toolSource(String latestApiVersion);
}
