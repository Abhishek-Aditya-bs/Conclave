package io.conclave.generators.security;

import io.conclave.events.security.AuthEvent;
import io.conclave.events.security.AuthMethod;
import io.conclave.events.security.AuthResult;
import io.conclave.generators.LabeledEvent;
import io.conclave.generators.Labels;
import io.conclave.generators.Scenario;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Multi-day, same-principal organic auth traffic for a whole population — the security-domain
 * mirror of {@code MultiDayPopulationScenario}.
 *
 * <p>Generates {@code principals × days × eventsPerDay} CLEAN auth events. Each principal
 * keeps a stable {@link SecurityPrincipalProfile} (home hosts, preferred method, user agent,
 * privilege) across the whole window. The behavioural spread across principals — different
 * host pools, method preferences, and privilege — is the security analogue of the fraud
 * amount distributions, and it is what gives the M3 baseline + graph realistic per-principal
 * history to learn before the adversarial scenarios deviate from it.
 */
public class SecurityMultiDayPopulationScenario implements Scenario {

    private final int principals;
    private final int days;
    private final int eventsPerDay;
    private final Random random;
    private final Instant windowStart;

    public SecurityMultiDayPopulationScenario(int principals, int days, int eventsPerDay,
                                              Random random, Instant windowStart) {
        if (principals < 0 || days < 0 || eventsPerDay < 0) {
            throw new IllegalArgumentException("principals/days/eventsPerDay must be >= 0");
        }
        this.principals = principals;
        this.days = days;
        this.eventsPerDay = eventsPerDay;
        this.random = random;
        this.windowStart = windowStart;
    }

    @Override
    public Stream<LabeledEvent> generate() {
        List<LabeledEvent> events = new ArrayList<>(principals * days * eventsPerDay);
        for (int p = 0; p < principals; p++) {
            SecurityPrincipalProfile profile = SecurityPrincipalProfile.sample(p, random);
            for (int day = 0; day < days; day++) {
                for (int e = 0; e < eventsPerDay; e++) {
                    events.add(event(profile, day));
                }
            }
        }
        return events.stream();
    }

    private LabeledEvent event(SecurityPrincipalProfile p, int day) {
        String host = p.homeHosts.get(random.nextInt(p.homeHosts.size()));
        // Mostly the preferred method; occasionally another normal one.
        AuthMethod method = random.nextInt(100) < 85
                ? p.preferredMethod
                : SecurityCatalog.NORMAL_METHODS.get(random.nextInt(SecurityCatalog.NORMAL_METHODS.size()));
        AuthResult result = SecurityCatalog.NORMAL_OUTCOMES.get(
                random.nextInt(SecurityCatalog.NORMAL_OUTCOMES.size()));
        Instant ts = windowStart
                .plus(Duration.ofDays(day))
                .plus(Duration.ofMinutes(random.nextInt(24 * 60)));
        // Privileged principals occasionally touch a benign resource — gives the graph
        // ACCESSED→Resource edges without tripping the sensitive-access template.
        String resource = (p.privileged && random.nextInt(5) == 0)
                ? "/home/" + p.principalId + "/reports/day" + day
                : null;
        AuthEvent event = AuthEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTimestamp(ts)
                .setPrincipalId(p.principalId)
                .setHostId(host)
                .setSourceIp("10.20." + (Math.abs(p.principalId.hashCode()) % 64) + "." + (1 + random.nextInt(254)))
                .setAuthMethod(method)
                .setResult(result)
                .setTargetResource(resource)
                .setUserAgent(p.userAgent)
                .setSessionId(UUID.randomUUID().toString())
                .setIsPrivileged(p.privileged)
                .build();
        return new LabeledEvent(event, Labels.CLEAN, "security-population-" + p.principalId,
                "multi-day organic auth, day=" + day + ", host=" + host);
    }
}
