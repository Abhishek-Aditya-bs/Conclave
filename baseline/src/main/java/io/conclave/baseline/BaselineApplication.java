package io.conclave.baseline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entrypoint for the CONCLAVE M3 Behavioral Baseline Service.
 *
 * <p>Exposes BOTH a REST API (port 8081) AND a gRPC service (port 9091) over a single
 * shared {@code BaselineService} bean. Storage is Postgres + pgvector; embeddings are
 * computed in-process via langchain4j's bundled MiniLM-L6-v2 model.
 *
 * <p>See [docs/adr/0002-baseline-storage-and-embedding.md] for the design decisions.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("io.conclave.baseline")
public class BaselineApplication {

    public static void main(String[] args) {
        SpringApplication.run(BaselineApplication.class, args);
    }
}
