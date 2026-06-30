# Multi-stage build for aperture-demo
# Stage 1: Builder
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /workspace
COPY . .
RUN mvn -pl demos/aperture-demo -am package -DskipTests --no-transfer-progress

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Create non-root user
RUN addgroup -g 1000 -S appuser && adduser -u 1000 -S appuser -G appuser

# Copy JAR from builder
COPY --from=builder /workspace/demos/aperture-demo/target/aperture-demo-*.jar app.jar

# Set ownership
RUN chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=30s \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Graceful shutdown signal
STOPSIGNAL SIGTERM

# JVM container flags for proper resource limits
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
