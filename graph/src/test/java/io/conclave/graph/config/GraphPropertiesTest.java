package io.conclave.graph.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GraphPropertiesTest {

    @Test
    @DisplayName("Valid construction succeeds")
    void valid() {
        GraphProperties props = new GraphProperties(
                new GraphProperties.Neo4j("bolt://x:7687", "u", "p"), 2);
        assertThat(props.maxPathDepth()).isEqualTo(2);
        assertThat(props.neo4j().uri()).isEqualTo("bolt://x:7687");
    }

    @Test
    @DisplayName("Null neo4j sub-config is rejected")
    void rejectsNullNeo4j() {
        assertThatThrownBy(() -> new GraphProperties(null, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("neo4j");
    }

    @Test
    @DisplayName("Blank Neo4j URI is rejected")
    void rejectsBlankUri() {
        assertThatThrownBy(() -> new GraphProperties.Neo4j("", "u", "p"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new GraphProperties.Neo4j(null, "u", "p"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("max-path-depth must be in [1, 5]")
    void rejectsOutOfRangeDepth() {
        GraphProperties.Neo4j neo4j = new GraphProperties.Neo4j("bolt://x:7687", "u", "p");
        assertThatThrownBy(() -> new GraphProperties(neo4j, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphProperties(neo4j, 6))
                .isInstanceOf(IllegalArgumentException.class);
        new GraphProperties(neo4j, 1);
        new GraphProperties(neo4j, 5);
    }
}
