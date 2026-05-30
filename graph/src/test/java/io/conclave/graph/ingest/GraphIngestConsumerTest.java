package io.conclave.graph.ingest;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/** Routing + resilience tests for {@link GraphIngestConsumer} (GraphWriter mocked). */
class GraphIngestConsumerTest {

    private static GenericRecord record(String name, String namespace) {
        Schema schema = SchemaBuilder.record(name).namespace(namespace).fields().endRecord();
        return new GenericData.Record(schema);
    }

    @Test
    void routesPaymentEventToFraudWriter() {
        GraphWriter writer = mock(GraphWriter.class);
        GenericRecord rec = record("EnrichedPaymentEvent", "io.conclave.events.fraud");
        new GraphIngestConsumer(writer).onEnrichedEvent(rec);
        verify(writer).writeFraud(rec);
    }

    @Test
    void routesAuthEventToSecurityWriter() {
        GraphWriter writer = mock(GraphWriter.class);
        GenericRecord rec = record("EnrichedAuthEvent", "io.conclave.events.security");
        new GraphIngestConsumer(writer).onEnrichedEvent(rec);
        verify(writer).writeSecurity(rec);
    }

    @Test
    void ignoresUnknownRecordType() {
        GraphWriter writer = mock(GraphWriter.class);
        new GraphIngestConsumer(writer).onEnrichedEvent(record("SomethingElse", "io.conclave.x"));
        verifyNoInteractions(writer);
    }

    @Test
    void ignoresNull() {
        GraphWriter writer = mock(GraphWriter.class);
        new GraphIngestConsumer(writer).onEnrichedEvent(null);
        verifyNoInteractions(writer);
    }

    @Test
    void swallowsWriterExceptionsSoTheConsumerKeepsRunning() {
        GraphWriter writer = mock(GraphWriter.class);
        GenericRecord rec = record("EnrichedPaymentEvent", "io.conclave.events.fraud");
        doThrow(new RuntimeException("neo4j down")).when(writer).writeFraud(rec);
        // Must not propagate — a single bad event can't kill the listener.
        new GraphIngestConsumer(writer).onEnrichedEvent(rec);
        verify(writer).writeFraud(rec);
    }
}
