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
 * Organic auth traffic: principals authenticating to their own workstation,
 * occasional MFA prompts, mostly successful. Provides the negative class so
 * the eval pipeline can compute precision under realistic base rates.
 */
public class CleanAuthScenario implements Scenario {

    private final int count;
    private final Random random;
    private final Instant baseTime;

    public CleanAuthScenario(int count, Random random, Instant baseTime) {
        this.count = count;
        this.random = random;
        this.baseTime = baseTime;
    }

    @Override
    public Stream<LabeledEvent> generate() {
        return Stream.generate(this::nextEvent).limit(count);
    }

    private LabeledEvent nextEvent() {
        String principal = "user_" + (1000 + random.nextInt(200));
        AuthMethod method = SecurityCatalog.NORMAL_METHODS.get(
                random.nextInt(SecurityCatalog.NORMAL_METHODS.size()));
        AuthResult result = SecurityCatalog.NORMAL_OUTCOMES.get(
                random.nextInt(SecurityCatalog.NORMAL_OUTCOMES.size()));
        String host = SecurityCatalog.HOMES.get(random.nextInt(SecurityCatalog.HOMES.size()));
        AuthEvent event = AuthEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTimestamp(baseTime.plus(Duration.ofMillis((long) random.nextInt(60_000))))
                .setPrincipalId(principal)
                .setHostId(host)
                .setSourceIp("10.10." + random.nextInt(64) + "." + (1 + random.nextInt(254)))
                .setAuthMethod(method)
                .setResult(result)
                .setTargetResource(null)
                .setUserAgent(SecurityCatalog.USER_AGENTS.get(random.nextInt(SecurityCatalog.USER_AGENTS.size())))
                .setSessionId(UUID.randomUUID().toString())
                .setIsPrivileged(false)
                .build();
        return new LabeledEvent(event, Labels.CLEAN, "clean-auth",
                "organic auth, principal=" + principal + " → " + host);
    }
}
