# Runtime image for the M3 behavioral baseline service.
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY baseline/target/baseline-0.1.0-SNAPSHOT.jar app.jar

# REST 8081, gRPC 9091.
EXPOSE 8081 9091

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
