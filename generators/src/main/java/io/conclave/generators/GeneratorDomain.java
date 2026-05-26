package io.conclave.generators;

/**
 * Mirror of {@code io.conclave.ingest.EventDomain} (orchestrator module).
 * Duplicated here because the generators module deliberately avoids depending
 * on orchestrator/ (would pull in Spring Boot, Postgres, gRPC for a CLI tool).
 *
 * <p>The single source of truth for the topic naming convention is
 * {@code io.conclave.ingest.EventDomain}; bump both if either changes.
 */
public enum GeneratorDomain {
    FRAUD("fraud"),
    SECURITY("security");

    private final String key;

    GeneratorDomain(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public String rawTopic() {
        return "events." + key + ".raw";
    }

    public String labelsTopic() {
        return "events." + key + ".labels";
    }
}
