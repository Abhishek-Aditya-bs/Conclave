package io.conclave.baseline.ingest;

import org.apache.avro.generic.IndexedRecord;

/**
 * Reads fields off a generic Avro record by name. The baseline ingest consumer
 * deserializes enriched events with {@code specific.avro.reader=false}, so it gets
 * {@link IndexedRecord}s and never depends on the generated {@code io.conclave.events.*}
 * classes. Avro strings arrive as {@code Utf8}; {@code toString()} normalizes them.
 */
final class AvroFields {

    private AvroFields() {
        /* static-only */
    }

    /** Raw field value (Utf8/Long/Boolean/…), or {@code null} if absent/unset. */
    static Object raw(IndexedRecord record, String field) {
        var f = record.getSchema().getField(field);
        if (f == null) {
            return null;
        }
        return record.get(f.pos());
    }

    /** String field, or {@code null}. */
    static String str(IndexedRecord record, String field) {
        Object v = raw(record, field);
        return v == null ? null : v.toString();
    }
}
