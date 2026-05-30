package io.conclave.graph.ingest;

import org.apache.avro.generic.IndexedRecord;

/**
 * Reads fields off a generic Avro record by name. The graph ingest consumer
 * deserializes enriched events with {@code specific.avro.reader=false}, so it
 * receives {@link IndexedRecord}s (not the generated SpecificRecord classes) and
 * never has to depend on the {@code io.conclave.events.*} generated sources.
 *
 * <p>Avro string fields arrive as {@code org.apache.avro.util.Utf8}; calling
 * {@code toString()} normalizes them to {@link String}.
 */
final class AvroFields {

    private AvroFields() {
        /* static-only */
    }

    /** Read a string-typed field, or {@code null} if absent / unset. */
    static String str(IndexedRecord record, String field) {
        var f = record.getSchema().getField(field);
        if (f == null) {
            return null;
        }
        Object value = record.get(f.pos());
        return value == null ? null : value.toString();
    }

    /** Read a boolean field, defaulting to {@code false} if absent / unset. */
    static boolean bool(IndexedRecord record, String field) {
        var f = record.getSchema().getField(field);
        if (f == null) {
            return false;
        }
        Object value = record.get(f.pos());
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
