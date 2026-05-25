package io.conclave.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration bound from {@code conclave.ingest.*}.
 *
 * <p>The {@link #domain()} field has no default — it MUST be set by the active profile
 * (see {@code application-fraud.yaml} / {@code application-security.yaml}). Booting without
 * a domain configured is a programmer error; the profile-startup integration tests guard
 * against it.
 *
 * @param domain               which reference configuration this instance serves
 * @param rawTopicPartitions   partition count for the raw input topic; sized for the demo
 * @param replicationFactor    replication factor for the raw input topic; 1 for the demo
 */
@ConfigurationProperties(prefix = "conclave.ingest")
public record IngestProperties(
        EventDomain domain,
        @DefaultValue("3") int rawTopicPartitions,
        @DefaultValue("1") short replicationFactor) {

    public IngestProperties {
        if (domain == null) {
            throw new IllegalStateException(
                    "conclave.ingest.domain is required — set spring.profiles.active=fraud "
                  + "or spring.profiles.active=security (or set CONCLAVE_DOMAIN env var).");
        }
        if (rawTopicPartitions <= 0) {
            throw new IllegalArgumentException(
                    "conclave.ingest.raw-topic-partitions must be > 0, got " + rawTopicPartitions);
        }
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException(
                    "conclave.ingest.replication-factor must be > 0, got " + replicationFactor);
        }
    }
}
