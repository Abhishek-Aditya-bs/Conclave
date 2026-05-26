package io.conclave.generators;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeneratorDomainTest {

    @Test
    void rawAndLabelTopicNaming() {
        assertThat(GeneratorDomain.FRAUD.rawTopic()).isEqualTo("events.fraud.raw");
        assertThat(GeneratorDomain.FRAUD.labelsTopic()).isEqualTo("events.fraud.labels");
        assertThat(GeneratorDomain.SECURITY.rawTopic()).isEqualTo("events.security.raw");
        assertThat(GeneratorDomain.SECURITY.labelsTopic()).isEqualTo("events.security.labels");
    }

    @Test
    void keysMatchOrchestratorEventDomain() {
        // Sanity: keep these in sync with io.conclave.ingest.EventDomain.
        assertThat(GeneratorDomain.FRAUD.key()).isEqualTo("fraud");
        assertThat(GeneratorDomain.SECURITY.key()).isEqualTo("security");
    }
}
