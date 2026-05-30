package io.conclave.baseline.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.conclave.baseline.service.BaselineService;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/** Routing + resilience tests for {@link BaselineIngestConsumer} (BaselineService mocked). */
class BaselineIngestConsumerTest {

    private static GenericRecord record(String name, String namespace, String entityId) {
        var builder = SchemaBuilder.record(name).namespace(namespace).fields();
        if (entityId != null) {
            builder = builder.requiredString("baselineEntityId");
        }
        Schema schema = builder.endRecord();
        GenericData.Record r = new GenericData.Record(schema);
        if (entityId != null) {
            r.put("baselineEntityId", entityId);
        }
        return r;
    }

    @Test
    void routesPaymentEventToFraudBaseline() {
        BaselineService svc = mock(BaselineService.class);
        new BaselineIngestConsumer(svc)
                .onEnrichedEvent(record("EnrichedPaymentEvent", "io.conclave.events.fraud", "ch_ato_0"));
        verify(svc).update(eq("fraud"), eq("ch_ato_0"), contains("payment"));
    }

    @Test
    void routesAuthEventToSecurityBaseline() {
        BaselineService svc = mock(BaselineService.class);
        new BaselineIngestConsumer(svc)
                .onEnrichedEvent(record("EnrichedAuthEvent", "io.conclave.events.security", "user_ato_0"));
        verify(svc).update(eq("security"), eq("user_ato_0"), contains("auth"));
    }

    @Test
    void skipsEventWithoutEntityId() {
        BaselineService svc = mock(BaselineService.class);
        new BaselineIngestConsumer(svc)
                .onEnrichedEvent(record("EnrichedPaymentEvent", "io.conclave.events.fraud", null));
        verifyNoInteractions(svc);
    }

    @Test
    void ignoresUnknownRecordType() {
        BaselineService svc = mock(BaselineService.class);
        new BaselineIngestConsumer(svc)
                .onEnrichedEvent(record("SomethingElse", "io.conclave.x", "e1"));
        verifyNoInteractions(svc);
    }

    @Test
    void ignoresNull() {
        BaselineService svc = mock(BaselineService.class);
        new BaselineIngestConsumer(svc).onEnrichedEvent(null);
        verifyNoInteractions(svc);
    }

    @Test
    void swallowsServiceExceptions() {
        BaselineService svc = mock(BaselineService.class);
        doThrow(new RuntimeException("db down")).when(svc).update(anyString(), anyString(), any());
        // Must not propagate.
        new BaselineIngestConsumer(svc)
                .onEnrichedEvent(record("EnrichedPaymentEvent", "io.conclave.events.fraud", "ch_1"));
        verify(svc).update(eq("fraud"), eq("ch_1"), any());
    }
}
