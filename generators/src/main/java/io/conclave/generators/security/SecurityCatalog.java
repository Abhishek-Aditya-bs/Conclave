package io.conclave.generators.security;

import io.conclave.events.security.AuthMethod;
import io.conclave.events.security.AuthResult;
import java.util.List;

/**
 * Reference data shared across the security scenarios.
 */
final class SecurityCatalog {

    static final List<AuthMethod> NORMAL_METHODS = List.of(
            AuthMethod.SSO, AuthMethod.MFA, AuthMethod.PASSWORD);

    static final List<AuthMethod> SERVICE_METHODS = List.of(
            AuthMethod.API_KEY, AuthMethod.CERTIFICATE, AuthMethod.SSH_KEY);

    static final List<String> HOMES = List.of("workstation-1", "workstation-2", "workstation-3");

    /** Hosts that imply elevated access and feed the graph reasoner's PrivilegedAccessTemplate. */
    static final List<String> SENSITIVE_HOSTS = List.of(
            "prod-db-1", "prod-db-2", "vault-1", "vault-2", "billing-1", "secrets-1");

    /** Resource paths for exfil scenarios — keep recognisable so the verdict reads well. */
    static final List<String> SENSITIVE_RESOURCES = List.of(
            "/customers/export.csv", "/finance/q4.xlsx", "/keys/root-ca.pem",
            "/billing/dump.sql", "/identities/admin.json");

    static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5)",
            "okta-verify/9.21",
            "kubectl/1.31",
            "boto3/1.34",
            "curl/8.7");

    static final List<AuthResult> NORMAL_OUTCOMES = List.of(
            AuthResult.SUCCESS, AuthResult.SUCCESS, AuthResult.SUCCESS,
            AuthResult.SUCCESS, AuthResult.MFA_REQUIRED, AuthResult.FAILURE);

    private SecurityCatalog() {}
}
