package io.conclave.graph;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entrypoint for the CONCLAVE M4 Graph Reasoner Service.
 *
 * <p>Exposes REST (port 8082) + gRPC (port 9092) over a shared
 * {@code GraphReasonerService}. Cypher templates are autowired as
 * {@code GraphTemplate} beans — one per domain-specific query — and registered
 * by name at startup.
 *
 * <p>Design rules (see [docs/adr/0003-graph-templates-and-schema.md]):
 * <ul>
 *   <li>No free-form query generation — every query is a pre-written, depth-bounded template.</li>
 *   <li>Latency-bounded — every template aims for sub-50ms p99 on a 1M-edge graph.</li>
 *   <li>Returns structured {@code GraphFinding}s, never raw rows.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("io.conclave.graph")
public class GraphApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraphApplication.class, args);
    }
}
