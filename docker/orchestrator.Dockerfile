# Runtime image for the M1/M2/M6/M7 orchestrator service.
#
# Built from a pre-packaged Spring Boot fat jar. ./scripts/up.sh runs
# `mvn package -DskipTests` first so this image only needs a JRE — keeps
# `docker compose build` under 10 seconds after the first run and avoids
# re-downloading the Maven repo inside Docker.
FROM eclipse-temurin:25-jre

WORKDIR /app

# Copied in from the host's repository root (compose build context).
COPY orchestrator/target/orchestrator-0.1.0-SNAPSHOT.jar app.jar

# Spring Boot listens on 8080; gRPC stubs not exposed here (the orchestrator
# is a gRPC client, not a server).
EXPOSE 8080

# Profile (fraud|security) supplied via SPRING_PROFILES_ACTIVE. All other
# config comes from env vars referenced in application.yaml.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
