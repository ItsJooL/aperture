FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /workspace
COPY . .
RUN mvn -pl demos/aperture-single-tenant-demo -am package -DskipTests --no-transfer-progress

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -g 1000 -S appuser && adduser -u 1000 -S appuser -G appuser
COPY --from=builder /workspace/demos/aperture-single-tenant-demo/target/aperture-single-tenant-demo-*.jar app.jar
RUN chown -R appuser:appuser /app

USER appuser
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=30s \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
