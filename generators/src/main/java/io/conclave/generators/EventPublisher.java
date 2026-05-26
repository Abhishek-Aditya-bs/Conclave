package io.conclave.generators;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirror of {@code io.conclave.ingest.KafkaEventProducer}'s contract: every event keyed by
 * its {@code eventId}, publish settings hardened to {@code acks=all} +
 * {@code enable.idempotence=true} (M1 spec, profile-startup ITs assert these).
 *
 * <p>Owns two producers — one Avro for raw events, one String for label JSON — so it can
 * be closed cleanly at the end of a generator run.
 */
public class EventPublisher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventPublisher.class);
    private static final String EVENT_ID_FIELD = "eventId";

    private final Producer<String, SpecificRecord> rawProducer;
    private final Producer<String, String> labelProducer;
    private final GeneratorDomain domain;
    private final Clock clock;
    private final ObjectMapper mapper;

    public EventPublisher(GeneratorDomain domain, String bootstrapServers, String schemaRegistryUrl) {
        this(domain, buildRawProducer(bootstrapServers, schemaRegistryUrl),
                buildLabelProducer(bootstrapServers), Clock.systemUTC());
    }

    EventPublisher(GeneratorDomain domain,
                   Producer<String, SpecificRecord> rawProducer,
                   Producer<String, String> labelProducer,
                   Clock clock) {
        this.domain = Objects.requireNonNull(domain, "domain");
        this.rawProducer = Objects.requireNonNull(rawProducer, "rawProducer");
        this.labelProducer = Objects.requireNonNull(labelProducer, "labelProducer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = new ObjectMapper();
    }

    public void publish(LabeledEvent labeled) {
        Objects.requireNonNull(labeled, "labeled");
        String eventId = extractEventId(labeled.event());
        try {
            rawProducer.send(new ProducerRecord<>(domain.rawTopic(), eventId, labeled.event()))
                    .get(30, TimeUnit.SECONDS);
            String labelJson = mapper.writeValueAsString(new LabelRecord(
                    eventId, domain.key(), labeled.label(),
                    labeled.scenarioId(), labeled.reason(), clock.millis()));
            labelProducer.send(new ProducerRecord<>(domain.labelsTopic(), eventId, labelJson))
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PublishException("interrupted while publishing event " + eventId, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new PublishException("failed to publish event " + eventId, ex);
        } catch (JsonProcessingException ex) {
            throw new PublishException("failed to encode label JSON for event " + eventId, ex);
        }
    }

    @Override
    public void close() {
        try {
            rawProducer.flush();
            labelProducer.flush();
        } finally {
            try {
                rawProducer.close();
            } catch (RuntimeException ex) {
                LOG.warn("rawProducer.close threw", ex);
            }
            try {
                labelProducer.close();
            } catch (RuntimeException ex) {
                LOG.warn("labelProducer.close threw", ex);
            }
        }
    }

    static String extractEventId(SpecificRecord event) {
        Schema.Field field = event.getSchema().getField(EVENT_ID_FIELD);
        if (field == null) {
            throw new IllegalArgumentException(
                    "Avro schema " + event.getSchema().getFullName()
                  + " is missing required field '" + EVENT_ID_FIELD + "'");
        }
        Object value = event.get(field.pos());
        if (value == null) {
            throw new IllegalArgumentException(
                    "Event of type " + event.getSchema().getFullName()
                  + " has a null " + EVENT_ID_FIELD);
        }
        return value.toString();
    }

    private static Producer<String, SpecificRecord> buildRawProducer(
            String bootstrapServers, String schemaRegistryUrl) {
        Properties props = baseProducerProps(bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "io.confluent.kafka.serializers.KafkaAvroSerializer");
        props.put("schema.registry.url", schemaRegistryUrl);
        props.put("auto.register.schemas", "true");
        return new KafkaProducer<>(props);
    }

    private static Producer<String, String> buildLabelProducer(String bootstrapServers) {
        Properties props = baseProducerProps(bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private static Properties baseProducerProps(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Mirror M1's KafkaProducerConfig + application.yaml exactly.
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return props;
    }

    /** Thrown when a publish round-trip fails synchronously. */
    public static class PublishException extends RuntimeException {
        public PublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
