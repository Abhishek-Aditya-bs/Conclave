package io.conclave.stream;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Spring wiring for the M2 feature-extraction Kafka Streams app.
 *
 * <p>Builds the {@link Topology} from the active-profile {@link FeatureSpec}, constructs a
 * {@link KafkaStreams} instance from it, and starts it once the Spring context is ready
 * (so the broker config & topic auto-creation from M1 are in place before Streams polls).
 *
 * <p>The {@code APPLICATION_ID_CONFIG} is derived from the domain so fraud and security
 * deployments don't share consumer groups or state stores.
 */
@Configuration
public class KafkaStreamsConfig {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaStreamsConfig.class);

    @Bean
    public Topology featureExtractionTopology(FeatureSpec<?, ?> spec) {
        LOG.info("Building feature-extraction topology for domain {}", spec.domain());
        return FeatureExtractionTopology.build(spec);
    }

    @Bean(destroyMethod = "close")
    public KafkaStreams kafkaStreams(
            Topology topology, FeatureSpec<?, ?> spec, KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildStreamsProperties());
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "conclave-stream-" + spec.domain().key());
        // Both topics are String-keyed; explicit default so any derived KTables also use
        // String keys without per-operator overrides.
        props.putIfAbsent(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        // Reduce noise during shutdown — explicit close in destroyMethod handles teardown.
        props.putIfAbsent(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1);
        Properties properties = new Properties();
        properties.putAll(props);
        return new KafkaStreams(topology, properties);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startStreams(ApplicationReadyEvent event) {
        KafkaStreams streams = event.getApplicationContext().getBean(KafkaStreams.class);
        LOG.info("Starting KafkaStreams for application.id={}",
                streams.metadataForLocalThreads());
        streams.start();
    }
}
