package io.conclave.stream;

import static org.assertj.core.api.Assertions.assertThat;

import io.conclave.events.security.AuthEvent;
import io.conclave.events.security.AuthMethod;
import io.conclave.events.security.AuthResult;
import io.conclave.events.security.EnrichedAuthEvent;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityFeatureSpec} using {@link TopologyTestDriver}.
 */
class SecurityFeatureSpecTest {

    private static final String REGISTRY = "mock://m2-security-unit-" + UUID.randomUUID();

    private SecurityFeatureSpec spec;
    private TopologyTestDriver driver;
    private TestInputTopic<String, AuthEvent> in;
    private TestOutputTopic<String, EnrichedAuthEvent> out;

    @BeforeEach
    void setUp() throws Exception {
        spec = new SecurityFeatureSpec(REGISTRY);
        Topology topology = FeatureExtractionTopology.build(spec);
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "security-unit-" + UUID.randomUUID());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        props.put(StreamsConfig.STATE_DIR_CONFIG, Files.createTempDirectory("conclave-state").toString());
        driver = new TopologyTestDriver(topology, props);
        in = driver.createInputTopic(spec.inputTopic(),
                Serdes.String().serializer(), spec.rawSerde().serializer());
        out = driver.createOutputTopic(spec.outputTopic(),
                Serdes.String().deserializer(), spec.enrichedSerde().deserializer());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    @DisplayName("principalVelocity increments on every event; failedLoginsRecent only on non-SUCCESS")
    void velocityAndFailedCountTrackedIndependently() {
        AuthEvent e1 = authFor("user_alice", AuthResult.SUCCESS);
        AuthEvent e2 = authFor("user_alice", AuthResult.FAILURE);
        AuthEvent e3 = authFor("user_alice", AuthResult.SUCCESS);
        AuthEvent e4 = authFor("user_alice", AuthResult.FAILURE);
        AuthEvent e5 = authFor("user_bob",   AuthResult.FAILURE);

        in.pipeInput(e1.getEventId(), e1);
        in.pipeInput(e2.getEventId(), e2);
        in.pipeInput(e3.getEventId(), e3);
        in.pipeInput(e4.getEventId(), e4);
        in.pipeInput(e5.getEventId(), e5);

        List<TestRecord<String, EnrichedAuthEvent>> records = out.readRecordsToList();
        assertThat(records).hasSize(5);

        // alice: total 4, failed 2  (e2, e4)
        // bob:   total 1, failed 1  (e5)
        EnrichedAuthEvent r1 = findByEventId(records, e1.getEventId());
        EnrichedAuthEvent r2 = findByEventId(records, e2.getEventId());
        EnrichedAuthEvent r3 = findByEventId(records, e3.getEventId());
        EnrichedAuthEvent r4 = findByEventId(records, e4.getEventId());
        EnrichedAuthEvent r5 = findByEventId(records, e5.getEventId());

        assertThat(r1.getPrincipalVelocity()).isEqualTo(1L);
        assertThat(r1.getFailedLoginsRecent()).isEqualTo(0L);
        assertThat(r2.getPrincipalVelocity()).isEqualTo(2L);
        assertThat(r2.getFailedLoginsRecent()).isEqualTo(1L);
        assertThat(r3.getPrincipalVelocity()).isEqualTo(3L);
        assertThat(r3.getFailedLoginsRecent()).isEqualTo(1L);  // unchanged from r2
        assertThat(r4.getPrincipalVelocity()).isEqualTo(4L);
        assertThat(r4.getFailedLoginsRecent()).isEqualTo(2L);
        assertThat(r5.getPrincipalVelocity()).isEqualTo(1L);
        assertThat(r5.getFailedLoginsRecent()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Enriched output preserves every raw field and keys by eventId")
    void preservesAllRawFields() {
        AuthEvent raw = authFor("user_zara", AuthResult.SUCCESS);

        in.pipeInput(raw.getEventId(), raw);

        TestRecord<String, EnrichedAuthEvent> rec = out.readRecord();
        assertThat(rec.getKey()).isEqualTo(raw.getEventId());
        EnrichedAuthEvent e = rec.getValue();
        assertThat(e.getEventId()).isEqualTo(raw.getEventId());
        assertThat(e.getTimestamp()).isEqualTo(raw.getTimestamp());
        assertThat(e.getPrincipalId()).isEqualTo(raw.getPrincipalId());
        assertThat(e.getHostId()).isEqualTo(raw.getHostId());
        assertThat(e.getSourceIp()).isEqualTo(raw.getSourceIp());
        assertThat(e.getAuthMethod()).isEqualTo(raw.getAuthMethod().name());
        assertThat(e.getResult()).isEqualTo(raw.getResult().name());
        assertThat(e.getTargetResource()).isEqualTo(raw.getTargetResource());
        assertThat(e.getUserAgent()).isEqualTo(raw.getUserAgent());
        assertThat(e.getSessionId()).isEqualTo(raw.getSessionId());
        assertThat(e.getIsPrivileged()).isEqualTo(raw.getIsPrivileged());
    }

    @Test
    @DisplayName("graphEntityIds includes principal/host/ip and (when present) targetResource")
    void graphEntityIdsIncludeOptionalTargetResource() {
        AuthEvent withTarget = authFor("user_x", AuthResult.SUCCESS);
        AuthEvent withoutTarget = AuthEvent.newBuilder(withTarget)
                .setEventId(UUID.randomUUID().toString())
                .setTargetResource(null)
                .build();

        in.pipeInput(withTarget.getEventId(), withTarget);
        in.pipeInput(withoutTarget.getEventId(), withoutTarget);

        List<TestRecord<String, EnrichedAuthEvent>> records = out.readRecordsToList();
        EnrichedAuthEvent withT = findByEventId(records, withTarget.getEventId());
        EnrichedAuthEvent withoutT = findByEventId(records, withoutTarget.getEventId());

        assertThat(withT.getGraphEntityIds()).contains(withTarget.getTargetResource());
        assertThat(withoutT.getGraphEntityIds()).hasSize(3); // principal, host, ip only
    }

    @Test
    @DisplayName("All AuthResult symbols other than SUCCESS count toward failedLoginsRecent")
    void allNonSuccessResultsCountAsFailures() {
        for (AuthResult r : AuthResult.values()) {
            if (r == AuthResult.SUCCESS) continue;
            AuthEvent e = authFor("user_" + r.name(), r);
            in.pipeInput(e.getEventId(), e);
            EnrichedAuthEvent enriched = out.readRecord().getValue();
            assertThat(enriched.getFailedLoginsRecent())
                    .as("result=%s", r)
                    .isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("SecurityFeatureSpec exposes the correct domain wiring")
    void domainWiring() {
        assertThat(spec.domain().key()).isEqualTo("security");
        assertThat(spec.inputTopic()).isEqualTo("events.security.raw");
        assertThat(spec.outputTopic()).isEqualTo("events.security.enriched");
        assertThat(spec.rawType()).isEqualTo(AuthEvent.class);
        assertThat(spec.enrichedType()).isEqualTo(EnrichedAuthEvent.class);
    }

    private static AuthEvent authFor(String principalId, AuthResult result) {
        return AuthEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTimestamp(Instant.now())
                .setPrincipalId(principalId)
                .setHostId("host_app01")
                .setSourceIp("10.0.0.10")
                .setAuthMethod(AuthMethod.PASSWORD)
                .setResult(result)
                .setTargetResource("/api/v1/data")
                .setUserAgent("test-agent")
                .setSessionId(UUID.randomUUID().toString())
                .setIsPrivileged(false)
                .build();
    }

    private static EnrichedAuthEvent findByEventId(
            List<TestRecord<String, EnrichedAuthEvent>> records, String eventId) {
        for (TestRecord<String, EnrichedAuthEvent> r : records) {
            if (r.getKey().equals(eventId)) return r.getValue();
        }
        throw new AssertionError("no record with eventId=" + eventId);
    }
}
