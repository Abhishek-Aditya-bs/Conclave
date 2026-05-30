package io.conclave.ingest;

/**
 * The two reference configurations CONCLAVE ships. Selected at startup via the
 * {@code conclave.ingest.domain} property bound by {@link IngestProperties}, which in turn is
 * driven by the active Spring profile ({@code fraud} or {@code security}).
 *
 * <p>Centralizes the topic naming convention so feature extraction, the decision
 * orchestrator, and downstream tooling all derive the same names from one source.
 */
public enum EventDomain {
    FRAUD("fraud"),
    SECURITY("security");

    private final String key;

    EventDomain(String key) {
        this.key = key;
    }

    /** Short kebab key used in topic names, config paths, dashboard URLs, etc. */
    public String key() {
        return key;
    }

    /** Topic carrying the raw, unenriched events for this domain. Producers write here. */
    public String rawTopic() {
        return "events." + key + ".raw";
    }

    /** Topic carrying enriched events emitted by the feature-extraction job. */
    public String enrichedTopic() {
        return "events." + key + ".enriched";
    }

    /** Topic carrying final decisions emitted by the orchestrator. */
    public String decisionsTopic() {
        return "decisions." + key;
    }

    /**
     * Topic carrying decision-orchestration failures (judge timeout, downstream
     * DB error, malformed event). The orchestrator publishes here instead of dropping the
     * event so a downstream replay job can re-attempt or alert.
     */
    public String decisionsFailedTopic() {
        return "decisions." + key + ".failed";
    }
}
