package io.conclave.generators.security;

import io.conclave.events.security.AuthMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A stable synthetic principal (user/service account) reused across many days of auth
 * events. Fixes the things that should stay constant for a real principal — a small home
 * host pool, a preferred auth method, a user agent, a privilege flag — so the baseline
 * converges to a per-principal steady state that a later anomaly (lateral movement, ATO,
 * exfiltration) can deviate from. Heterogeneity across the population (different host
 * pools, methods, privilege) gives the realistic spread of behaviour benchmarking needs.
 */
final class SecurityPrincipalProfile {

    final String principalId;
    final List<String> homeHosts;
    final AuthMethod preferredMethod;
    final String userAgent;
    final boolean privileged;

    private SecurityPrincipalProfile(String principalId, List<String> homeHosts,
                                     AuthMethod preferredMethod, String userAgent,
                                     boolean privileged) {
        this.principalId = principalId;
        this.homeHosts = homeHosts;
        this.preferredMethod = preferredMethod;
        this.userAgent = userAgent;
        this.privileged = privileged;
    }

    /** Build a profile for principal {@code index}. */
    static SecurityPrincipalProfile sample(int index, Random r) {
        List<String> hosts = new ArrayList<>();
        int hostCount = 1 + r.nextInt(3); // 1-3 habitual hosts
        for (int h = 0; h < hostCount; h++) {
            hosts.add(SecurityCatalog.HOMES.get(r.nextInt(SecurityCatalog.HOMES.size())));
        }
        AuthMethod method = SecurityCatalog.NORMAL_METHODS.get(
                r.nextInt(SecurityCatalog.NORMAL_METHODS.size()));
        String userAgent = SecurityCatalog.USER_AGENTS.get(
                r.nextInt(SecurityCatalog.USER_AGENTS.size()));
        boolean privileged = r.nextInt(10) == 0; // ~10% privileged accounts
        return new SecurityPrincipalProfile(
                "user_pop_" + index, hosts, method, userAgent, privileged);
    }
}
