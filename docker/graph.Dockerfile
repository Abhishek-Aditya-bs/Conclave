# Runtime image for the M4 graph reasoner service.
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY graph/target/graph-0.1.0-SNAPSHOT.jar app.jar

# REST 8082, gRPC 9092. The gRPC port collides with the Kafka EXTERNAL host
# port (also 9092) — inside compose they're on different containers so it's
# fine; the host port mapping only exposes the orchestrator REST surface.
EXPOSE 8082 9092

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
