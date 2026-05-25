package io.conclave.orchestrator.encode;

import io.conclave.deliberation.proto.DeliberationRequest;
import io.conclave.ingest.EventDomain;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.IndexedRecord;
import org.springframework.stereotype.Component;

/**
 * Translates an Avro {@link IndexedRecord} (the M2 {@code EnrichedPaymentEvent}
 * or {@code EnrichedAuthEvent}) into the M5 {@link DeliberationRequest} proto.
 *
 * <p>Three well-known field names are pulled out as proto-level
 * orchestration scalars; the full record is also stringified to JSON for
 * the judge prompt. Keeping the well-known fields typed avoids the M5
 * Python side having to reparse the JSON to find IDs it already knows
 * the orchestrator extracted upstream.
 *
 * <ul>
 *   <li>{@code eventId} → {@code event_id}</li>
 *   <li>{@code baselineEntityId} → {@code baseline_entity_id}</li>
 *   <li>{@code graphEntityIds} → {@code graph_entity_ids}</li>
 * </ul>
 *
 * <p>Both M2 enriched schemas guarantee all three fields are present
 * ({@code configs/fraud/enriched-schema.avsc},
 * {@code configs/security/enriched-schema.avsc}). A missing field means
 * the upstream M2 contract has changed — fail fast with an
 * {@link IllegalArgumentException}.
 */
@Component
public class DeliberationRequestTranslator {

    private final EnrichedEventJsonEncoder encoder;

    public DeliberationRequestTranslator(EnrichedEventJsonEncoder encoder) {
        this.encoder = encoder;
    }

    public DeliberationRequest toRequest(EventDomain domain, IndexedRecord enriched) {
        String eventId = stringField(enriched, "eventId");
        String baselineEntityId = stringField(enriched, "baselineEntityId");
        List<String> graphEntityIds = stringListField(enriched, "graphEntityIds");
        String json = encoder.encode(enriched);

        return DeliberationRequest.newBuilder()
                .setEventId(eventId)
                .setDomain(domain.key())
                .setBaselineEntityId(baselineEntityId)
                .addAllGraphEntityIds(graphEntityIds)
                .setEnrichedEventJson(json)
                .build();
    }

    /**
     * Pull a non-null string field by name. Throws if the field is missing,
     * is the wrong type, or is null at runtime — all three of those mean the
     * upstream M2 schema changed without M6 catching up.
     */
    private static String stringField(IndexedRecord record, String fieldName) {
        Schema.Field field = record.getSchema().getField(fieldName);
        if (field == null) {
            throw new IllegalArgumentException(
                    "Enriched event missing required field '" + fieldName + "'");
        }
        Object value = record.get(field.pos());
        if (value == null) {
            throw new IllegalArgumentException(
                    "Enriched event field '" + fieldName + "' is null");
        }
        if (!(value instanceof CharSequence cs)) {
            throw new IllegalArgumentException(
                    "Enriched event field '" + fieldName + "' is not a string: " + value.getClass());
        }
        return cs.toString();
    }

    /**
     * Pull an array-of-string field by name. Returns an empty list if the
     * Avro array is empty; throws on shape mismatch.
     */
    @SuppressWarnings("unchecked")
    private static List<String> stringListField(IndexedRecord record, String fieldName) {
        Schema.Field field = record.getSchema().getField(fieldName);
        if (field == null) {
            throw new IllegalArgumentException(
                    "Enriched event missing required field '" + fieldName + "'");
        }
        Object value = record.get(field.pos());
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "Enriched event field '" + fieldName + "' is not a list: " + value.getClass());
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element == null) {
                continue;
            }
            if (!(element instanceof CharSequence cs)) {
                throw new IllegalArgumentException(
                        "Enriched event field '" + fieldName + "' contains non-string element: "
                                + element.getClass());
            }
            out.add(cs.toString());
        }
        return out;
    }
}
