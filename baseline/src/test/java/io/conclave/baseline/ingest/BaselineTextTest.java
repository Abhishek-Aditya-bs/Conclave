package io.conclave.baseline.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Unit tests for the behavioral textualizer. */
class BaselineTextTest {

    private static Function<String, Object> fields(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m::get;
    }

    @Test
    void fraudTextCapturesBehavior() {
        String t = BaselineText.of("fraud", fields(
                "amountMinor", 1500L, "currency", "USD", "merchantCategoryCode", 5411,
                "billingCountry", "US", "channel", "WEB", "cardPresent", false));
        assertThat(t)
                .contains("payment")
                .contains("amount=small")
                .contains("currency=USD")
                .contains("mcc=5411")
                .contains("billing=US")
                .contains("channel=WEB")
                .contains("card_not_present")
                // emphasized discriminating tokens for a routine charge
                .contains("amount_tier_small")
                .contains("merchant_normal");
    }

    @Test
    void fraudHighTicketShiftsBucket() {
        // An ATO/bust-out style high-ticket charge lands in a different bucket → text shifts.
        assertThat(BaselineText.fraud(fields("amountMinor", 80_000L)))
                .contains("amount=xlarge")
                .contains("amount_tier_xlarge");
    }

    @Test
    void fraudBustOutEmbedsFarFromRoutineSpend() {
        // A high-value charge at a high-risk (jewelry) merchant with a geo split must
        // carry strongly-emphasized risk tokens absent from routine spend.
        String bustOut = BaselineText.fraud(fields(
                "amountMinor", 80_000L, "currency", "USD", "merchantCategoryCode", 5944,
                "billingCountry", "US", "shippingCountry", "RU", "channel", "WEB",
                "cardPresent", false));
        String routine = BaselineText.fraud(fields(
                "amountMinor", 1500L, "currency", "USD", "merchantCategoryCode", 5411,
                "billingCountry", "US", "channel", "WEB", "cardPresent", false));
        assertThat(bustOut)
                .contains("amount_tier_xlarge")
                .contains("merchant_high_risk")
                .contains("geo_split");
        assertThat(routine)
                .doesNotContain("merchant_high_risk")
                .doesNotContain("geo_split");
        assertThat(bustOut).isNotEqualTo(routine);
    }

    @Test
    void amountBuckets() {
        assertThat(BaselineText.amountBucket(500)).isEqualTo("small");
        assertThat(BaselineText.amountBucket(5_000)).isEqualTo("medium");
        assertThat(BaselineText.amountBucket(30_000)).isEqualTo("large");
        assertThat(BaselineText.amountBucket(200_000)).isEqualTo("xlarge");
    }

    @Test
    void highRiskMccDetection() {
        assertThat(BaselineText.highRiskMcc(5944)).isTrue();   // jewelry
        assertThat(BaselineText.highRiskMcc(7995)).isTrue();   // gambling
        assertThat(BaselineText.highRiskMcc(5411)).isFalse();  // grocery
    }

    @Test
    void userAgentClassification() {
        assertThat(BaselineText.userAgentClass("Mozilla/5.0")).isEqualTo("browser");
        assertThat(BaselineText.userAgentClass("curl/8.7")).isEqualTo("automation");
        assertThat(BaselineText.userAgentClass("boto3/1.34")).isEqualTo("automation");
        assertThat(BaselineText.userAgentClass("python-requests/2.31")).isEqualTo("automation");
        assertThat(BaselineText.userAgentClass(null)).isEqualTo("unknown");
    }

    @Test
    void securityTextCapturesBehavior() {
        String t = BaselineText.of("security", fields(
                "authMethod", "SSO", "result", "SUCCESS", "hostId", "workstation-1",
                "isPrivileged", false, "userAgent", "Mozilla/5.0"));
        assertThat(t)
                .contains("auth")
                .contains("method=SSO")
                .contains("result=SUCCESS")
                .contains("host=workstation-1")
                .contains("standard")
                .contains("no_resource")
                .contains("agent=Mozilla/5.0")
                // emphasized discriminating tokens for a clean browser login
                .contains("result_class_SUCCESS")
                .contains("agent_class_browser")
                .contains("privilege_no");
    }

    @Test
    void securityFailureFromScriptedAgentEmbedsFarFromCleanLogin() {
        // A failed-login from a scripted agent must carry emphasized FAILURE/automation
        // tokens that a clean SUCCESS/browser login does not — so the embedding shifts
        // far even though only two raw fields differ.
        String attack = BaselineText.security(fields(
                "authMethod", "PASSWORD", "result", "FAILURE", "hostId", "bastion-1",
                "isPrivileged", false, "userAgent", "curl/8.7"));
        String clean = BaselineText.security(fields(
                "authMethod", "SSO", "result", "SUCCESS", "hostId", "bastion-1",
                "isPrivileged", false, "userAgent", "Mozilla/5.0"));
        assertThat(attack)
                .contains("result_class_FAILURE")
                .contains("agent_class_automation")
                .doesNotContain("result_class_SUCCESS")
                .doesNotContain("agent_class_browser");
        assertThat(clean)
                .contains("result_class_SUCCESS")
                .contains("agent_class_browser");
        assertThat(attack).isNotEqualTo(clean);
    }

    @Test
    void securityPrivilegedResourceAccessShiftsText() {
        String t = BaselineText.security(fields(
                "authMethod", "API_KEY", "result", "SUCCESS", "hostId", "prod-db-1",
                "isPrivileged", true, "targetResource", "/billing/dump.sql", "userAgent", "boto3/1.34"));
        assertThat(t)
                .contains("privileged")
                .contains("resource=/billing/dump.sql")
                .contains("agent=boto3/1.34")
                .contains("privilege_yes")
                .contains("agent_class_automation");
    }

    @Test
    void textualizationIsDeterministic() {
        String a = BaselineText.security(fields(
                "authMethod", "PASSWORD", "result", "FAILURE", "hostId", "bastion-1",
                "isPrivileged", false, "userAgent", "curl/8.7"));
        String b = BaselineText.security(fields(
                "authMethod", "PASSWORD", "result", "FAILURE", "hostId", "bastion-1",
                "isPrivileged", false, "userAgent", "curl/8.7"));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void toleratesMissingFields() {
        // All fields absent → still a stable, non-empty string (no NPE).
        assertThat(BaselineText.of("fraud", fields())).contains("payment").contains("currency=unknown");
        assertThat(BaselineText.of("security", fields())).contains("auth").contains("method=unknown");
    }
}
