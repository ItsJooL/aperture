FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -g 1000 -S appuser && adduser -u 1000 -S appuser -G appuser
COPY target/aperture-audit-demo-*.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=30s \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
