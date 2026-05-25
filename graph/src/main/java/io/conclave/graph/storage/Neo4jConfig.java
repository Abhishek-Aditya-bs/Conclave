package io.conclave.graph.storage;

import io.conclave.graph.config.GraphProperties;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Neo4j {@link Driver} bean from {@link GraphProperties}.
 *
 * <p>We use the raw {@code neo4j-java-driver} rather than Spring Data Neo4j. The
 * service surface is "execute one of a fixed set of Cypher templates" — Spring Data's
 * OGM and repository abstractions buy nothing on top of that, and the raw API keeps
 * latency and behaviour predictable.
 */
@Configuration
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(GraphProperties properties) {
        GraphProperties.Neo4j n = properties.neo4j();
        return GraphDatabase.driver(
                n.uri(),
                AuthTokens.basic(n.username(), n.password()));
    }
}
