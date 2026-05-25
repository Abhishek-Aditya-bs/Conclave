package io.conclave.orchestrator.encode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.IndexedRecord;
import org.springframework.stereotype.Component;

/**
 * Avro → clean JSON for M5's {@code DeliberationRequest.enriched_event_json} field.
 *
 * <p>Avro's built-in {@code JsonEncoder} emits an Avro-specific JSON shape:
 * union types are wrapped as {@code {"string": "DE"}} rather than just
 * {@code "DE"}, and timestamps emit as numbers without preserving the
 * logical type. M5's Python {@code feature_node} expects plain JSON
 * (field name → value, no union wrapping, Instants as epoch ms), so we
 * walk the schema ourselves.
 *
 * <p>Stateless and thread-safe — the {@link ObjectMapper} is configured
 * for stable, deterministic output (no indentation, insertion-order
 * field iteration via {@link LinkedHashMap}).
 *
 * <p>This translation is the M6 ↔ M5 contract glue: any field added to
 * the M2 enriched-schema flows through here without code changes, and
 * the M5 Python fixtures match the shape produced by this encoder.
 */
@Component
public class EnrichedEventJsonEncoder {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Serialize an Avro {@link IndexedRecord} (e.g. {@code EnrichedPaymentEvent},
     * {@code EnrichedAuthEvent}) to a plain JSON string.
     */
    public String encode(IndexedRecord record) {
        Map<String, Object> map = toMap(record);
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            // The recursive walk only emits primitives, strings, lists, and maps
            // — all natively JSON-serializable. Any failure is a programming
            // error (e.g. a new Avro type slipped into the schema).
            throw new IllegalStateException(
                    "Failed to JSON-encode Avro record " + record.getSchema().getFullName(), e);
        }
    }

    private Map<String, Object> toMap(IndexedRecord record) {
        Schema schema = record.getSchema();
        // LinkedHashMap preserves field order from the schema — useful for
        // log readability and for prompt-cache stability on the M5 side.
        Map<String, Object> map = new LinkedHashMap<>();
        List<Schema.Field> fields = schema.getFields();
        for (Schema.Field field : fields) {
            map.put(field.name(), encodeValue(record.get(field.pos())));
        }
        return map;
    }

    private Object encodeValue(Object value) {
        // null first — handles optional Avro fields without any union ceremony.
        if (value == null) {
            return null;
        }
        // Nested records (e.g. if we ever add struct-shaped enriched fields).
        if (value instanceof IndexedRecord nested) {
            return toMap(nested);
        }
        // Instants come from Avro's timestamp-millis logical type. M5's
        // Python feature_node expects epoch millis (long), not ISO 8601 —
        // matches the canonical fixture shape in agents/tests/conftest.py.
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        // CharSequence covers both String and Avro's Utf8 (rare given the
        // plugin's stringType=String setting, but cheap insurance).
        if (value instanceof CharSequence cs) {
            return cs.toString();
        }
        // Avro arrays land as java.util.List; the elements may themselves
        // need encoding (strings, records, etc.).
        if (value instanceof List<?> list) {
            return list.stream().map(this::encodeValue).toList();
        }
        // Avro maps — flatten values recursively.
        if (value instanceof Map<?, ?> avroMap) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : avroMap.entrySet()) {
                out.put(entry.getKey().toString(), encodeValue(entry.getValue()));
            }
            return out;
        }
        // Primitives + boxed numerics + booleans flow through as-is — Jackson
        // emits the correct JSON shape for each.
        return value;
    }
}
