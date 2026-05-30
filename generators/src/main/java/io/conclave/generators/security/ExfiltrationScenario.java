package io.conclave.generators.security;

import io.conclave.events.security.AuthEvent;
import io.conclave.events.security.AuthMethod;
import io.conclave.events.security.AuthResult;
import io.conclave.generators.LabeledEvent;
import io.conclave.generators.Labels;
import io.conclave.generators.Scenario;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Exfiltration: a privileged identity touches a sequence of sensitive resources
 * inside one session, often outside business hours. The graph reasoner's
 * {@code SecurityPrivilegedAccessTemplate} fires once any sensitive resource is
 * accessed; this scenario stacks accesses to make the verdict justification rich.
 */
public class ExfiltrationScenario implements Scenario {

    private final int campaignIndex;
    private final int accessCount;
    private final Random random;
    private final Instant baseTime;

    public ExfiltrationScenario(int campaignIndex, int accessCount, Random random, Instant baseTime) {
        this.campaignIndex = campaignIndex;
        this.accessCount = accessCount;
        this.random = random;
        this.baseTime = baseTime;
    }

    @Override
    public Stream<LabeledEvent> generate() {
        String principal = "svc_admin_" + campaignIndex;
        String host = SecurityCatalog.SENSITIVE_HOSTS.get(
                campaignIndex % SecurityCatalog.SENSITIVE_HOSTS.size());
        String session = UUID.randomUUID().toString();
        String scenarioId = "security-exfil-" + campaignIndex;
        return Stream.iterate(0, i -> i < accessCount, i -> i + 1)
                .map(i -> {
                    String resource = SecurityCatalog.SENSITIVE_RESOURCES.get(
                            (i + campaignIndex) % SecurityCatalog.SENSITIVE_RESOURCES.size());
                    AuthEvent event = AuthEvent.newBuilder()
                            .setEventId(UUID.randomUUID().toString())
                            .setTimestamp(baseTime.plus(Duration.ofSeconds(15L * i + random.nextInt(5))))
                            .setPrincipalId(principal)
                            .setHostId(host)
                            .setSourceIp("198.51.100." + (1 + campaignIndex))
                            .setAuthMethod(AuthMethod.API_KEY)
                            .setResult(AuthResult.SUCCESS)
                            .setTargetResource(resource)
                            .setUserAgent("boto3/1.34")
                            .setSessionId(session)
                            .setIsPrivileged(true)
                            .build();
                    return new LabeledEvent(event, Labels.EXFILTRATION, scenarioId,
                            "exfil " + campaignIndex + " — privileged read of " + resource);
                });
    }
}
