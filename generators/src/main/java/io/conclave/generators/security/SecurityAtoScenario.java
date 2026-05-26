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
 * Account takeover for a security principal: warmup of normal SSO logins from a
 * home IP, then sudden password auth from a foreign IP and odd user-agent. The
 * baseline embedding shifts; M5's judge fires.
 */
public class SecurityAtoScenario implements Scenario {

    private final int campaignIndex;
    private final int warmupCount;
    private final int compromisedCount;
    private final Random random;
    private final Instant baseTime;

    public SecurityAtoScenario(int campaignIndex, int warmupCount, int compromisedCount,
                               Random random, Instant baseTime) {
        this.campaignIndex = campaignIndex;
        this.warmupCount = warmupCount;
        this.compromisedCount = compromisedCount;
        this.random = random;
        this.baseTime = baseTime;
    }

    @Override
    public Stream<LabeledEvent> generate() {
        String principal = "user_ato_" + campaignIndex;
        String scenarioId = "security-ato-" + campaignIndex;
        String homeIp = "10.10." + campaignIndex + "." + (1 + random.nextInt(20));
        String compromisedIp = "45.83." + (10 + campaignIndex) + "." + (1 + random.nextInt(254));
        String homeHost = SecurityCatalog.HOMES.get(campaignIndex % SecurityCatalog.HOMES.size());

        Stream<LabeledEvent> warmup = Stream.iterate(0, i -> i < warmupCount, i -> i + 1)
                .map(i -> {
                    AuthEvent ev = AuthEvent.newBuilder()
                            .setEventId(UUID.randomUUID().toString())
                            .setTimestamp(baseTime.plus(Duration.ofMinutes(i)))
                            .setPrincipalId(principal)
                            .setHostId(homeHost)
                            .setSourceIp(homeIp)
                            .setAuthMethod(AuthMethod.SSO)
                            .setResult(AuthResult.SUCCESS)
                            .setTargetResource(null)
                            .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5)")
                            .setSessionId(UUID.randomUUID().toString())
                            .setIsPrivileged(false)
                            .build();
                    return new LabeledEvent(ev, Labels.CLEAN, scenarioId + "-warmup",
                            "benign warmup before security ATO " + campaignIndex);
                });

        Stream<LabeledEvent> compromised = Stream.iterate(0, i -> i < compromisedCount, i -> i + 1)
                .map(i -> {
                    AuthEvent ev = AuthEvent.newBuilder()
                            .setEventId(UUID.randomUUID().toString())
                            .setTimestamp(baseTime.plus(Duration.ofMinutes(warmupCount + i)))
                            .setPrincipalId(principal)
                            .setHostId(homeHost)
                            .setSourceIp(compromisedIp)
                            .setAuthMethod(AuthMethod.PASSWORD)
                            .setResult(i == 0 ? AuthResult.FAILURE : AuthResult.SUCCESS)
                            .setTargetResource("/admin/console")
                            .setUserAgent("python-requests/2.31")
                            .setSessionId(UUID.randomUUID().toString())
                            .setIsPrivileged(false)
                            .build();
                    return new LabeledEvent(ev, Labels.SECURITY_ATO, scenarioId,
                            "security ATO " + campaignIndex + " — foreign-IP password auth");
                });

        return Stream.concat(warmup, compromised);
    }
}
