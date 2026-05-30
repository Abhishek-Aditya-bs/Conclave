package io.conclave.ingest;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Default {@link EventProducer} implementation backed by Spring's {@link KafkaTemplate}.
 *
 * <p>Key derivation: every Avro schema includes a top-level {@code eventId} string field
 * (see {@code configs/fraud/schema.avsc} and {@code configs/security/schema.avsc}). This
 * producer looks the field up by name on the record's schema once per send. If the field is
 * missing the producer fails fast — that situation indicates a schema regression worth
 * catching during local dev, not silently routing.
 */
@Component
public class KafkaEventProducer implements EventProducer {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventProducer.class);
    private static final String EVENT_ID_FIELD = "eventId";

    private final KafkaTemplate<String, SpecificRecord> template;
    private final IngestProperties properties;

    public KafkaEventProducer(KafkaTemplate<String, SpecificRecord> template,
                              IngestProperties properties) {
        this.template = Objects.requireNonNull(template, "template");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public CompletableFuture<SendResult<String, SpecificRecord>> publish(SpecificRecord event) {
        Objects.requireNonNull(event, "event");
        String key = extractEventId(event);
        String topic = properties.domain().rawTopic();
        LOG.debug("publishing event {} to {}", key, topic);
        return template.send(topic, key, event);
    }

    private static String extractEventId(SpecificRecord event) {
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
}
