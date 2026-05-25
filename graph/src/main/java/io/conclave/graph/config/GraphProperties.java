package io.conclave.graph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration bound from {@code conclave.graph.*}.
 *
 * @param neo4j          connection details for the backing Neo4j instance.
 * @param maxPathDepth   hard ceiling on Cypher path length in any template. Templates
 *                       must use values {@code <= maxPathDepth} in their {@code *1..N}
 *                       path quantifiers; this property is documented in the ADR but
 *                       not enforced by the runtime (templates carry their own caps).
 */
@ConfigurationProperties(prefix = "conclave.graph")
public record GraphProperties(
        Neo4j neo4j,
        @DefaultValue("3") int maxPathDepth) {

    public GraphProperties {
        if (neo4j == null) {
            throw new IllegalStateException("conclave.graph.neo4j.* must be set");
        }
        if (maxPathDepth <= 0 || maxPathDepth > 5) {
            throw new IllegalArgumentException(
                    "conclave.graph.max-path-depth must be in [1, 5], got " + maxPathDepth);
        }
    }

    public record Neo4j(String uri, String username, String password) {
        public Neo4j {
            if (uri == null || uri.isBlank()) {
                throw new IllegalStateException("conclave.graph.neo4j.uri is required");
            }
        }
    }
}
