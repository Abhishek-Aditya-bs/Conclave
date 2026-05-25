package io.conclave.graph.service;

import io.conclave.graph.domain.GraphFinding;
import io.conclave.graph.domain.GraphTemplate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The graph-reasoning façade that both the REST controller and the gRPC service sit
 * on top of. Two operations:
 *
 * <ul>
 *   <li>{@link #listTemplates(String)} — discovery, optionally filtered by domain.</li>
 *   <li>{@link #execute(String, Map)} — runs a named template against Neo4j and
 *       stamps the wall-clock latency on the resulting finding.</li>
 * </ul>
 *
 * <p>Templates auto-register via Spring's component scan; we build the name → template
 * map once at startup and reuse it for every call.
 */
@Service
public class GraphReasonerService {

    private static final Logger LOG = LoggerFactory.getLogger(GraphReasonerService.class);

    private final Map<String, GraphTemplate> registry;
    private final Driver driver;

    public GraphReasonerService(List<GraphTemplate> templates, Driver driver) {
        this.registry = templates.stream()
                .collect(Collectors.toUnmodifiableMap(
                        GraphTemplate::name,
                        t -> t,
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate template name: " + a.name());
                        }));
        this.driver = driver;
        LOG.info("Registered {} graph templates: {}", registry.size(), registry.keySet());
    }

    /** List templates, optionally filtered by domain. Sorted by name for determinism. */
    public List<GraphTemplate> listTemplates(String domain) {
        return registry.values().stream()
                .filter(t -> domain == null || domain.isBlank() || t.domain().equals(domain))
                .sorted(Comparator.comparing(GraphTemplate::name))
                .toList();
    }

    /**
     * Execute a named template. Returns empty if the name is unknown — the caller
     * decides whether to surface 404 / NOT_FOUND.
     */
    public Optional<GraphFinding> execute(String templateName, Map<String, String> params) {
        GraphTemplate template = registry.get(templateName);
        if (template == null) {
            LOG.debug("Unknown template requested: {}", templateName);
            return Optional.empty();
        }
        try (Session session = driver.session()) {
            long startNs = System.nanoTime();
            GraphFinding finding = template.execute(session, params);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            return Optional.of(finding.withLatency(elapsedMs));
        }
    }

    /** True if {@code templateName} is registered. */
    public boolean knows(String templateName) {
        return registry.containsKey(templateName);
    }
}
