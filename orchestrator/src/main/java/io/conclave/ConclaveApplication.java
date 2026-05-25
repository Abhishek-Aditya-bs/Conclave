package io.conclave;

import io.conclave.ingest.IngestProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entrypoint for the CONCLAVE data plane.
 *
 * <p>The active Spring profile selects the domain configuration (currently {@code fraud}
 * or {@code security}). See {@code application-fraud.yaml} / {@code application-security.yaml}
 * and {@link IngestProperties}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("io.conclave")
public class ConclaveApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConclaveApplication.class, args);
    }
}
