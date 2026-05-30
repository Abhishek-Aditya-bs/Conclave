package io.conclave.orchestrator.client;

import io.conclave.orchestrator.config.DecisionOrchestratorProperties;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Owns the gRPC channel + {@link DeliberationClient} bean lifecycle.
 *
 * <p>The channel is built lazily on first use (Netty's default) so a
 * misconfigured target doesn't fail Spring startup — the channel
 * reports unavailable on the first call instead, which the orchestrator
 * routes to the DLQ.
 *
 * <p>{@code destroyMethod = "shutdown"} kicks off a graceful shutdown
 * when the Spring context closes; we then wait up to 5s in
 * {@link #channelShutdown(ManagedChannel)} for in-flight calls to drain.
 */
@Configuration
@ConditionalOnProperty(name = "conclave.orchestrator.enabled", havingValue = "true",
        matchIfMissing = true)
public class DeliberationClientConfig {

    private static final Logger LOG = LoggerFactory.getLogger(DeliberationClientConfig.class);

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel deliberationChannel(DecisionOrchestratorProperties properties) {
        String target = properties.deliberationTarget();
        LOG.info("Building gRPC channel to deliberation service at {}", target);
        // forTarget accepts "host:port" or "dns:///host:port" — the latter is
        // preferred for clustered deployments but the demo uses bare host:port.
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();
    }

    @Bean
    public DeliberationClient deliberationClient(
            ManagedChannel deliberationChannel,
            DecisionOrchestratorProperties properties) {
        return new DeliberationClient(
                deliberationChannel,
                properties.deliberationDeadlineMs(),
                Clock.systemUTC());
    }

    /**
     * Helper to keep the destroy semantics honest. Spring will call
     * {@code shutdown()} on the channel bean (because of
     * {@code destroyMethod} above); this method is for any future code
     * paths that want to drain a channel synchronously.
     */
    public static void channelShutdown(ManagedChannel channel) {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("gRPC channel did not terminate in 5s; forcing shutdown");
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
