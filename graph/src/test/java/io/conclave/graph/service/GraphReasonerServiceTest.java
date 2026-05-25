package io.conclave.graph.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.conclave.graph.domain.GraphFinding;
import io.conclave.graph.domain.GraphTemplate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

/**
 * Unit tests for {@link GraphReasonerService} — registry construction, name lookup,
 * empty result for unknown templates, latency stamping. The actual Cypher execution
 * is covered by the per-domain ITs.
 */
class GraphReasonerServiceTest {

    @Test
    @DisplayName("Registry indexes templates by name and exposes them in sorted order")
    void registryBuildsByName() {
        GraphTemplate fraudA = stub("fraud_a", "fraud");
        GraphTemplate fraudB = stub("fraud_b", "fraud");
        GraphTemplate securityA = stub("sec_a", "security");

        GraphReasonerService svc = new GraphReasonerService(
                List.of(securityA, fraudB, fraudA), mock(Driver.class));

        assertThat(svc.listTemplates(null)).extracting(GraphTemplate::name)
                .containsExactly("fraud_a", "fraud_b", "sec_a");
        assertThat(svc.listTemplates("fraud")).extracting(GraphTemplate::name)
                .containsExactly("fraud_a", "fraud_b");
        assertThat(svc.listTemplates("security")).extracting(GraphTemplate::name)
                .containsExactly("sec_a");
        assertThat(svc.listTemplates("")).hasSize(3); // blank == no filter
    }

    @Test
    @DisplayName("Duplicate template names are rejected at construction")
    void duplicateNamesRejected() {
        GraphTemplate t1 = stub("dup", "fraud");
        GraphTemplate t2 = stub("dup", "security");

        assertThatThrownBy(() -> new GraphReasonerService(List.of(t1, t2), mock(Driver.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    @DisplayName("execute() returns empty Optional for an unknown template name")
    void executeUnknown() {
        GraphReasonerService svc = new GraphReasonerService(
                List.of(stub("known", "fraud")), mock(Driver.class));

        Optional<GraphFinding> found = svc.execute("does-not-exist", Map.of());
        assertThat(found).isEmpty();
        assertThat(svc.knows("does-not-exist")).isFalse();
        assertThat(svc.knows("known")).isTrue();
    }

    @Test
    @DisplayName("execute() stamps measured latency on the returned finding")
    void executeStampsLatency() {
        // The stub returns a finding with latency=0; the service must overwrite it
        // with the measured wall-clock value.
        Driver driver = mock(Driver.class);
        Session session = mock(Session.class);
        when(driver.session()).thenReturn(session);

        GraphTemplate template = mock(GraphTemplate.class);
        when(template.name()).thenReturn("t");
        when(template.execute(any(), any())).thenAnswer(invocation -> {
            try { Thread.sleep(2); } catch (InterruptedException ignored) {}
            return new GraphFinding("t", "e", "fraud", Map.of(), 0.0, 0L);
        });

        GraphReasonerService svc = new GraphReasonerService(List.of(template), driver);

        Optional<GraphFinding> found = svc.execute("t", Map.of());
        assertThat(found).isPresent();
        // We slept 2ms; latency stamp should be at least 1ms (allowing for rounding down).
        assertThat(found.get().queryLatencyMs()).isGreaterThanOrEqualTo(1L);
    }

    private static GraphTemplate stub(String name, String domain) {
        GraphTemplate t = mock(GraphTemplate.class);
        when(t.name()).thenReturn(name);
        when(t.domain()).thenReturn(domain);
        when(t.description()).thenReturn("stub");
        return t;
    }
}
