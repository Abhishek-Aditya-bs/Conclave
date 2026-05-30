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
 * Lateral movement: one principal authenticates to a sequence of hosts in quick
 * succession. The graph reasoner's {@code SecurityLateralMovementTemplate} flags above the
 * 5-host threshold; we emit 8 hops by default so the signal is unambiguous.
 *
 * <p>Uses SSH_KEY auth on most hops because that's the realistic blast-radius
 * vector once an attacker has a foothold; first hop is the initial login that
 * established the session.
 */
public class LateralMovementScenario implements Scenario {

    private final int campaignIndex;
    private final int hopCount;
    private final Random random;
    private final Instant baseTime;

    public LateralMovementScenario(int campaignIndex, int hopCount, Random random, Instant baseTime) {
        this.campaignIndex = campaignIndex;
        this.hopCount = hopCount;
        this.random = random;
        this.baseTime = baseTime;
    }

    @Override
    public Stream<LabeledEvent> generate() {
        String principal = "user_lateral_" + campaignIndex;
        String sourceIp = "172.16." + campaignIndex + "." + (10 + random.nextInt(200));
        String session = UUID.randomUUID().toString();
        String scenarioId = "security-lateral-" + campaignIndex;
        return Stream.iterate(0, i -> i < hopCount, i -> i + 1)
                .map(i -> {
                    String host = "host_lm_" + campaignIndex + "_" + i;
                    AuthMethod method = i == 0 ? AuthMethod.PASSWORD : AuthMethod.SSH_KEY;
                    AuthEvent event = AuthEvent.newBuilder()
                            .setEventId(UUID.randomUUID().toString())
                            .setTimestamp(baseTime.plus(Duration.ofSeconds(20L * i)))
                            .setPrincipalId(principal)
                            .setHostId(host)
                            .setSourceIp(sourceIp)
                            .setAuthMethod(method)
                            .setResult(AuthResult.SUCCESS)
                            .setTargetResource(null)
                            .setUserAgent("ssh/9.6p1")
                            .setSessionId(session)
                            .setIsPrivileged(false)
                            .build();
                    return new LabeledEvent(event, Labels.LATERAL_MOVEMENT, scenarioId,
                            "lateral hop " + i + " principal=" + principal + " → " + host);
                });
    }
}
