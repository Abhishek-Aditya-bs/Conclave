package io.conclave.graph.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

/**
 * Unit tests for {@link GraphWriter} + {@link ResourceSensitivity}. The Neo4j
 * {@link Driver}/{@link Session} are mocked so we assert on the Cypher + params
 * sent, not on a live database (that's covered by the IT).
 */
class GraphWriterTest {

    @Test
    void classifiesResourceSensitivity() {
        assertThat(ResourceSensitivity.classify("/keys/root-ca.pem")).isEqualTo("restricted");
        assertThat(ResourceSensitivity.classify("/billing/dump.sql")).isEqualTo("restricted");
        assertThat(ResourceSensitivity.classify("/customers/export.csv")).isEqualTo("high");
        assertThat(ResourceSensitivity.classify("/finance/q4.xlsx")).isEqualTo("high");
        assertThat(ResourceSensitivity.classify("/admin/console")).isEqualTo("high");
        assertThat(ResourceSensitivity.classify("/status/health")).isEqualTo("low");
        assertThat(ResourceSensitivity.classify(null)).isEqualTo("low");
        assertThat(ResourceSensitivity.classify("")).isEqualTo("low");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeFraudMergesCardholderGraph() {
        Session session = mock(Session.class);
        Driver driver = mockDriver(session);

        new GraphWriter(driver).writeFraud(fraudRecord());

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(session).run(contains("USED_DEVICE"), params.capture());
        assertThat(params.getValue())
                .containsEntry("cardholderId", "ch_ring0_card1")
                .containsEntry("deviceFingerprint", "dev_ring_0")
                .containsEntry("cardToken", "tok_stolen_0_1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeSecurityWritesHostAndSensitiveResource() {
        Session session = mock(Session.class);
        Driver driver = mockDriver(session);

        new GraphWriter(driver).writeSecurity(authRecord("svc_admin_0", "prod-db-1", "/billing/dump.sql"));

        verify(session).run(contains("[:ACCESSED]->(h)"), anyMap());
        ArgumentCaptor<Map<String, Object>> resourceParams = ArgumentCaptor.forClass(Map.class);
        verify(session).run(contains("Resource"), resourceParams.capture());
        assertThat(resourceParams.getValue())
                .containsEntry("principalId", "svc_admin_0")
                .containsEntry("targetResource", "/billing/dump.sql")
                .containsEntry("sensitivity", "restricted");
    }

    @Test
    void writeSecuritySkipsResourceWhenAbsent() {
        Session session = mock(Session.class);
        Driver driver = mockDriver(session);

        // Record has no targetResource field at all → no resource MERGE.
        new GraphWriter(driver).writeSecurity(authRecordHostOnly("user_lateral_0", "host_lm_0_3"));

        verify(session).run(contains("[:ACCESSED]->(h)"), anyMap());
        verify(session, never()).run(contains("Resource"), anyMap());
    }

    // ---- helpers ----

    private static Driver mockDriver(Session session) {
        Driver driver = mock(Driver.class);
        Result result = mock(Result.class);
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        return driver;
    }

    private static GenericRecord fraudRecord() {
        Schema schema = SchemaBuilder.record("EnrichedPaymentEvent")
                .namespace("io.conclave.events.fraud").fields()
                .requiredString("cardholderId")
                .requiredString("deviceFingerprint")
                .requiredString("ipAddress")
                .requiredString("merchantId")
                .requiredString("cardToken")
                .endRecord();
        GenericData.Record r = new GenericData.Record(schema);
        r.put("cardholderId", "ch_ring0_card1");
        r.put("deviceFingerprint", "dev_ring_0");
        r.put("ipAddress", "203.0.113.10");
        r.put("merchantId", "merch_900");
        r.put("cardToken", "tok_stolen_0_1");
        return r;
    }

    private static GenericRecord authRecord(String principal, String host, String resource) {
        Schema schema = SchemaBuilder.record("EnrichedAuthEvent")
                .namespace("io.conclave.events.security").fields()
                .requiredString("principalId")
                .requiredString("hostId")
                .requiredString("targetResource")
                .endRecord();
        GenericData.Record r = new GenericData.Record(schema);
        r.put("principalId", principal);
        r.put("hostId", host);
        r.put("targetResource", resource);
        return r;
    }

    private static GenericRecord authRecordHostOnly(String principal, String host) {
        Schema schema = SchemaBuilder.record("EnrichedAuthEvent")
                .namespace("io.conclave.events.security").fields()
                .requiredString("principalId")
                .requiredString("hostId")
                .endRecord();
        GenericData.Record r = new GenericData.Record(schema);
        r.put("principalId", principal);
        r.put("hostId", host);
        return r;
    }
}
