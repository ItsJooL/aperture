FROM eclipse-temurin:25-jre

WORKDIR /app
COPY target/aperture-vault-demo-1.0.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
